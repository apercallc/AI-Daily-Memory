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
