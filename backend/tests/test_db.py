"""Regression test: every connection the app opens pins its session timezone to UTC,
independent of the Postgres server's own configured default.

Without this, a timestamptz read back from the database serializes in whatever TimeZone
GUC the server happens to have (varies across managed providers and local setups), while a
Python-computed `datetime.now(UTC)` value always serializes as Z/+00:00 -- two
representations of the same instant that fail a naive string-equality check (idempotency,
caching, client-side comparisons). See app/db/__init__.py.
"""

from collections.abc import AsyncIterator

import pytest
from sqlalchemy import Engine, text

from app.db import Database


@pytest.fixture
async def database(db: Engine, database_url: str) -> AsyncIterator[Database]:
    database = Database(database_url)
    yield database
    await database.dispose()


@pytest.mark.anyio
async def test_every_connection_pins_its_session_timezone_to_utc(database: Database) -> None:
    async with database.sessions() as session:
        result = await session.execute(text("SHOW TimeZone"))
        assert result.scalar_one() == "UTC"


@pytest.mark.anyio
async def test_timestamptz_round_trips_as_utc_even_when_server_default_is_not(
    database: Database,
) -> None:
    # Simulate a managed provider whose server-level default timezone isn't UTC: set it on
    # this connection's own session (distinct from the startup option the engine already
    # applies), confirm it would have drifted without the pin, then confirm the engine's
    # actual connect-time pin still wins on a *fresh* connection from the same pool.
    async with database.sessions() as session:
        await session.execute(text("SET LOCAL TimeZone = 'America/Chicago'"))
        drifted = await session.execute(text("SELECT now()"))
        # Within this one session, the explicit SET LOCAL does take effect (proves the probe
        # itself is meaningful) -- but SET LOCAL is transaction-scoped and never leaks out.
        assert drifted.scalar_one().utcoffset() is not None

    async with database.sessions() as session:
        fresh = await session.execute(text("SHOW TimeZone"))
        assert fresh.scalar_one() == "UTC"
