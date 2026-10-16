import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import Engine, text

from app.db import Database
from app.retention import MAX_ACCOUNT_PURGE_ATTEMPTS, MAX_HOLD, PENDING_GRACE, SweepResult, sweep
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


async def test_a_row_resolved_mid_sweep_keeps_that_outcome(database: Database, db: Engine) -> None:
    """A candidate whose state changes between selection and marking (here: a concurrent
    complete) keeps the other writer's outcome — the mark is fenced on the state the
    candidate query saw, and the sweep must not count it as its own."""

    class CompletedMidDelete(FakeObjectStore):
        def __init__(self) -> None:
            super().__init__()
            self.key: str | None = None

        async def delete(self, key: str) -> None:
            if key == self.key:
                with db.connect() as connection:
                    connection.execute(
                        text("UPDATE audio_uploads SET state = 'received' WHERE id = :id"),
                        {"id": upload_id},
                    )
                    connection.commit()
            await super().delete(key)

    store = CompletedMidDelete()
    stale = NOW - timedelta(seconds=TTL) - PENDING_GRACE - timedelta(minutes=1)
    upload_id, key = upload(db, store, state="pending", created_at=stale)
    store.key = key

    result = await sweep(database.sessions, store, url_ttl_seconds=TTL, now=NOW)

    assert result.abandoned == 0
    assert result.errors == 0
    assert row(db, upload_id) == ("received", None)


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


async def test_failed_delayed_prefix_purge_is_retained_for_retry(
    database: Database,
    db: Engine,
) -> None:
    account_id = uuid.uuid4()
    key = f"users/{account_id}/audio/late-put.aac"
    store = FakeObjectStore()
    store.put(key, b"late upload")
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO pending_account_object_deletions (account_id, purge_after) "
                "VALUES (:account_id, :purge_after)"
            ),
            {"account_id": account_id, "purge_after": NOW - timedelta(seconds=1)},
        )

    class DownPrefixStore(FakeObjectStore):
        async def delete_prefix(self, prefix: str) -> int:
            raise RuntimeError("store unavailable")

    failing_store = DownPrefixStore(objects=store.objects.copy())
    result = await sweep(
        database.sessions,
        failing_store,
        url_ttl_seconds=TTL,
        now=NOW,
    )

    assert result.errors == 1
    assert result.account_prefixes_purged == 0
    assert key in failing_store.objects
    with db.connect() as connection:
        assert (
            connection.scalar(
                text("SELECT count(*) FROM pending_account_object_deletions"),
            )
            == 1
        )


async def test_a_prefix_purge_is_dead_lettered_after_the_retry_budget(
    database: Database,
    db: Engine,
) -> None:
    """A permanent storage failure must surface to an operator, not retry forever."""
    account_id = uuid.uuid4()
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO pending_account_object_deletions "
                "(account_id, purge_after, attempts) "
                "VALUES (:account_id, :purge_after, :attempts)"
            ),
            {
                "account_id": account_id,
                "purge_after": NOW - timedelta(seconds=1),
                "attempts": MAX_ACCOUNT_PURGE_ATTEMPTS - 1,
            },
        )

    class DownPrefixStore(FakeObjectStore):
        async def delete_prefix(self, prefix: str) -> int:
            raise RuntimeError("store unavailable")

    result = await sweep(database.sessions, DownPrefixStore(), url_ttl_seconds=TTL, now=NOW)

    assert result.account_prefixes_dead_lettered == 1
    with db.connect() as connection:
        row = connection.execute(
            text(
                "SELECT attempts, last_error_code, dead_at IS NOT NULL AS dead "
                "FROM pending_account_object_deletions WHERE account_id = :account_id"
            ),
            {"account_id": account_id},
        ).one()
    assert row.attempts == MAX_ACCOUNT_PURGE_ATTEMPTS
    assert row.last_error_code == "RuntimeError"
    assert row.dead

    # A dead-lettered row is never picked up again on its own.
    again = await sweep(database.sessions, DownPrefixStore(), url_ttl_seconds=TTL, now=NOW)
    assert again.errors == 0
    assert again.account_prefixes_dead_lettered == 0


async def test_nothing_to_sweep_is_quiet(database: Database) -> None:
    result = await sweep(database.sessions, FakeObjectStore(), url_ttl_seconds=TTL, now=NOW)

    assert result == SweepResult(0, 0, 0, 0)


async def test_finished_metadata_and_expired_deliveries_are_purged_in_batches(
    database: Database, db: Engine
) -> None:
    store = FakeObjectStore()
    old = NOW - timedelta(days=31)
    ids = [upload(db, store, state="delivered", created_at=old)[0] for _ in range(5)]
    assert len(ids) == 5

    result = await sweep(database.sessions, store, url_ttl_seconds=TTL, now=NOW, limit=2)

    assert result.metadata_purged == 5  # three batches of at most 2
    with db.connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM audio_uploads")) == 0


async def test_expired_deliveries_drain_in_batches_and_fail_their_clips(
    database: Database, db: Engine
) -> None:
    store = FakeObjectStore()
    for _ in range(3):
        upload_id, _ = upload(db, store, state="transcribed", created_at=NOW - timedelta(days=8))
        with db.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO device_keys (id, user_id, token_id, public_key) SELECT "
                    "gen_random_uuid(), u.user_id, t.id, :k FROM audio_uploads u JOIN "
                    "access_tokens t ON t.user_id = u.user_id WHERE u.id = :up"
                ),
                {"k": b"k" * 32, "up": upload_id},
            )
            connection.execute(
                text(
                    "INSERT INTO sealed_deliveries (id, user_id, upload_id, device_key_id, "
                    "ciphertext, expires_at) SELECT gen_random_uuid(), u.user_id, u.id, "
                    "k.id, :ct, :exp FROM audio_uploads u JOIN device_keys k "
                    "ON k.user_id = u.user_id WHERE u.id = :up"
                ),
                {"ct": b"c", "exp": NOW - timedelta(days=1), "up": upload_id},
            )

    result = await sweep(database.sessions, store, url_ttl_seconds=TTL, now=NOW, limit=2)

    assert result.deliveries_expired == 3
    with db.connect() as connection:
        assert connection.scalar(text("SELECT count(*) FROM sealed_deliveries")) == 0
        failed = connection.scalar(
            text("SELECT count(*) FROM audio_uploads WHERE failure_code = 'delivery_expired'")
        )
    assert failed == 3
