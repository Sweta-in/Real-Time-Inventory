"""
Demand forecasting engine using Facebook Prophet.
"""
import os
import logging
import json
from datetime import datetime, timedelta

import pandas as pd
import numpy as np
import psycopg2
from kafka import KafkaProducer

logger = logging.getLogger(__name__)


class Forecaster:
    def __init__(self):
        self.kafka_producer = None
        self._init_kafka()

    def _init_kafka(self):
        bootstrap = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
        try:
            self.kafka_producer = KafkaProducer(
                bootstrap_servers=bootstrap,
                value_serializer=lambda v: json.dumps(v, default=str).encode("utf-8"),
                key_serializer=lambda k: k.encode("utf-8") if k else None,
            )
            logger.info("Kafka producer initialized")
        except Exception as e:
            logger.error(f"Failed to init Kafka producer: {e}")

    def _get_connection(self):
        db_url = os.getenv("DATABASE_URL", "postgresql://app_user:changeme_postgres_2024@localhost:5432/inventory_db")
        return psycopg2.connect(db_url)

    def forecast(self, product_id: str, days: int = 30) -> dict:
        """Run Prophet forecast for a single product."""
        try:
            conn = self._get_connection()
            df = pd.read_sql(
                """SELECT DATE(timestamp) as ds, SUM(quantity) as y
                   FROM purchase_events WHERE product_id = %s
                   GROUP BY DATE(timestamp) ORDER BY ds""",
                conn, params=(product_id,)
            )
            conn.close()

            if len(df) < 7:
                logger.warning(f"Not enough data for product {product_id} ({len(df)} days)")
                return {"product_id": product_id, "error": "insufficient_data", "data_points": len(df)}

            # Suppress Prophet logging
            import cmdstanpy
            cmdstanpy.utils.get_logger().disabled = True

            from prophet import Prophet
            model = Prophet(
                daily_seasonality=True,
                weekly_seasonality=True,
                yearly_seasonality=False,
                changepoint_prior_scale=0.05,
            )
            model.fit(df)

            future = model.make_future_dataframe(periods=days)
            forecast = model.predict(future)

            # Extract predictions for future dates only
            future_forecast = forecast[forecast["ds"] > df["ds"].max()]
            predictions = []
            for _, row in future_forecast.iterrows():
                predictions.append({
                    "date": row["ds"].strftime("%Y-%m-%d"),
                    "predicted_demand": max(0, round(row["yhat"], 2)),
                    "lower_bound": max(0, round(row["yhat_lower"], 2)),
                    "upper_bound": max(0, round(row["yhat_upper"], 2)),
                })

            total_7d = sum(p["predicted_demand"] for p in predictions[:7])

            return {
                "product_id": product_id,
                "forecast_days": days,
                "predictions": predictions,
                "predicted_demand_7d": round(total_7d, 2),
                "generated_at": datetime.now().isoformat(),
            }
        except Exception as e:
            logger.error(f"Forecast failed for product {product_id}: {e}")
            return {"product_id": product_id, "error": str(e)}

    def check_restock_alerts(self) -> list[dict]:
        """Check all products: if predicted_demand_7d > current_stock, publish restock alert."""
        alerts = []
        try:
            conn = self._get_connection()
            products = pd.read_sql("SELECT DISTINCT id FROM products", conn)
            inventory = pd.read_sql(
                "SELECT product_id, warehouse_id, stock_level FROM inventory_items", conn
            )
            conn.close()

            for _, row in products.iterrows():
                product_id = str(row["id"])
                result = self.forecast(product_id, days=7)

                if "error" in result:
                    continue

                demand_7d = result.get("predicted_demand_7d", 0)
                product_inventory = inventory[inventory["product_id"].astype(str) == product_id]

                for _, inv_row in product_inventory.iterrows():
                    current_stock = inv_row["stock_level"]
                    if demand_7d > current_stock:
                        recommended_qty = int(demand_7d - current_stock + (demand_7d * 0.2))
                        alert = {
                            "productId": product_id,
                            "warehouseId": str(inv_row["warehouse_id"]),
                            "currentStock": int(current_stock),
                            "predictedDemand7d": int(demand_7d),
                            "recommendedQuantity": recommended_qty,
                            "correlationId": f"forecast-{product_id}-{datetime.now().strftime('%Y%m%d')}",
                            "timestamp": datetime.now().isoformat(),
                        }
                        alerts.append(alert)
                        self._publish_restock_recommendation(alert)
        except Exception as e:
            logger.error(f"Restock alert check failed: {e}")

        logger.info(f"Generated {len(alerts)} restock alerts")
        return alerts

    def _publish_restock_recommendation(self, alert: dict):
        if self.kafka_producer:
            try:
                self.kafka_producer.send(
                    "restock-recommendations",
                    key=alert["productId"],
                    value=alert,
                )
                self.kafka_producer.flush()
                logger.info(f"Published restock recommendation for product {alert['productId']}")
            except Exception as e:
                logger.error(f"Failed to publish restock recommendation: {e}")
