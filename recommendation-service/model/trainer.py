"""
SVD collaborative filtering model trainer using Surprise library.
Trains on purchase event data, saves model artifacts.
"""
import os
import logging
import pickle
from datetime import datetime

import pandas as pd
import psycopg2
from surprise import SVD, Dataset, Reader, accuracy
from surprise.model_selection import train_test_split

logger = logging.getLogger(__name__)

ARTIFACTS_DIR = "/app/model/artifacts"


class ModelTrainer:
    def __init__(self):
        self.model = None
        self.last_trained = None
        self.last_rmse = None
        self.trainset = None

    def load_data_from_db(self) -> pd.DataFrame:
        """Load purchase events from PostgreSQL."""
        db_url = os.getenv(
            "DATABASE_URL",
            "postgresql://app_user:changeme_postgres_2024@localhost:5432/inventory_db"
        )
        try:
            conn = psycopg2.connect(db_url)
            df = pd.read_sql(
                "SELECT user_id, product_id, quantity FROM purchase_events",
                conn
            )
            conn.close()
            logger.info(f"Loaded {len(df)} purchase events from database")
            return df
        except Exception as e:
            logger.error(f"Failed to load data from DB: {e}")
            # Fallback to CSV if available
            csv_path = "/app/data/interactions.csv"
            if os.path.exists(csv_path):
                logger.info("Falling back to CSV data")
                return pd.read_csv(csv_path)[["user_id", "product_id", "quantity"]]
            raise

    def train(self) -> float:
        """Train SVD model and return RMSE on test set."""
        logger.info("Starting model training...")
        df = self.load_data_from_db()

        if df.empty:
            logger.warning("No training data available")
            return -1.0

        # Aggregate interactions: sum quantity per user-product pair as implicit rating
        ratings = df.groupby(["user_id", "product_id"])["quantity"].sum().reset_index()
        ratings.columns = ["user_id", "product_id", "rating"]

        # Normalize ratings to 1-5 scale
        max_rating = ratings["rating"].max()
        if max_rating > 0:
            ratings["rating"] = (ratings["rating"] / max_rating * 4) + 1
            ratings["rating"] = ratings["rating"].clip(1, 5)

        reader = Reader(rating_scale=(1, 5))
        data = Dataset.load_from_df(ratings[["user_id", "product_id", "rating"]], reader)

        trainset, testset = train_test_split(data, test_size=0.2, random_state=42)

        self.model = SVD(n_factors=50, n_epochs=20, lr_all=0.005, reg_all=0.02, random_state=42)
        self.model.fit(trainset)
        self.trainset = trainset

        predictions = self.model.test(testset)
        rmse = accuracy.rmse(predictions, verbose=False)
        self.last_rmse = rmse
        self.last_trained = datetime.now()

        logger.info(f"Model trained. RMSE: {rmse:.4f}")

        # Save model artifacts
        self._save_model()
        return rmse

    def _save_model(self):
        """Save trained model to disk."""
        os.makedirs(ARTIFACTS_DIR, exist_ok=True)
        model_path = os.path.join(ARTIFACTS_DIR, "svd_model.pkl")
        with open(model_path, "wb") as f:
            pickle.dump({
                "model": self.model,
                "trainset": self.trainset,
                "rmse": self.last_rmse,
                "trained_at": self.last_trained
            }, f)
        logger.info(f"Model saved to {model_path}")

    def load_model(self) -> bool:
        """Load model from disk if available."""
        model_path = os.path.join(ARTIFACTS_DIR, "svd_model.pkl")
        if os.path.exists(model_path):
            with open(model_path, "rb") as f:
                data = pickle.load(f)
                self.model = data["model"]
                self.trainset = data["trainset"]
                self.last_rmse = data["rmse"]
                self.last_trained = data["trained_at"]
            logger.info(f"Model loaded from {model_path}, RMSE: {self.last_rmse:.4f}")
            return True
        return False
