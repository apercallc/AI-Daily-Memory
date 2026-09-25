"""Upload protocol (TODO Phase 1): the phone asks for an upload instruction, PUTs the clip
straight to object storage, then completes the upload. Every step is idempotent by the
client's upload id (ADR-0006), so any step can be retried after a network failure.

    POST /v1/uploads                   -> instruction (or current state if already received)
    PUT  <presigned url>               -> object storage verifies size + SHA-256
    POST /v1/uploads/{id}/complete     -> verified, recorded, transcription queued
    GET  /v1/uploads/{id}              -> current state
"""

import logging
import uuid
from datetime import UTC, datetime, timedelta
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import AwareDatetime, BaseModel, ConfigDict, Field
from sqlalchemy import func, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.config import Settings
from app.db import get_session
from app.db.models import AudioUpload
from app.errors import ErrorResponse, error_response
from app.jobs import enqueue
from app.storage import ObjectStore, audio_key

logger = logging.getLogger("app.uploads")
router = APIRouter(tags=["uploads"])

TRANSCRIBE_JOB = "transcribe"
UploadState = Literal["pending", "received", "transcribed", "failed"]
_ERRORS: dict[int | str, dict[str, object]] = {
    status: {"model": ErrorResponse} for status in (401, 403, 404, 409, 413, 429, 503)
}


class UploadCreate(BaseModel):
    model_config = ConfigDict(extra="forbid")

    upload_id: uuid.UUID
    session_id: uuid.UUID
    segment_id: uuid.UUID
    # Where the clip starts inside its segment, so transcript times map back (ADR-0015).
    offset_ms: int = Field(ge=0, le=86_400_000)
    duration_ms: int = Field(gt=0)
    byte_size: int = Field(gt=0)
    sha256: str = Field(pattern=r"^[0-9a-f]{64}$")
    codec: Literal["aac-lc-adts"]
    sample_rate_hz: Literal[16_000, 22_050, 24_000, 32_000, 44_100, 48_000]
    channel_count: int = Field(ge=1, le=2)
    captured_at: AwareDatetime


class UploadTarget(BaseModel):
    method: Literal["PUT"] = "PUT"
    url: str
    headers: dict[str, str]
    expires_at: datetime


class UploadStatus(BaseModel):
    upload_id: uuid.UUID
    state: UploadState
    # Present only while the client still has to upload the bytes.
    upload: UploadTarget | None = None


def _settings(request: Request) -> Settings:
    settings: Settings = request.app.state.settings
    return settings


def _store(request: Request) -> ObjectStore:
    store: ObjectStore | None = getattr(request.app.state, "object_store", None)
    if store is None:
        raise HTTPException(status_code=503, detail="Object storage not configured.")
    return store


Session = Annotated[AsyncSession, Depends(get_session)]
Uploader = Annotated[Principal, Depends(require("upload:write"))]


def _same(row: AudioUpload, body: UploadCreate) -> bool:
    return (
        row.session_id == body.session_id
        and row.segment_id == body.segment_id
        and row.offset_ms == body.offset_ms
        and row.duration_ms == body.duration_ms
        and row.byte_size == body.byte_size
        and row.sha256 == body.sha256
        and row.codec == body.codec
        and row.sample_rate_hz == body.sample_rate_hz
        and row.channel_count == body.channel_count
    )


def _status(row: AudioUpload, store: ObjectStore, settings: Settings) -> UploadStatus:
    target = None
    if row.state == "pending":
        signed = store.presign_put(
            row.object_key,
            byte_size=row.byte_size,
            sha256=row.sha256,
            expires_in=settings.upload_url_ttl_seconds,
        )
        target = UploadTarget(url=signed.url, headers=signed.headers, expires_at=signed.expires_at)
    return UploadStatus(upload_id=row.id, state=row.state, upload=target)  # type: ignore[arg-type]


