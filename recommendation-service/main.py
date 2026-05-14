"""
Recommendation Service — FastAPI application.
Serves ML recommendations, embeddings, and health checks.
"""
import os
import logging
import time
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, Header, Request
from pydantic import BaseModel
from prometheus_client import Counter, Histogram, Gauge, generate_latest, CONTENT_TYPE_LATEST
from starlette.responses import Response
from apscheduler.schedulers.background import BackgroundScheduler
from pythonjsonlogger import jsonlogger

from model.trainer import ModelTrainer
from model.recommender import Recommender
from model.embedder import Embedder

# ── Structured JSON Logging ──
log_handler = logging.StreamHandler()
formatter = jsonlogger.JsonFormatter(
    fmt="%(asctime)s %(name)s %(levelname)s %(message)s",
    rename_fields={"asctime": "timestamp", "name": "serviceId", "levelname": "level"}
)
log_handler.setFormatter(formatter)
logging.root.handlers = [log_handler]
logging.root.setLevel(logging.INFO)
logger = logging.getLogger("recommendation-service")

# ── Prometheus Metrics ──
REQUEST_COUNT = Counter("recommendation_requests_total", "Total recommendation requests", ["group"])
REQUEST_LATENCY = Histogram("recommendation_request_duration_seconds", "Request latency")
MODEL_RMSE = Gauge("model_rmse", "Current model RMSE")

# ── Global State ──
trainer = ModelTrainer()
recommender = Recommender(trainer)
embedder: Optional[Embedder] = None
scheduler = BackgroundScheduler()


def retrain_job():
    logger.info("Starting scheduled model retraining")
    rmse = trainer.train()
    if rmse > 0:
        MODEL_RMSE.set(rmse)
    recommender.load_popular_products()


@asynccontextmanager
async def lifespan(app: FastAPI):
    global embedder
    logger.info("Starting recommendation service...")
    # Load embedder
    embedder = Embedder()
    # Train model or load from disk
    if not trainer.load_model():
        try:
            rmse = trainer.train()
            if rmse > 0:
                MODEL_RMSE.set(rmse)
        except Exception as e:
            logger.warning(f"Initial training failed (no data yet?): {e}")
    recommender.load_popular_products()
    # Schedule retraining
    interval = int(os.getenv("MODEL_RETRAIN_INTERVAL_HOURS", "24"))
    scheduler.add_job(retrain_job, "interval", hours=interval, id="retrain")
    scheduler.start()
    yield
    scheduler.shutdown()


app = FastAPI(title="Recommendation Service", version="1.0.0", lifespan=lifespan)


class EmbedRequest(BaseModel):
    text: str

class EmbedResponse(BaseModel):
    embedding: list[float]


@app.get("/recommend/{user_id}")
async def get_recommendations(
    user_id: str,
    limit: int = 10,
    x_experiment_group: Optional[str] = Header(None, alias="X-Experiment-Group")
):
    start = time.time()
    group = x_experiment_group or "A"
    REQUEST_COUNT.labels(group=group).inc()
    results = recommender.recommend(user_id, limit, experiment_group=group)
    REQUEST_LATENCY.observe(time.time() - start)
    return {"user_id": user_id, "group": group, "recommendations": results}


@app.get("/similar/{product_id}")
async def get_similar(product_id: str, limit: int = 10):
    results = recommender.similar(product_id, limit)
    return {"product_id": product_id, "similar_products": results}


@app.post("/embed", response_model=EmbedResponse)
async def generate_embedding(request: EmbedRequest):
    if embedder is None:
        return EmbedResponse(embedding=[0.0] * 384)
    embedding = embedder.embed(request.text)
    return EmbedResponse(embedding=embedding)


@app.get("/health")
async def health():
    return {
        "status": "healthy",
        "model_loaded": trainer.model is not None,
        "last_trained": str(trainer.last_trained) if trainer.last_trained else None,
        "last_rmse": trainer.last_rmse,
        "embedder_loaded": embedder is not None
    }


@app.get("/metrics")
async def metrics():
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)
