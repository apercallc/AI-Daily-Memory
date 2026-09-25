"""PostgreSQL job queue (TODO Phase 1 "Processing jobs").

- Enqueue is idempotent by key: the same stage for the same subject is one job, ever.
- Claim uses `FOR UPDATE SKIP LOCKED` and a lease; a worker that dies leaves a job whose lease
  expires and is claimed again.
- Completion and failure are fenced by the lease owner *and* the attempt number, so a worker
  that lost its lease (or a restarted process reusing the same id) can't overwrite the new
  owner's outcome.
- Time comes from the database clock by default, so lease expiry and backoff don't depend on
  each worker's clock. Tests pass an explicit `now`.
- Retries back off exponentially with a cap; after `max_attempts` a job is `dead` with an
  operator-visible error code. Nothing retries forever and nothing fails silently.
"""

import logging
import uuid
import zlib
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import ColumnElement, Result, and_, func, or_, select, update
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import ProcessingJob

logger = logging.getLogger("app.jobs")

BACKOFF_BASE: Final = timedelta(seconds=30)
BACKOFF_CAP: Final = timedelta(hours=1)
DEFAULT_LEASE: Final = timedelta(minutes=10)

Clock = datetime | ColumnElement[datetime]


@dataclass(frozen=True)
class ClaimedJob:
    id: uuid.UUID
    user_id: uuid.UUID
    kind: str
    subject_id: uuid.UUID
    attempts: int
    max_attempts: int
    lease_owner: str


def backoff(job_id: uuid.UUID, attempt: int) -> timedelta:
    """Exponential backoff with deterministic jitter (0-25%, derived from the job id): jobs
    that failed together don't retry in lockstep, and tests stay reproducible. The cap
    applies after jitter."""
    base = min(BACKOFF_BASE * (1 << min(max(attempt - 1, 0), 16)), BACKOFF_CAP)
    jitter = (zlib.crc32(job_id.bytes) % 1000) / 4000
    return min(base + base * jitter, BACKOFF_CAP)


def _clock(now: datetime | None) -> Clock:
    return now if now is not None else func.now()


async def enqueue(
    session: AsyncSession,
    *,
    user_id: uuid.UUID,
    kind: str,
    subject_id: uuid.UUID,
    idempotency_key: str,
    max_attempts: int = 5,
    run_after: Clock | None = None,
) -> None:
    """Creates the job unless one with this key exists. The caller commits. [run_after] delays
    the first claim (e.g. extraction waits a little for sibling clips of the same session to
    finish transcribing); omitted, the row's own server default (now) applies."""
    values: dict[str, object] = {
        "id": uuid.uuid4(),
        "user_id": user_id,
        "kind": kind,
        "subject_id": subject_id,
        "idempotency_key": idempotency_key,
        "max_attempts": max_attempts,
    }
    if run_after is not None:
        values["run_after"] = run_after
    await session.execute(
        insert(ProcessingJob)
        .values(**values)
        .on_conflict_do_nothing(index_elements=["idempotency_key"]),
    )


async def claim(
    session: AsyncSession,
    *,
    kinds: tuple[str, ...],
    worker: str,
    now: datetime | None = None,
    lease: timedelta = DEFAULT_LEASE,
) -> ClaimedJob | None:
    """Claims the oldest runnable job of [kinds] and commits the claim."""
    clock = _clock(now)
    runnable = or_(
        and_(ProcessingJob.state == "queued", ProcessingJob.run_after <= clock),
        and_(ProcessingJob.state == "running", ProcessingJob.lease_expires_at < clock),
    )
    candidate = (
        select(ProcessingJob.id)
        .where(ProcessingJob.kind.in_(kinds), runnable)
        .order_by(ProcessingJob.run_after)
        .limit(1)
        .with_for_update(skip_locked=True)
        .scalar_subquery()
    )
    row = (
        await session.execute(
            update(ProcessingJob)
            .where(ProcessingJob.id == candidate)
            .values(
                state="running",
                lease_owner=worker,
                lease_expires_at=clock + lease,
                attempts=ProcessingJob.attempts + 1,
            )
            .returning(
                ProcessingJob.id,
                ProcessingJob.user_id,
                ProcessingJob.kind,
                ProcessingJob.subject_id,
                ProcessingJob.attempts,
                ProcessingJob.max_attempts,
            ),
        )
    ).one_or_none()
    if row is None:
        await session.commit()
        return None
    job = ClaimedJob(*row, lease_owner=worker)
    if job.attempts > job.max_attempts:
        # Only reachable through expired leases: the worker keeps dying on this job.
        await _dead(session, job, "lease_expired_repeatedly")
        await session.commit()
        return None
    await session.commit()
    return job


def _owned(job: ClaimedJob) -> list[ColumnElement[bool]]:
    return [
        ProcessingJob.id == job.id,
        ProcessingJob.state == "running",
        ProcessingJob.lease_owner == job.lease_owner,
        ProcessingJob.attempts == job.attempts,
    ]


async def complete(session: AsyncSession, job: ClaimedJob) -> bool:
    """Marks the job done. False if the lease was lost (someone else owns it now)."""
    result = await session.execute(
        update(ProcessingJob)
        .where(*_owned(job))
        .values(state="succeeded", lease_owner=None, lease_expires_at=None, last_error_code=None),
    )
    await session.commit()
    return bool(getattr(result, "rowcount", 0))


async def fail(
    session: AsyncSession,
    job: ClaimedJob,
    *,
    error_code: str,
    retryable: bool,
    now: datetime | None = None,
) -> str:
    """Schedules a retry or dead-letters the job. Returns the new state ("lost" if fenced)."""
    if retryable and job.attempts < job.max_attempts:
        result = await session.execute(
            update(ProcessingJob)
            .where(*_owned(job))
            .values(
                state="queued",
                run_after=_clock(now) + backoff(job.id, job.attempts),
                lease_owner=None,
                lease_expires_at=None,
                last_error_code=error_code,
            ),
        )
        state = "queued"
    else:
        result = await _dead(session, job, error_code)
        state = "dead"
    await session.commit()
    return state if getattr(result, "rowcount", 0) else "lost"


async def _dead(
    session: AsyncSession,
    job: ClaimedJob,
    error_code: str,
) -> Result[tuple[()]]:
    logger.error(
        "job_dead_lettered",
        extra={"job_id": str(job.id), "kind": job.kind, "error_code": error_code},
    )
    return await session.execute(
        update(ProcessingJob)
        .where(*_owned(job))
        .values(state="dead", lease_owner=None, lease_expires_at=None, last_error_code=error_code),
    )
