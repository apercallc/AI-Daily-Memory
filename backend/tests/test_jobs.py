import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime, timedelta

import pytest
from sqlalchemy import Engine, text

from app.db import Database
from app.jobs import BACKOFF_CAP, backoff, claim, complete, enqueue, fail
from tests.conftest import make_account

pytestmark = pytest.mark.anyio

NOW = datetime(2026, 9, 25, 12, 0, tzinfo=UTC)


@pytest.fixture
async def database(db: Engine, database_url: str) -> AsyncIterator[Database]:
    database = Database(database_url)
    yield database
    await database.dispose()


async def queue_one(database: Database, user_id: uuid.UUID, *, max_attempts: int = 3) -> uuid.UUID:
    subject = uuid.uuid4()
    async with database.sessions() as session:
        await enqueue(
            session,
            user_id=user_id,
            kind="transcribe",
            subject_id=subject,
            idempotency_key=f"transcribe:{subject}",
            max_attempts=max_attempts,
        )
        await session.execute(text("UPDATE processing_jobs SET run_after = :t"), {"t": NOW})
        await session.commit()
    return subject


def job_row(db: Engine) -> tuple[str, int, str | None]:
    with db.connect() as connection:
        row = connection.execute(
            text("SELECT state, attempts, last_error_code FROM processing_jobs"),
        ).one()
    return row.state, row.attempts, row.last_error_code


async def test_enqueue_is_idempotent_by_key(database: Database, db: Engine) -> None:
    user = make_account(db).user_id
    subject = uuid.uuid4()
    async with database.sessions() as session:
        for _ in range(3):
            await enqueue(
                session,
                user_id=user,
                kind="transcribe",
                subject_id=subject,
                idempotency_key="transcribe:x",
            )
        await session.commit()

    with db.connect() as connection:
        assert connection.execute(text("SELECT count(*) FROM processing_jobs")).scalar() == 1


async def test_claim_complete_and_nothing_left(database: Database, db: Engine) -> None:
    subject = await queue_one(database, make_account(db).user_id)

    async with database.sessions() as session:
        job = await claim(session, kinds=("transcribe",), worker="w1", now=NOW)
        assert job is not None
        assert (job.subject_id, job.attempts) == (subject, 1)
        assert await claim(session, kinds=("transcribe",), worker="w2", now=NOW) is None
        assert await complete(session, job)

    assert job_row(db) == ("succeeded", 1, None)


async def test_jobs_wait_for_run_after_and_their_kind(database: Database, db: Engine) -> None:
    await queue_one(database, make_account(db).user_id)

    async with database.sessions() as session:
        early = NOW - timedelta(seconds=1)
        assert await claim(session, kinds=("transcribe",), worker="w", now=early) is None
        assert await claim(session, kinds=("extract",), worker="w", now=NOW) is None


async def test_retryable_failures_back_off_then_dead_letter(database: Database, db: Engine) -> None:
    await queue_one(database, make_account(db).user_id, max_attempts=2)

    async with database.sessions() as session:
        job = await claim(session, kinds=("transcribe",), worker="w", now=NOW)
        assert job is not None
        state = await fail(session, job, error_code="provider_timeout", retryable=True, now=NOW)
        assert state == "queued"
        assert job_row(db) == ("queued", 1, "provider_timeout")
        assert await claim(session, kinds=("transcribe",), worker="w", now=NOW) is None

        later = NOW + backoff(job.id, 1)
        again = await claim(session, kinds=("transcribe",), worker="w", now=later)
        assert again is not None
        state = await fail(session, again, error_code="provider_timeout", retryable=True, now=later)

    assert state == "dead"
    assert job_row(db) == ("dead", 2, "provider_timeout")


async def test_permanent_failures_dead_letter_immediately(database: Database, db: Engine) -> None:
    await queue_one(database, make_account(db).user_id)

    async with database.sessions() as session:
        job = await claim(session, kinds=("transcribe",), worker="w", now=NOW)
        assert job is not None
        await fail(session, job, error_code="invalid_provider_result", retryable=False, now=NOW)

    assert job_row(db) == ("dead", 1, "invalid_provider_result")


async def test_an_expired_lease_is_reclaimed_and_the_old_owner_is_fenced(
    database: Database,
    db: Engine,
) -> None:
    await queue_one(database, make_account(db).user_id)

    async with database.sessions() as session:
        stale = await claim(session, kinds=("transcribe",), worker="w1", now=NOW)
        assert stale is not None
        later = NOW + timedelta(hours=1)
        fresh = await claim(session, kinds=("transcribe",), worker="w2", now=later)
        assert fresh is not None
        assert fresh.id == stale.id

        assert not await complete(session, stale)
        lost = await fail(session, stale, error_code="x", retryable=True, now=later)
        assert lost == "lost"
        assert await complete(session, fresh)

    assert job_row(db) == ("succeeded", 2, None)


async def test_a_job_that_keeps_killing_workers_is_dead_lettered(
    database: Database,
    db: Engine,
) -> None:
    await queue_one(database, make_account(db).user_id, max_attempts=1)

    async with database.sessions() as session:
        assert await claim(session, kinds=("transcribe",), worker="w1", now=NOW) is not None
        later = NOW + timedelta(hours=1)
        assert await claim(session, kinds=("transcribe",), worker="w2", now=later) is None

    assert job_row(db) == ("dead", 2, "lease_expired_repeatedly")


async def test_deleting_a_user_deletes_their_jobs(database: Database, db: Engine) -> None:
    account = make_account(db)
    await queue_one(database, account.user_id)

    with db.begin() as connection:
        connection.execute(text("DELETE FROM users WHERE id = :id"), {"id": account.user_id})
        assert connection.execute(text("SELECT count(*) FROM processing_jobs")).scalar() == 0


def test_backoff_grows_is_capped_and_is_jittered_deterministically() -> None:
    job = uuid.UUID(int=12345)

    first, second, huge = backoff(job, 1), backoff(job, 2), backoff(job, 40)

    assert timedelta(seconds=30) <= first <= timedelta(seconds=37.5)
    assert second == first * 2
    assert huge <= BACKOFF_CAP * 1.25
    assert backoff(job, 1) == first
