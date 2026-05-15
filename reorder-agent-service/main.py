"""
Reorder Agent Service — FastAPI application.
An LLM-powered autonomous agent that analyzes demand and executes reorders.
"""
import os, json, logging, uuid
from datetime import datetime
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, Query, HTTPException
from pydantic import BaseModel
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST
from starlette.responses import Response
from apscheduler.schedulers.background import BackgroundScheduler
from pythonjsonlogger import jsonlogger
from kafka import KafkaProducer

from models import init_db, SessionLocal, AgentDecision, DecisionStatus
from agent import ReActAgent

# ── Structured JSON Logging ──
handler = logging.StreamHandler()
handler.setFormatter(jsonlogger.JsonFormatter(
    fmt="%(asctime)s %(name)s %(levelname)s %(message)s",
    rename_fields={"asctime": "timestamp", "name": "serviceId", "levelname": "level"}
))
logging.root.handlers = [handler]
logging.root.setLevel(logging.INFO)
logger = logging.getLogger("reorder-agent-service")

# ── Globals ──
agent = ReActAgent()
scheduler = BackgroundScheduler()
kafka_producer: Optional[KafkaProducer] = None

AGENT_RUN_INTERVAL_HOURS = int(os.getenv("AGENT_RUN_INTERVAL_HOURS", "6"))
KAFKA_BOOTSTRAP = os.getenv("KAFKA_BOOTSTRAP_SERVERS", "kafka:9092")


def init_kafka():
    global kafka_producer
    try:
        kafka_producer = KafkaProducer(
            bootstrap_servers=KAFKA_BOOTSTRAP,
            value_serializer=lambda v: json.dumps(v, default=str).encode("utf-8"),
            key_serializer=lambda k: k.encode("utf-8") if k else None,
        )
        logger.info("Kafka producer initialized")
    except Exception as e:
        logger.warning(f"Kafka init failed (will retry on publish): {e}")


def publish_decision(decision_data: dict):
    """Publish agent decision to Kafka topic reorder-decisions."""
    global kafka_producer
    if kafka_producer is None:
        init_kafka()
    if kafka_producer:
        try:
            kafka_producer.send("reorder-decisions", key=decision_data.get("correlation_id", ""), value=decision_data)
            kafka_producer.flush()
            logger.info(f"Published decision {decision_data.get('correlation_id')} to Kafka")
        except Exception as e:
            logger.error(f"Failed to publish decision to Kafka: {e}")


import asyncio

def scheduled_agent_run():
    """Scheduled job: run the agent for all products needing analysis."""
    logger.info("Scheduled agent run starting...")
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    try:
        result = loop.run_until_complete(run_agent_full())
        logger.info(f"Scheduled run complete: {result.get('status')}")
    except Exception as e:
        logger.error(f"Scheduled agent run failed: {e}")
    finally:
        loop.close()


