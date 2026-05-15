"""
SQLAlchemy models for agent decision logs.
Stores full reasoning traces as JSONB for transparency and demo-ability.
"""
import os
import uuid
from datetime import datetime

from sqlalchemy import (
    Column, String, Integer, Float, DateTime, JSON, Text,
    create_engine, Enum as SAEnum
)
from sqlalchemy.dialects.postgresql import UUID as PGUUID, JSONB
from sqlalchemy.orm import declarative_base, sessionmaker
import enum

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://app_user:changeme_postgres_2024@localhost:5432/inventory_db"
)

engine = create_engine(DATABASE_URL, pool_size=10, max_overflow=20, pool_pre_ping=True)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()


class DecisionStatus(str, enum.Enum):
    PENDING = "PENDING"
    REASONING = "REASONING"
    EXECUTED = "EXECUTED"
    FAILED = "FAILED"
    NO_ACTION = "NO_ACTION"


class AgentDecision(Base):
    """
    Stores every agent run with its full reasoning trace.
    The reasoning_trace column is JSONB containing the entire ReAct loop:
    each step's thought, tool call, tool result, and the final decision.
    """
    __tablename__ = "agent_decisions"

    id = Column(PGUUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    correlation_id = Column(String(64), nullable=False, index=True)
    product_id = Column(String(64), nullable=True, index=True)
    status = Column(SAEnum(DecisionStatus), nullable=False, default=DecisionStatus.PENDING)

    # The full ReAct reasoning trace — the demo showpiece
    reasoning_trace = Column(JSONB, nullable=False, default=list)

    # Final decision summary
    final_decision = Column(JSONB, nullable=True)
    products_analyzed = Column(Integer, default=0)
    reorders_executed = Column(Integer, default=0)

    # LLM usage tracking
    total_llm_calls = Column(Integer, default=0)
    total_tokens_used = Column(Integer, default=0)
    total_latency_seconds = Column(Float, default=0.0)

    # Timestamps
    started_at = Column(DateTime, nullable=False, default=datetime.utcnow)
    completed_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, nullable=False, default=datetime.utcnow)

    def to_dict(self):
        return {
            "id": str(self.id),
            "correlation_id": self.correlation_id,
            "product_id": self.product_id,
            "status": self.status.value if self.status else None,
            "reasoning_trace": self.reasoning_trace,
            "final_decision": self.final_decision,
            "products_analyzed": self.products_analyzed,
            "reorders_executed": self.reorders_executed,
            "total_llm_calls": self.total_llm_calls,
            "total_tokens_used": self.total_tokens_used,
            "total_latency_seconds": round(self.total_latency_seconds, 3),
            "started_at": self.started_at.isoformat() if self.started_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
        }


def init_db():
    """Create all tables."""
    Base.metadata.create_all(bind=engine)


def get_db():
    """Yield a database session."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
