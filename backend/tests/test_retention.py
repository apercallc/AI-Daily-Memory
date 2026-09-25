import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import Engine, text

from app.db import Database
from app.retention import MAX_HOLD, PENDING_GRACE, SweepResult, sweep
from tests.conftest import FakeObjectStore, make_account

pytestmark = pytest.mark.anyio

NOW = datetime(2026, 9, 25, 12, 0, tzinfo=UTC)
TTL = 900


@pytest.fixture
async def database(db: Engine, database_url: str) -> AsyncIterator[Database]:
    database = Database(database_url)
    yield database
    await database.dispose()


def upload(
    db: Engine,
    store: FakeObjectStore,
    *,
    state: str,
    created_at: datetime,
    received_at: datetime | None = None,
    job_state: str | None = None,
) -> tuple[uuid.UUID, str]:
    user = make_account(db).user_id
    upload_id = uuid.uuid4()
    key = f"users/{user}/audio/{upload_id}.aac"
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO audio_uploads (id, user_id, session_id, segment_id, offset_ms, "
                "duration_ms, byte_size, sha256, codec, sample_rate_hz, channel_count, "
                "captured_at, state, object_key, created_at, received_at) VALUES (:id, :user, "
                ":s, :seg, 0, 3000, 4, :sha, 'aac-lc-adts', 16000, 1, :at, :state, :key, "
                ":created, :received)",
            ),
            {
                "id": upload_id,
                "user": user,
                "s": uuid.uuid4(),
                "seg": uuid.uuid4(),
                "sha": "0" * 64,
                "at": created_at,
                "state": state,
                "key": key,
                "created": created_at,
                "received": received_at,
            },
        )
        if job_state:
            connection.execute(
                text(
                    "INSERT INTO processing_jobs (id, user_id, kind, idempotency_key, "
                    "subject_id, state) VALUES (:id, :user, 'transcribe', :key, :sub, :state)",
                ),
                {
                    "id": uuid.uuid4(),
                    "user": user,
                    "key": f"transcribe:{upload_id}",
                    "sub": upload_id,
                    "state": job_state,
                },
            )
    store.put(key, b"clip")
    return upload_id, key


def row(db: Engine, upload_id: uuid.UUID) -> tuple[str, str | None]:
    with db.connect() as connection:
        found = connection.execute(
            text("SELECT state, failure_code FROM audio_uploads WHERE id = :id"),
            {"id": upload_id},
        ).one()
    return found.state, found.failure_code


async def test_every_lingering_clip_is_deleted_then_marked_with_why(
    database: Database,
    db: Engine,
) -> None:
    store = FakeObjectStore()
    stale = NOW - timedelta(seconds=TTL) - PENDING_GRACE - timedelta(minutes=1)
    abandoned, _ = upload(db, store, state="pending", created_at=stale)
    dead, _ = upload(db, store, state="received", created_at=NOW, received_at=NOW, job_state="dead")
    old = NOW - MAX_HOLD - timedelta(minutes=1)
    expired, _ = upload(db, store, state="received", created_at=old, received_at=old)
    fresh_pending, _ = upload(db, store, state="pending", created_at=NOW)
    working, _ = upload(
        db,
        store,
        state="received",
        created_at=NOW,
        received_at=NOW,
        job_state="queued",
    )

    result = await sweep(database.sessions, store, url_ttl_seconds=TTL, now=NOW)

    assert result == SweepResult(abandoned=1, dead=1, expired=1, errors=0)
    assert row(db, abandoned) == ("failed", "upload_abandoned")
    assert row(db, dead) == ("failed", "transcription_failed")
    assert row(db, expired) == ("failed", "retention_expired")
    assert row(db, fresh_pending) == ("pending", None)
    assert row(db, working) == ("received", None)
    assert len(store.deleted) == 3
    assert len(store.objects) == 2


async def test_a_failed_delete_leaves_the_row_for_the_next_sweep(
    database: Database,
    db: Engine,
) -> None:
    class DownStore(FakeObjectStore):
        async def delete(self, key: str) -> None:
            raise RuntimeError("store unavailable")

    store = DownStore()
    old = NOW - MAX_HOLD - timedelta(minutes=1)
    expired, _ = upload(db, store, state="received", created_at=old, received_at=old)

    result = await sweep(database.sessions, store, url_ttl_seconds=TTL, now=NOW)

    assert result.errors == 1
    assert row(db, expired) == ("received", None)


async def test_the_database_clock_is_the_default(database: Database, db: Engine) -> None:
    store = FakeObjectStore()
    long_ago = datetime.now(UTC) - timedelta(days=10)
    abandoned, _ = upload(db, store, state="pending", created_at=long_ago)

    await sweep(database.sessions, store, url_ttl_seconds=TTL)

    assert row(db, abandoned) == ("failed", "upload_abandoned")


async def test_nothing_to_sweep_is_quiet(database: Database) -> None:
    result = await sweep(database.sessions, FakeObjectStore(), url_ttl_seconds=TTL, now=NOW)

    assert result == SweepResult(0, 0, 0, 0)
