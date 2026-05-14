"""Tests for recommendation service."""
import pytest
import os
import sys

# Add parent directory to path
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def test_embedder_loads():
    """Test that the sentence-transformer model loads and produces correct dimensions."""
    from model.embedder import Embedder
    embedder = Embedder()
    embedding = embedder.embed("test product description")
    assert len(embedding) == 384
    assert all(isinstance(v, float) for v in embedding)


def test_embedder_batch():
    """Test batch embedding."""
    from model.embedder import Embedder
    embedder = Embedder()
    texts = ["product one", "product two", "product three"]
    embeddings = embedder.embed_batch(texts)
    assert len(embeddings) == 3
    assert all(len(e) == 384 for e in embeddings)


def test_trainer_initialization():
    """Test ModelTrainer initializes correctly."""
    from model.trainer import ModelTrainer
    trainer = ModelTrainer()
    assert trainer.model is None
    assert trainer.last_trained is None
    assert trainer.last_rmse is None


def test_recommender_cold_start():
    """Test that recommender returns popular items for cold-start users."""
    from model.trainer import ModelTrainer
    from model.recommender import Recommender

    trainer = ModelTrainer()
    recommender = Recommender(trainer)
    # With no model and no popular products, should return empty
    results = recommender.recommend("nonexistent_user", limit=5)
    assert isinstance(results, list)


def test_recommender_group_b():
    """Test A/B group B returns popularity-based results."""
    from model.trainer import ModelTrainer
    from model.recommender import Recommender

    trainer = ModelTrainer()
    recommender = Recommender(trainer)
    recommender.popular_products = [
        {"id": "prod1", "purchase_count": 100},
        {"id": "prod2", "purchase_count": 50},
    ]
    results = recommender.recommend("user1", limit=2, experiment_group="B")
    assert len(results) == 2
    assert results[0]["algorithm"] == "popularity"


def test_health_endpoint():
    """Test FastAPI health endpoint."""
    from fastapi.testclient import TestClient
    from main import app
    client = TestClient(app)
    response = client.get("/health")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "healthy"