@router.post(
    "/uploads",
    response_model=UploadStatus,
    status_code=201,
    responses=_ERRORS,
)
async def create_upload(
    body: UploadCreate,
    request: Request,
    response: Response,
    session: Session,
    principal: Uploader,
) -> UploadStatus | Response:
    settings = _settings(request)
    store = _store(request)
    if body.byte_size > settings.max_upload_bytes:
        return error_response(413, "Clip too large.", code="upload_too_large")
    if body.duration_ms > settings.max_upload_duration_ms:
        return error_response(413, "Clip too long.", code="upload_too_long")

    existing = await session.get(AudioUpload, body.upload_id)
    if existing is not None:
        return _existing(existing, body, principal, store, settings, response)

    since = datetime.now(UTC) - timedelta(days=1)
    used = await session.scalar(
        select(func.coalesce(func.sum(AudioUpload.byte_size), 0)).where(
            AudioUpload.user_id == principal.user_id,
            AudioUpload.created_at >= since,
        ),
    )
    if int(used or 0) + body.byte_size > settings.daily_upload_bytes_quota:
        return error_response(429, "Daily upload quota reached.", code="quota_exceeded")

    row = AudioUpload(
        id=body.upload_id,
        user_id=principal.user_id,
        session_id=body.session_id,
        segment_id=body.segment_id,
        offset_ms=body.offset_ms,
        duration_ms=body.duration_ms,
        byte_size=body.byte_size,
        sha256=body.sha256,
        codec=body.codec,
        sample_rate_hz=body.sample_rate_hz,
        channel_count=body.channel_count,
        captured_at=body.captured_at,
        state="pending",
        object_key=audio_key(principal.user_id, body.upload_id),
    )
    session.add(row)
    try:
        await session.commit()
    except IntegrityError:
        # A concurrent retry won the insert, or the same clip arrived under another id.
        await session.rollback()
        raced = await session.get(AudioUpload, body.upload_id)
        if raced is None:
            logger.warning("upload_duplicate_clip", extra={"upload_id": str(body.upload_id)})
            return error_response(409, "This clip was already uploaded.", code="duplicate_clip")
        return _existing(raced, body, principal, store, settings, response)
    return _status(row, store, settings)


def _existing(
    row: AudioUpload,
    body: UploadCreate,
    principal: Principal,
    store: ObjectStore,
    settings: Settings,
    response: Response,
) -> UploadStatus | Response:
    # Another user's id and a changed payload look the same from outside: never say which.
    if row.user_id != principal.user_id or not _same(row, body):
        logger.warning("upload_idempotency_conflict", extra={"upload_id": str(body.upload_id)})
        return error_response(
            409,
            "Upload id already used with different content.",
            code="idempotency_conflict",
        )
    response.status_code = 200
    return _status(row, store, settings)


async def _owned(session: AsyncSession, upload_id: uuid.UUID, principal: Principal) -> AudioUpload:
    row = await session.get(AudioUpload, upload_id)
    if row is None or row.user_id != principal.user_id:
        raise HTTPException(status_code=404, detail="Upload not found.")
    return row


@router.post("/uploads/{upload_id}/complete", response_model=UploadStatus, responses=_ERRORS)
async def complete_upload(
    upload_id: uuid.UUID,
    request: Request,
    session: Session,
    principal: Uploader,
) -> UploadStatus | Response:
    store = _store(request)
    row = await _owned(session, upload_id, principal)
    if row.state != "pending":
        return UploadStatus(upload_id=row.id, state=row.state)  # type: ignore[arg-type]

    stored = await store.stat(row.object_key)
    if stored is None:
        return error_response(409, "Upload the clip before completing it.", code="upload_missing")
    if stored.sha256 is None:
        # The store must verify checksums; without one we can't prove integrity (fail closed).
        logger.error("upload_checksum_unavailable", extra={"upload_id": str(row.id)})
    if stored.size != row.byte_size or stored.sha256 != row.sha256:
        await store.delete(row.object_key)
        logger.warning("upload_integrity_failed", extra={"upload_id": str(row.id)})
        return error_response(
            409,
            "Uploaded bytes don't match; upload again.",
            code="upload_integrity_failed",
        )

    await session.execute(
        update(AudioUpload)
        .where(AudioUpload.id == row.id, AudioUpload.state == "pending")
        .values(state="received", received_at=datetime.now(UTC)),
    )
    await enqueue(
        session,
        user_id=row.user_id,
        kind=TRANSCRIBE_JOB,
        subject_id=row.id,
        idempotency_key=f"{TRANSCRIBE_JOB}:{row.id}",
    )
    await session.commit()
    logger.info("upload_received", extra={"upload_id": str(row.id), "bytes": row.byte_size})
    return UploadStatus(upload_id=row.id, state="received")


@router.get("/uploads/{upload_id}", response_model=UploadStatus, responses=_ERRORS)
async def get_upload(
    upload_id: uuid.UUID,
    request: Request,
    session: Session,
    principal: Uploader,
) -> UploadStatus:
    row = await _owned(session, upload_id, principal)
    return _status(row, _store(request), _settings(request))
