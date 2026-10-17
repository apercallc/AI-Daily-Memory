"""Database access. Only the API and worker processes connect (ADR-0009)."""

from collections.abc import AsyncIterator

from fastapi import HTTPException, Request
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)


class Database:
    def __init__(self, url: str, *, pool_size: int = 5) -> None:
        self.engine: AsyncEngine = create_async_engine(
            url,
            pool_size=pool_size,
            pool_pre_ping=True,
            # Never log SQL: statements can carry transcript text.
            echo=False,
            # Pin every connection's session timezone to UTC regardless of the server's
            # configured default. Without this, a timestamptz read back from the database
            # serializes in whatever TimeZone GUC the Postgres instance happens to have
            # (varies across managed providers and local setups), while a Python-computed
            # `datetime.now(UTC)` value always serializes as Z/+00:00 -- two representations
            # of the same instant that fail a naive string-equality check (idempotency,
            # caching, client-side comparisons). `-c TimeZone=UTC` is a libpq startup option,
            # the correct pin point for the psycopg driver (ADR-0009).
            connect_args={"options": "-c TimeZone=UTC"},
        )
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)

    async def dispose(self) -> None:
        await self.engine.dispose()


async def get_session(request: Request) -> AsyncIterator[AsyncSession]:
    """One session per request; the handler commits. Anything uncommitted rolls back."""
    database: Database | None = getattr(request.app.state, "database", None)
    if database is None:
        raise HTTPException(status_code=503, detail="Database not configured.")
    async with database.sessions() as session:
        yield session
