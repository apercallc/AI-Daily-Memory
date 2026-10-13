import logging
from typing import Literal

from fastapi import APIRouter, Request, Response
from pydantic import BaseModel
from sqlalchemy import text
from sqlalchemy.exc import SQLAlchemyError

from app.db import Database

logger = logging.getLogger("app.health")

router = APIRouter(tags=["health"])


class HealthResponse(BaseModel):
    status: Literal["ok"]
    service: str


class ReadyResponse(BaseModel):
    status: Literal["ok", "unavailable"]
    database: Literal["ok", "unavailable", "not_configured"]


@router.get("/health", response_model=HealthResponse)
async def health() -> HealthResponse:
    """Liveness: the process is up. Says nothing about its dependencies; see /ready."""
    return HealthResponse(status="ok", service="ai-daily-memory")


@router.get("/ready", response_model=ReadyResponse)
async def ready(request: Request, response: Response) -> ReadyResponse:
    """Readiness: can this instance serve a request that needs the database?"""
    database: Database | None = getattr(request.app.state, "database", None)
    if database is None:
        response.status_code = 503
        return ReadyResponse(status="unavailable", database="not_configured")
    try:
        async with database.sessions() as session:
            await session.execute(text("SELECT 1"))
    except SQLAlchemyError as error:
        logger.error("readiness_database_failed", extra={"error_type": type(error).__name__})
        response.status_code = 503
        return ReadyResponse(status="unavailable", database="unavailable")
    return ReadyResponse(status="ok", database="ok")
