"""
Forecasting Service — FastAPI application.
Provides demand forecasts and restock alerts using Prophet.
"""
import os
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
from starlette.responses import Response
from apscheduler.schedulers.background import BackgroundScheduler
from pythonjsonlogger import jsonlogger

from forecaster import Forecaster

# ── Structured JSON Logging ──
log_handler = logging.StreamHandler()
formatter = jsonlogger.JsonFormatter(
    fmt="%(asctime)s %(name)s %(levelname)s %(message)s",
    rename_fields={"asctime": "timestamp", "name": "serviceId", "levelname": "level"}
)
log_handler.setFormatter(formatter)
logging.root.handlers = [log_handler]
logging.root.setLevel(logging.INFO)
logger = logging.getLogger("forecasting-service")

# ── Prometheus Metrics ──
FORECAST_REQUESTS = Counter("forecast_requests_total", "Total forecast requests")
FORECAST_LATENCY = Histogram("forecast_request_duration_seconds", "Forecast request latency")

# ── Global State ──
forecaster = Forecaster()
scheduler = BackgroundScheduler()


def batch_forecast_job():
    logger.info("Running batch forecast for all products...")
    alerts = forecaster.check_restock_alerts()
    logger.info(f"Batch forecast complete. {len(alerts)} restock alerts generated.")


@asynccontextmanager
async def lifespan(app: FastAPI):
    scheduler.add_job(batch_forecast_job, "interval", hours=24, id="batch_forecast")
    scheduler.start()
    logger.info("Forecasting service started")
    yield
    scheduler.shutdown()


app = FastAPI(title="Forecasting Service", version="1.0.0", lifespan=lifespan)


@app.get("/forecast/{product_id}")
async def get_forecast(product_id: str, days: int = 30):
    import time
    start = time.time()
    FORECAST_REQUESTS.inc()
    result = forecaster.forecast(product_id, days)
    FORECAST_LATENCY.observe(time.time() - start)
    return result


@app.get("/forecast/restock-alerts")
async def get_restock_alerts():
    alerts = forecaster.check_restock_alerts()
    return {"alerts": alerts, "count": len(alerts)}


@app.get("/health")
async def health():
    return {"status": "healthy", "service": "forecasting-service"}


@app.get("/metrics")
async def metrics():
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)
