"""Regression tests for TOCTOU races on quota enforcement.

`POST /v1/api-keys` (app/api/tokens.py `create_key`) and `POST /v1/folders`
(app/api/notes.py `create_folder`) each enforce a per-account cap with a count-then-insert
check. Without a lock, two concurrent requests from the same account can both pass the count
check before either commits, exceeding the cap. Modeled on test_jobs.py's
`test_concurrent_workers_never_double_claim_or_lose_a_job`: real concurrent coroutines, each
with its own session, racing against a shared Postgres instance -- not a single simulated
lock holder.
"""

import asyncio
import uuid
from collections.abc import AsyncIterator
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi import Request
from fastapi.responses import JSONResponse
from sqlalchemy import Engine, text

import app.api.notes as notes_api
import app.api.tokens as tokens_api
from app.api.notes import FolderIn, FolderOut, create_folder
from app.api.tokens import CreatedApiKey, NewApiKey, create_key
from app.auth import Principal
from app.db import Database
from tests.conftest import make_account

pytestmark = pytest.mark.anyio


@pytest.fixture
async def database(db: Engine, database_url: str) -> AsyncIterator[Database]:
    # A larger pool than the default so every concurrent attempt gets its own connection
    # instead of queuing behind the pool, which would hide the race instead of exercising it.
    database = Database(database_url, pool_size=20)
    yield database
    await database.dispose()


def _request() -> Request:
    settings = SimpleNamespace(login_source_key=None, database_url=None)
    app = SimpleNamespace(state=SimpleNamespace(settings=settings))
    return cast(Request, SimpleNamespace(app=app))


def _principal(user_id: uuid.UUID) -> Principal:
    return Principal(user_id=user_id, token_id=uuid.uuid4(), scopes=frozenset())


async def test_concurrent_api_key_creation_never_exceeds_the_cap(
    database: Database, db: Engine, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(tokens_api, "MAX_API_KEYS", 4)
    account = make_account(db)
    manager = _principal(account.user_id)
    attempts = tokens_api.MAX_API_KEYS + 5

    async def attempt(n: int) -> bool:
        async with database.sessions() as session:
            result = await create_key(NewApiKey(name=f"key-{n}"), _request(), session, manager)
            return isinstance(result, CreatedApiKey)

    results = await asyncio.gather(*(attempt(n) for n in range(attempts)))

    assert sum(results) == tokens_api.MAX_API_KEYS
    assert attempts - sum(results) == 5  # the rest saw the cap, not a silent drop
    with db.connect() as connection:
        count = connection.scalar(
            text(
                "SELECT count(*) FROM access_tokens "
                "WHERE user_id = :u AND kind = 'api' AND revoked_at IS NULL"
            ),
            {"u": account.user_id},
        )
    assert count == tokens_api.MAX_API_KEYS


async def test_a_losing_api_key_attempt_reports_the_quota_error(
    database: Database, db: Engine, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(tokens_api, "MAX_API_KEYS", 1)
    account = make_account(db)
    manager = _principal(account.user_id)

    async def attempt(n: int) -> CreatedApiKey | JSONResponse:
        async with database.sessions() as session:
            return await create_key(NewApiKey(name=f"key-{n}"), _request(), session, manager)

    first, second = await asyncio.gather(attempt(0), attempt(1))
    losers = [r for r in (first, second) if isinstance(r, JSONResponse)]

    assert len(losers) == 1
    assert losers[0].status_code == 409


async def test_concurrent_folder_creation_never_exceeds_the_cap(
    database: Database, db: Engine, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(notes_api, "MAX_FOLDERS", 4)
    account = make_account(db)
    writer = _principal(account.user_id)
    attempts = notes_api.MAX_FOLDERS + 5

    async def attempt(n: int) -> bool:
        async with database.sessions() as session:
            result = await create_folder(FolderIn(name=f"Folder {n}"), session, writer)
            return isinstance(result, FolderOut)

    results = await asyncio.gather(*(attempt(n) for n in range(attempts)))

    assert sum(results) == notes_api.MAX_FOLDERS
    assert attempts - sum(results) == 5
    with db.connect() as connection:
        count = connection.scalar(
            text("SELECT count(*) FROM note_folders WHERE user_id = :u"), {"u": account.user_id}
        )
    assert count == notes_api.MAX_FOLDERS