async def run_agent_full(product_id: Optional[str] = None) -> dict:
    """Execute the agent and persist results."""
    correlation_id = f"agent-{uuid.uuid4().hex[:12]}"
    db = SessionLocal()

    try:
        # Create decision record
        decision = AgentDecision(
            correlation_id=correlation_id,
            product_id=product_id,
            status=DecisionStatus.REASONING,
        )
        db.add(decision)
        db.commit()
        db.refresh(decision)

        # Build task prompt
        if product_id:
            task = (
                f"Analyze product {product_id}: check its demand forecast, current inventory, "
                f"and decide whether to reorder. If reorder is needed, calculate the optimal "
                f"quantity and execute the restock."
            )
        else:
            task = (
                "Run a full inventory health check:\n"
                "1. First, check for slow-moving products to avoid restocking them\n"
                "2. Then pick 3-5 products that might need restocking and analyze each:\n"
                "   - Get the demand forecast\n"
                "   - Check current inventory levels\n"
                "   - Calculate reorder quantity if needed\n"
                "   - Execute restock if justified\n"
                "3. Summarize all decisions in FINAL_DECISION"
            )

        # Run the ReAct agent
        result = await agent.run(task=task, correlation_id=correlation_id, product_id=product_id)

        # Update decision record
        decision.status = (
            DecisionStatus.EXECUTED if result.get("final_decision", {}).get("action") == "reorder"
            else DecisionStatus.NO_ACTION if result["status"] == "completed"
            else DecisionStatus.FAILED
        )
        decision.reasoning_trace = result["reasoning_trace"]
        decision.final_decision = result.get("final_decision")
        decision.total_llm_calls = result.get("total_llm_calls", 0)
        decision.total_tokens_used = result.get("total_tokens_used", 0)
        decision.total_latency_seconds = result.get("total_latency_seconds", 0)
        decision.completed_at = datetime.utcnow()

        # Count reorders
        reorders = result.get("final_decision", {}).get("reorders", [])
        decision.reorders_executed = len(reorders) if isinstance(reorders, list) else 0
        decision.products_analyzed = len([
            s for s in result["reasoning_trace"] if s.get("tool_name") == "get_inventory"
        ])

        db.commit()

        # Publish to Kafka
        publish_decision({
            "correlation_id": correlation_id,
            "product_id": product_id,
            "status": decision.status.value,
            "final_decision": result.get("final_decision"),
            "reorders_executed": decision.reorders_executed,
            "total_llm_calls": decision.total_llm_calls,
            "timestamp": datetime.utcnow().isoformat(),
        })

        return result

    except Exception as e:
        logger.error(f"Agent run failed: {e}")
        decision.status = DecisionStatus.FAILED
        decision.reasoning_trace = [{"step": 0, "type": "error", "content": str(e)}]
        decision.completed_at = datetime.utcnow()
        db.commit()
        raise
    finally:
        db.close()


# ── FastAPI App ──

@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Starting reorder-agent-service...")
    init_db()
    init_kafka()
    scheduler.add_job(scheduled_agent_run, "interval", hours=AGENT_RUN_INTERVAL_HOURS, id="agent_run")
    scheduler.start()
    yield
    scheduler.shutdown()


app = FastAPI(title="Reorder Agent Service", version="1.0.0", lifespan=lifespan)


class TriggerRequest(BaseModel):
    product_id: Optional[str] = None


@app.post("/trigger")
async def trigger_agent(req: TriggerRequest = TriggerRequest()):
    """Manually trigger the agent for a specific product or full scan."""
    try:
        result = await run_agent_full(product_id=req.product_id)
        return {
            "status": result["status"],
            "correlation_id": result["correlation_id"],
            "final_decision": result.get("final_decision"),
            "total_llm_calls": result.get("total_llm_calls"),
            "total_tokens_used": result.get("total_tokens_used"),
            "reasoning_steps": len(result.get("reasoning_trace", [])),
            "latency_seconds": result.get("total_latency_seconds"),
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/decisions")
async def get_decisions(
    limit: int = Query(10, ge=1, le=100),
    product_id: Optional[str] = None,
):
    """Get agent decision logs with full reasoning traces."""
    db = SessionLocal()
    try:
        query = db.query(AgentDecision).order_by(AgentDecision.created_at.desc())
        if product_id:
            query = query.filter(AgentDecision.product_id == product_id)
        decisions = query.limit(limit).all()
        return {
            "decisions": [d.to_dict() for d in decisions],
            "count": len(decisions),
        }
    finally:
        db.close()


@app.get("/decisions/{decision_id}")
async def get_decision_detail(decision_id: str):
    """Get a single decision with full reasoning trace."""
    db = SessionLocal()
    try:
        decision = db.query(AgentDecision).filter(
            AgentDecision.id == decision_id
        ).first()
        if not decision:
            raise HTTPException(status_code=404, detail="Decision not found")
        return decision.to_dict()
    finally:
        db.close()


@app.get("/health")
async def health():
    return {
        "status": "healthy",
        "service": "reorder-agent-service",
        "groq_configured": bool(os.getenv("GROQ_API_KEY")),
        "model": os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile"),
    }


@app.get("/metrics")
async def metrics():
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)
