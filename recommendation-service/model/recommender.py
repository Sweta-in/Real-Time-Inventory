"""
Recommendation engine using SVD collaborative filtering with cold-start fallback.
"""
import os
import logging
from typing import Optional

import pandas as pd
import psycopg2
import numpy as np

from model.trainer import ModelTrainer

logger = logging.getLogger(__name__)


class Recommender:
    def __init__(self, trainer: ModelTrainer):
        self.trainer = trainer
        self.popular_products: list[dict] = []
        self.cold_start_threshold = int(os.getenv("COLD_START_THRESHOLD", "3"))

    def get_user_purchase_count(self, user_id: str) -> int:
        db_url = os.getenv("DATABASE_URL", "postgresql://app_user:changeme_postgres_2024@localhost:5432/inventory_db")
        try:
            conn = psycopg2.connect(db_url)
            cur = conn.cursor()
            cur.execute("SELECT COUNT(*) FROM purchase_events WHERE user_id = %s", (user_id,))
            count = cur.fetchone()[0]
            cur.close()
            conn.close()
            return count
        except Exception as e:
            logger.error(f"Failed to get purchase count: {e}")
            return 0

    def load_popular_products(self):
        db_url = os.getenv("DATABASE_URL", "postgresql://app_user:changeme_postgres_2024@localhost:5432/inventory_db")
        try:
            conn = psycopg2.connect(db_url)
            df = pd.read_sql(
                """SELECT p.id, p.name, p.category, p.base_price, COUNT(pe.id) as purchase_count
                   FROM products p LEFT JOIN purchase_events pe ON p.id::text = pe.product_id::text
                   GROUP BY p.id, p.name, p.category, p.base_price ORDER BY purchase_count DESC LIMIT 50""", conn)
            conn.close()
            self.popular_products = df.to_dict("records")
            logger.info(f"Loaded {len(self.popular_products)} popular products")
        except Exception as e:
            logger.error(f"Failed to load popular products: {e}")

    def recommend(self, user_id: str, limit: int = 10, experiment_group: Optional[str] = None) -> list[dict]:
        if experiment_group == "B":
            return self._get_popular(limit)
        purchase_count = self.get_user_purchase_count(user_id)
        if purchase_count < self.cold_start_threshold:
            return self._get_popular(limit)
        if self.trainer.model is None:
            return self._get_popular(limit)
        try:
            return self._svd_recommend(user_id, limit)
        except Exception as e:
            logger.error(f"SVD recommendation failed: {e}")
            return self._get_popular(limit)

    def _svd_recommend(self, user_id: str, limit: int) -> list[dict]:
        trainset = self.trainer.trainset
        model = self.trainer.model
        all_items = [trainset.to_raw_iid(i) for i in trainset.all_items()]
        try:
            inner_uid = trainset.to_inner_uid(user_id)
            user_items = set(trainset.to_raw_iid(iid) for (iid, _) in trainset.ur[inner_uid])
        except ValueError:
            user_items = set()
        predictions = []
        for item_id in all_items:
            if item_id not in user_items:
                pred = model.predict(user_id, item_id)
                predictions.append({"product_id": str(item_id), "score": round(pred.est, 4), "algorithm": "svd"})
        predictions.sort(key=lambda x: x["score"], reverse=True)
        return predictions[:limit]

    def similar(self, product_id: str, limit: int = 10) -> list[dict]:
        if self.trainer.model is None:
            return self._get_popular(limit)
        try:
            trainset = self.trainer.trainset
            model = self.trainer.model
            inner_id = trainset.to_inner_iid(product_id)
            target_vector = model.qi[inner_id]
            similarities = []
            for other_inner_id in trainset.all_items():
                if other_inner_id != inner_id:
                    other_vector = model.qi[other_inner_id]
                    cos_sim = float(np.dot(target_vector, other_vector) / (np.linalg.norm(target_vector) * np.linalg.norm(other_vector) + 1e-8))
                    similarities.append({"product_id": str(trainset.to_raw_iid(other_inner_id)), "score": round(cos_sim, 4), "algorithm": "item_similarity"})
            similarities.sort(key=lambda x: x["score"], reverse=True)
            return similarities[:limit]
        except Exception as e:
            logger.error(f"Item similarity failed: {e}")
            return self._get_popular(limit)

    def _get_popular(self, limit: int) -> list[dict]:
        return [{"product_id": str(p.get("id", "")), "score": float(p.get("purchase_count", 0)), "algorithm": "popularity"} for p in self.popular_products[:limit]]
