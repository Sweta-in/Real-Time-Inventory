"""
Agent tools — callable functions the ReAct agent can invoke.
Each tool makes HTTP calls to existing microservices and returns structured data.
"""
import os
import logging
import math
from typing import Any

import httpx

logger = logging.getLogger("reorder-agent-service")

INVENTORY_SERVICE_URL = os.getenv("INVENTORY_SERVICE_URL", "http://inventory-service:8081")
FORECASTING_SERVICE_URL = os.getenv("FORECASTING_SERVICE_URL", "http://forecasting-service:8083")
PRICING_SERVICE_URL = os.getenv("PRICING_SERVICE_URL", "http://pricing-service:8084")

# Default supplier lead times (days) and budget cap per reorder
SUPPLIER_LEAD_TIME_DAYS = int(os.getenv("SUPPLIER_LEAD_TIME_DAYS", "3"))
MAX_REORDER_BUDGET_USD = float(os.getenv("MAX_REORDER_BUDGET_USD", "5000.0"))
SAFETY_STOCK_MULTIPLIER = float(os.getenv("SAFETY_STOCK_MULTIPLIER", "1.5"))

# Shared async HTTP client
_client: httpx.AsyncClient | None = None


def get_client() -> httpx.AsyncClient:
    global _client
    if _client is None or _client.is_closed:
        _client = httpx.AsyncClient(timeout=15.0)
    return _client


# ═══════════════════════════════════════════
# TOOL DEFINITIONS — JSON schemas for the LLM
# ═══════════════════════════════════════════

TOOLS = [
    {
        "name": "get_forecast",
        "description": (
            "Get demand forecast for a specific product. Returns predicted daily "
            "demand for the next N days with confidence intervals, and the total "
            "predicted demand over 7 days."
        ),
        "parameters": {
            "product_id": {"type": "string", "description": "UUID of the product"},
            "days": {"type": "integer", "description": "Number of days to forecast (default 7)", "default": 7},
        },
    },
    {
        "name": "get_inventory",
        "description": (
            "Get current inventory level for a product across all warehouses. "
            "Returns stock level, capacity, warehouse ID, and last update time."
        ),
        "parameters": {
            "product_id": {"type": "string", "description": "UUID of the product"},
        },
    },
    {
        "name": "get_slow_movers",
        "description": (
            "Get list of slow-moving products from the pricing service. "
            "These are items that haven't sold in 14+ days and may need clearance "
            "rather than restocking."
        ),
        "parameters": {},
    },
    {
        "name": "calculate_reorder_quantity",
        "description": (
            "Calculate the optimal reorder quantity using the Economic Order Quantity "
            "model, factoring in predicted demand, current stock, supplier lead time, "
            "safety stock, and budget constraints. Returns recommended order quantity "
            "and estimated cost."
        ),
        "parameters": {
            "product_id": {"type": "string", "description": "UUID of the product"},
            "current_stock": {"type": "integer", "description": "Current stock level"},
            "predicted_demand_7d": {"type": "number", "description": "Predicted demand over next 7 days"},
            "base_price": {"type": "number", "description": "Unit price of the product"},
        },
    },
    {
        "name": "execute_restock",
        "description": (
            "Execute a restock order for a product in a specific warehouse. "
            "This actually creates stock — only call this after analyzing demand "
            "and confirming the reorder quantity is justified."
        ),
        "parameters": {
            "product_id": {"type": "string", "description": "UUID of the product"},
            "warehouse_id": {"type": "string", "description": "UUID of the target warehouse"},
            "quantity": {"type": "integer", "description": "Number of units to restock"},
        },
    },
]


# ═══════════════════════════════════════════
# TOOL IMPLEMENTATIONS
# ═══════════════════════════════════════════

async def get_forecast(product_id: str, days: int = 7) -> dict[str, Any]:
    """Fetch demand forecast from forecasting-service."""
    client = get_client()
    try:
        resp = await client.get(
            f"{FORECASTING_SERVICE_URL}/forecast/{product_id}",
            params={"days": days},
        )
        resp.raise_for_status()
        data = resp.json()
        return {
            "product_id": product_id,
            "predicted_demand_7d": data.get("predicted_demand_7d", 0),
            "forecast_days": data.get("forecast_days", days),
            "predictions_count": len(data.get("predictions", [])),
            "status": "success",
        }
    except httpx.HTTPStatusError as e:
        logger.warning(f"Forecast request failed for {product_id}: {e.response.status_code}")
        return {"product_id": product_id, "error": f"HTTP {e.response.status_code}", "status": "error"}
    except Exception as e:
        logger.error(f"Forecast request error for {product_id}: {e}")
        return {"product_id": product_id, "error": str(e), "status": "error"}


async def get_inventory(product_id: str) -> dict[str, Any]:
    """Fetch current inventory from inventory-service."""
    client = get_client()
    try:
        resp = await client.get(f"{INVENTORY_SERVICE_URL}/api/inventory/{product_id}")
        resp.raise_for_status()
        data = resp.json()
        return {
            "product_id": product_id,
            "stock_level": data.get("stockLevel", 0),
            "capacity_max": data.get("capacityMax", 0),
            "warehouse_id": data.get("warehouseId", ""),
            "product_name": data.get("productName", "Unknown"),
            "last_updated": data.get("lastUpdated", ""),
            "stock_ratio": round(
                data.get("stockLevel", 0) / max(data.get("capacityMax", 1), 1), 3
            ),
            "status": "success",
        }
    except httpx.HTTPStatusError as e:
        return {"product_id": product_id, "error": f"HTTP {e.response.status_code}", "status": "error"}
    except Exception as e:
        return {"product_id": product_id, "error": str(e), "status": "error"}


