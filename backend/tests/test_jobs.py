import asyncio
import uuid
from collections.abc import AsyncIterator, Sequence
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


def test_backoff_grows_is_capped_after_jitter_and_is_deterministic() -> None:
    job = uuid.UUID(int=12345)

    first, second, huge = backoff(job, 1), backoff(job, 2), backoff(job, 40)

    assert timedelta(seconds=30) <= first <= timedelta(seconds=37.5)
    assert second == first * 2
    assert huge == BACKOFF_CAP
    assert backoff(job, 1) == first


async def test_skip_locked_hands_concurrent_workers_different_jobs(
    database: Database,
    db: Engine,
) -> None:
    user = make_account(db).user_id
    first = await queue_one(database, user)
    await queue_one(database, user)
    with db.connect() as holder:
        # Another worker is mid-claim on the oldest job: its row is locked.
        holder.execute(text("BEGIN"))
        locked: uuid.UUID = holder.execute(
            text("SELECT id FROM processing_jobs WHERE subject_id = :s FOR UPDATE"),
            {"s": first},
        ).scalar_one()

        async with database.sessions() as session:
            job = await claim(session, kinds=("transcribe",), worker="w2", now=NOW)

        holder.execute(text("ROLLBACK"))
    assert job is not None
    assert job.id != locked


async def test_a_restarted_worker_with_the_same_id_is_still_fenced(
    database: Database,
    db: Engine,
) -> None:
    await queue_one(database, make_account(db).user_id)

    async with database.sessions() as session:
        stale = await claim(session, kinds=("transcribe",), worker="host:1", now=NOW)
        assert stale is not None
        fresh = await claim(
            session,
            kinds=("transcribe",),
            worker="host:1",
            now=NOW + timedelta(hours=1),
        )
        assert fresh is not None

        assert not await complete(session, stale)
        assert await complete(session, fresh)


async def test_the_database_clock_is_used_when_no_time_is_given(
    database: Database,
    db: Engine,
) -> None:
    user = make_account(db).user_id
    async with database.sessions() as session:
        await enqueue(
            session,
            user_id=user,
            kind="transcribe",
            subject_id=uuid.uuid4(),
            idempotency_key="k",
        )
        await session.commit()
        job = await claim(session, kinds=("transcribe",), worker="w")
        assert job is not None
        await fail(session, job, error_code="x", retryable=True)

    with db.connect() as connection:
        wait: timedelta = connection.execute(
            text("SELECT run_after - now() FROM processing_jobs"),
        ).scalar_one()
    assert timedelta(seconds=25) < wait <= backoff(job.id, 1)


async def test_run_after_delays_the_first_claim(database: Database, db: Engine) -> None:
    user = make_account(db).user_id
    async with database.sessions() as session:
        await enqueue(
            session,
            user_id=user,
            kind="extract",
            subject_id=uuid.uuid4(),
            idempotency_key="delayed",
            run_after=NOW + timedelta(minutes=5),
        )
        await session.commit()

        assert await claim(session, kinds=("extract",), worker="w", now=NOW) is None
        later = await claim(session, kinds=("extract",), worker="w", now=NOW + timedelta(minutes=6))

    assert later is not None
    assert later.kind == "extract"


async def test_concurrent_workers_never_double_claim_or_lose_a_job(
    database: Database,
    db: Engine,
) -> None:
    """Load/chaos test (TODO.md Phase 8 "chaos/fault tests for worker retry behavior"): many
    real concurrent workers racing against a shared pool of jobs, not a single simulated lock
    holder. Every job must be claimed by exactly one worker, and every job must be claimed —
    `FOR UPDATE SKIP LOCKED` must never let two workers walk away with the same row, and must
    never let contention starve a job out entirely."""
    user = make_account(db).user_id
    job_count = 12
    worker_count = 6
    subjects = [await queue_one(database, user) for _ in range(job_count)]

    async def worker(name: str) -> list[uuid.UUID]:
        claimed: list[uuid.UUID] = []
        async with database.sessions() as session:
            while True:
                job = await claim(session, kinds=("transcribe",), worker=name, now=NOW)
                if job is None:
                    return claimed
                claimed.append(job.subject_id)
                assert await complete(session, job)

    results = await asyncio.gather(*(worker(f"w{i}") for i in range(worker_count)))
    all_claimed = [subject for worker_claims in results for subject in worker_claims]

    # Every job claimed exactly once across every worker: no duplicate, none dropped.
    assert sorted(all_claimed) == sorted(subjects)
    with db.connect() as connection:
        states: Sequence[str] = (
            connection.execute(text("SELECT state FROM processing_jobs")).scalars().all()
        )
    assert all(state == "succeeded" for state in states)
    assert len(states) == job_count
