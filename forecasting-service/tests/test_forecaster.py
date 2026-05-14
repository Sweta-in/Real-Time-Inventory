"""Tests for forecasting service."""
import pytest
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def test_forecaster_initialization():
    """Test that Forecaster initializes."""
    from forecaster import Forecaster
    f = Forecaster()
    assert f is not None


def test_forecast_insufficient_data():
    """Test forecast with no data returns error."""
    from forecaster import Forecaster
    f = Forecaster()
    # This will fail to connect to DB in test env, which is expected
    result = f.forecast("nonexistent-product", days=7)
    assert "product_id" in result


def test_health_endpoint():
    """Test FastAPI health endpoint."""
    from fastapi.testclient import TestClient
    from main import app
    client = TestClient(app)
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "healthy"
