"""Temporary-audio retention (ADR-0003, ADR-0014): no clip outlives its purpose in the bucket.

Every path that could leave audio behind ends here:

- a presigned upload the phone never completed (`pending` past its URL TTL plus a grace);
- a clip whose transcription job is dead (any cause, including repeated lease expiry);
- a clip held longer than the 7-day maximum without being transcribed.

Each is marked `failed` with a reason *after* its object is deleted, so a delete that fails
leaves the row for the next sweep instead of hiding audio that still exists. The bucket
lifecycle rule (docs/operations/OBJECT_STORAGE.md) is the last-resort backstop.
"""

import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Final

from sqlalchemy import ColumnElement, and_, delete, exists, func, or_, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.models import AudioUpload, ProcessingJob, SealedDelivery
from app.storage import ObjectStore

logger = logging.getLogger("app.retention")

PENDING_GRACE: Final = timedelta(hours=1)
MAX_HOLD: Final = timedelta(days=7)
# Finished upload rows say when the user spoke; keep them only as long as duplicate
# detection needs them, then remove even that metadata (ADR-0014).
FINISHED_METADATA: Final = timedelta(days=30)


@dataclass(frozen=True)
class SweepResult:
    abandoned: int
    dead: int
    expired: int
    errors: int
    deliveries_expired: int = 0
    metadata_purged: int = 0


async def sweep(
    sessions: async_sessionmaker[AsyncSession],
    store: ObjectStore,
    *,
    url_ttl_seconds: int,
    now: datetime | None = None,
    limit: int = 500,
) -> SweepResult:
    clock = now if now is not None else func.now()
    abandoned_before = clock - timedelta(seconds=url_ttl_seconds) - PENDING_GRACE
    dead_job = exists().where(
        ProcessingJob.subject_id == AudioUpload.id,
        ProcessingJob.kind == "transcribe",
        ProcessingJob.state == "dead",
    )
    candidates = (
        select(AudioUpload.id, AudioUpload.object_key, AudioUpload.state)
        .where(
            or_(
                and_(AudioUpload.state == "pending", AudioUpload.created_at < abandoned_before),
                and_(AudioUpload.state == "received", dead_job),
                and_(AudioUpload.state == "received", AudioUpload.received_at < clock - MAX_HOLD),
            ),
        )
        .limit(limit)
    )
    async with sessions() as session:
        rows = (await session.execute(candidates)).all()
        dead_ids = set(
            (
                await session.scalars(
                    select(AudioUpload.id).where(
                        AudioUpload.id.in_([r.id for r in rows]),
                        dead_job,
                    ),
                )
            ).all(),
        )

    counts = {"upload_abandoned": 0, "transcription_failed": 0, "retention_expired": 0}
    errors = 0
    for upload_id, key, state in rows:
        code = (
            "upload_abandoned"
            if state == "pending"
            else "transcription_failed"
            if upload_id in dead_ids
            else "retention_expired"
        )
        try:
            await store.delete(key)
        except Exception:
            # Left as is: the next sweep retries. Never mark audio gone that may still exist.
            logger.exception("retention_delete_failed", extra={"upload_id": str(upload_id)})
            errors += 1
            continue
        if await _mark_failed(sessions, upload_id, state, code):
            counts[code] += 1
    result = SweepResult(
        abandoned=counts["upload_abandoned"],
        dead=counts["transcription_failed"],
        expired=counts["retention_expired"],
        errors=errors,
        deliveries_expired=await _expire_deliveries(sessions, clock),
        metadata_purged=await _purge_finished(sessions, clock),
    )
    if rows or result.deliveries_expired:
        logger.info(
            "retention_sweep",
            extra={
                "abandoned": result.abandoned,
                "dead": result.dead,
                "expired": result.expired,
                "errors": result.errors,
                "deliveries_expired": result.deliveries_expired,
            },
        )
    return result


async def _mark_failed(
    sessions: async_sessionmaker[AsyncSession],
    upload_id: uuid.UUID,
    state: str,
    code: str,
) -> bool:
    async with sessions() as session:
        moved = await session.scalar(
            update(AudioUpload)
            .where(AudioUpload.id == upload_id, AudioUpload.state == state)
            .values(state="failed", failure_code=code)
            .returning(AudioUpload.id),
        )
        await session.commit()
    return moved is not None


async def _expire_deliveries(
    sessions: async_sessionmaker[AsyncSession],
    clock: datetime | ColumnElement[datetime],
) -> int:
    """Sealed transcripts the phone never collected within the hold are deleted, and their
    clips marked failed so the phone (which still has the audio) uploads them again."""
    async with sessions() as session:
        uploads = (
            await session.scalars(
                delete(SealedDelivery)
                .where(SealedDelivery.expires_at <= clock)
                .returning(SealedDelivery.upload_id),
            )
        ).all()
        if uploads:
            await session.execute(
                update(AudioUpload)
                .where(AudioUpload.id.in_(uploads), AudioUpload.state == "transcribed")
                .values(state="failed", failure_code="delivery_expired"),
            )
        await session.commit()
    return len(uploads)


async def _purge_finished(
    sessions: async_sessionmaker[AsyncSession],
    clock: datetime | ColumnElement[datetime],
) -> int:
    async with sessions() as session:
        purged = (
            await session.scalars(
                delete(AudioUpload)
                .where(
                    AudioUpload.state.in_(("delivered", "failed")),
                    AudioUpload.created_at < clock - FINISHED_METADATA,
                )
                .returning(AudioUpload.id),
            )
        ).all()
        await session.commit()
    return len(purged)