async def get_slow_movers() -> dict[str, Any]:
    """Fetch slow-moving products from pricing-service."""
    client = get_client()
    try:
        resp = await client.get(f"{PRICING_SERVICE_URL}/api/pricing/slow-movers")
        resp.raise_for_status()
        data = resp.json()
        return {
            "slow_movers": data if isinstance(data, list) else [],
            "count": len(data) if isinstance(data, list) else 0,
            "status": "success",
        }
    except Exception as e:
        logger.warning(f"Slow movers request failed: {e}")
        return {"slow_movers": [], "count": 0, "error": str(e), "status": "error"}


async def calculate_reorder_quantity(
    product_id: str,
    current_stock: int,
    predicted_demand_7d: float,
    base_price: float,
) -> dict[str, Any]:
    """
    Calculate optimal reorder quantity using a simplified EOQ model.
    Factors in: safety stock, lead time demand, capacity, and budget.
    """
    try:
        daily_demand = predicted_demand_7d / 7.0
        lead_time_demand = daily_demand * SUPPLIER_LEAD_TIME_DAYS
        safety_stock = daily_demand * SAFETY_STOCK_MULTIPLIER

        # Reorder point = lead time demand + safety stock
        reorder_point = lead_time_demand + safety_stock

        if current_stock > reorder_point:
            return {
                "product_id": product_id,
                "recommended_quantity": 0,
                "reason": "Stock is above reorder point — no reorder needed",
                "current_stock": current_stock,
                "reorder_point": round(reorder_point, 1),
                "safety_stock": round(safety_stock, 1),
                "status": "no_reorder",
            }

        # EOQ simplified: cover 14 days of demand + safety stock - current stock
        target_stock = (daily_demand * 14) + safety_stock
        quantity_needed = max(0, int(math.ceil(target_stock - current_stock)))

        # Budget constraint
        estimated_cost = quantity_needed * base_price * 0.6  # assume 60% COGS
        if estimated_cost > MAX_REORDER_BUDGET_USD:
            quantity_needed = int(MAX_REORDER_BUDGET_USD / (base_price * 0.6))
            estimated_cost = quantity_needed * base_price * 0.6

        return {
            "product_id": product_id,
            "recommended_quantity": quantity_needed,
            "estimated_cost_usd": round(estimated_cost, 2),
            "current_stock": current_stock,
            "reorder_point": round(reorder_point, 1),
            "safety_stock": round(safety_stock, 1),
            "lead_time_days": SUPPLIER_LEAD_TIME_DAYS,
            "daily_demand": round(daily_demand, 2),
            "target_stock_level": round(target_stock, 1),
            "budget_cap_usd": MAX_REORDER_BUDGET_USD,
            "status": "reorder_recommended",
        }
    except Exception as e:
        return {"product_id": product_id, "error": str(e), "status": "error"}


async def execute_restock(
    product_id: str, warehouse_id: str, quantity: int
) -> dict[str, Any]:
    """Execute a restock order via inventory-service API."""
    client = get_client()
    try:
        resp = await client.post(
            f"{INVENTORY_SERVICE_URL}/api/inventory/{product_id}/restock",
            json={"warehouseId": warehouse_id, "quantity": quantity},
            headers={"X-Correlation-ID": f"agent-restock-{product_id}"},
        )
        resp.raise_for_status()
        data = resp.json()
        return {
            "product_id": product_id,
            "warehouse_id": warehouse_id,
            "quantity_restocked": quantity,
            "new_stock_level": data.get("stockLevel", "unknown"),
            "status": "success",
        }
    except httpx.HTTPStatusError as e:
        return {
            "product_id": product_id,
            "error": f"Restock failed: HTTP {e.response.status_code}",
            "status": "error",
        }
    except Exception as e:
        return {"product_id": product_id, "error": str(e), "status": "error"}


# ═══════════════════════════════════════════
# TOOL DISPATCHER
# ═══════════════════════════════════════════

TOOL_REGISTRY: dict[str, Any] = {
    "get_forecast": get_forecast,
    "get_inventory": get_inventory,
    "get_slow_movers": get_slow_movers,
    "calculate_reorder_quantity": calculate_reorder_quantity,
    "execute_restock": execute_restock,
}


async def execute_tool(tool_name: str, args: dict) -> dict[str, Any]:
    """Dispatch a tool call by name with the given arguments."""
    func = TOOL_REGISTRY.get(tool_name)
    if func is None:
        return {"error": f"Unknown tool: {tool_name}", "status": "error"}
    try:
        result = await func(**args)
        return result
    except TypeError as e:
        return {"error": f"Invalid arguments for {tool_name}: {e}", "status": "error"}
    except Exception as e:
        return {"error": f"Tool execution failed: {e}", "status": "error"}
