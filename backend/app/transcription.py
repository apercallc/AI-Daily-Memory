"""Transcription: one provider behind one thin seam (AGENTS "provider SDK calls live behind
one seam"). No real provider is wired in until Gate B (cost model, provider terms, consent
review); until then `transcription_provider=disabled` and transcribe jobs wait in the queue.
"""

import asyncio
import hashlib
import logging
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime
from typing import Protocol

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.models import (
    AudioUpload,
    DeviceKey,
    SealedDelivery,
    Transcript,
    TranscriptSegment,
    User,
)
from app.jobs import ClaimedJob, enqueue
from app.retention import MAX_HOLD
from app.sealing import seal, transcript_payload
from app.storage import ObjectMissingError, ObjectStore

logger = logging.getLogger("app.transcription")

# Provider timestamps may run slightly past the clip (rounding); more than this is invalid.
_END_TOLERANCE_MS = 1_000


@dataclass(frozen=True)
class TranscribedSpan:
    start_ms: int
    end_ms: int
    text: str
    confidence: float | None = None
    speaker: str | None = None


@dataclass(frozen=True)
class TranscriptResult:
    language: str | None
    spans: list[TranscribedSpan]


class JobError(Exception):
    """A failure with an operator-visible code; `retryable` decides retry vs dead-letter."""

    def __init__(self, code: str, *, retryable: bool) -> None:
        super().__init__(code)
        self.code = code
        self.retryable = retryable


class TranscriptionProvider(Protocol):
    name: str
    model: str

    async def transcribe(
        self,
        audio: bytes,
        *,
        codec: str,
        sample_rate_hz: int,
    ) -> TranscriptResult:
        """Raises JobError for provider failures (retryable for timeouts and 5xx)."""
        ...


def validate(result: TranscriptResult, duration_ms: int) -> None:
    """Rejects results that would corrupt evidence: out-of-range or out-of-order spans."""
    previous_start = 0
    for span in result.spans:
        if (
            span.start_ms < 0
            or span.end_ms < span.start_ms
            or span.end_ms > duration_ms + _END_TOLERANCE_MS
            or span.start_ms < previous_start
            or (span.confidence is not None and not 0.0 <= span.confidence <= 1.0)
        ):
            raise JobError("invalid_provider_result", retryable=False)
        previous_start = span.start_ms


class TranscribeHandler:
    kind = "transcribe"

    def __init__(
        self,
        sessions: async_sessionmaker[AsyncSession],
        store: ObjectStore,
        provider: TranscriptionProvider,
        *,
        timeout_seconds: float = 300.0,
        read_timeout_seconds: float = 60.0,
    ) -> None:
        # Both budgets together stay well inside the job lease (jobs.DEFAULT_LEASE).
        self.sessions = sessions
        self.store = store
        self.provider = provider
        self.timeout_seconds = timeout_seconds
        self.read_timeout_seconds = read_timeout_seconds

    async def __call__(self, job: ClaimedJob) -> None:
        async with self.sessions() as session:
            upload = await session.get(AudioUpload, job.subject_id)
            if upload is None or upload.user_id != job.user_id:
                raise JobError("upload_missing", retryable=False)
            key, state = upload.object_key, upload.state
            codec, rate, duration = upload.codec, upload.sample_rate_hz, upload.duration_ms
            expected_size, expected_sha256 = upload.byte_size, upload.sha256
            clip = (upload.segment_id, upload.offset_ms, upload.captured_at)
            mode = await session.scalar(select(User.storage_mode).where(User.id == job.user_id))
            # Phone modes (ADR-0014): the transcript is sealed to the key of the device that
            # uploaded the clip, never stored in the clear and never sent to another device.
            # The key recorded at upload wins over the current mode, so a later mode switch
            # can't expose a clip uploaded under a phone-only promise. Phase 2 will hold the
            # transcript until the day's report is built; until then it is sealed at once.
            device_key = (
                await session.get(DeviceKey, upload.device_key_id)
                if upload.device_key_id is not None
                else None
            )

        if state not in ("received", "transcribed"):
            raise JobError(f"upload_{state}", retryable=False)
        if state != "transcribed":
            if device_key is not None and device_key.revoked_at is not None:
                raise JobError("device_key_revoked", retryable=False)
            if device_key is None and mode != "full_cloud":
                raise JobError("device_key_missing", retryable=False)

        if state != "transcribed":
            try:
                async with asyncio.timeout(self.read_timeout_seconds):
                    audio = await self.store.read(key)
            except ObjectMissingError as e:
                raise JobError("audio_missing", retryable=False) from e
            except TimeoutError as e:
                raise JobError("store_timeout", retryable=True) from e
            if len(audio) != expected_size or hashlib.sha256(audio).hexdigest() != expected_sha256:
                raise JobError("audio_integrity_failed", retryable=False)
            try:
                async with asyncio.timeout(self.timeout_seconds):
                    result = await self.provider.transcribe(audio, codec=codec, sample_rate_hz=rate)
            except TimeoutError as e:
                raise JobError("provider_timeout", retryable=True) from e
            validate(result, duration)
            if device_key is None:
                await self._persist(job.subject_id, job.user_id, result)
            else:
                payload = transcript_payload(
                    upload_id=job.subject_id,
                    segment_id=clip[0],
                    offset_ms=clip[1],
                    captured_at=clip[2],
                    provider=self.provider.name,
                    model=self.provider.model,
                    result=result,
                )
                try:
                    sealed = seal(device_key.public_key, job.subject_id, payload)
                except ValueError as e:
                    raise JobError("sealing_failed", retryable=False) from e
                await self._persist_sealed(job.subject_id, job.user_id, device_key.id, sealed)

        # Only after the transcript is committed (ADR-0003): delete the temporary audio. A
        # failure here retries the job, which lands back on this idempotent step.
        await self.store.delete(key)
        logger.info("upload_transcribed", extra={"upload_id": str(job.subject_id)})

    async def _persist(
        self,
        upload_id: uuid.UUID,
        user_id: uuid.UUID,
        result: TranscriptResult,
    ) -> None:
        async with self.sessions() as session, session.begin():
            # Serializes concurrent persists of one upload (lost lease + new owner).
            upload = await session.get(AudioUpload, upload_id, with_for_update=True)
            if upload is None or upload.state == "transcribed":
                return
            revision = await session.scalar(
                select(func.coalesce(func.max(Transcript.revision), 0)).where(
                    Transcript.upload_id == upload_id,
                ),
            )
            transcript = Transcript(
                user_id=user_id,
                upload_id=upload_id,
                revision=int(revision or 0) + 1,
                provider=self.provider.name,
                model=self.provider.model,
                language=result.language,
            )
            session.add(transcript)
            await session.flush()
            session.add_all(
                TranscriptSegment(
                    user_id=user_id,
                    transcript_id=transcript.id,
                    start_ms=span.start_ms,
                    end_ms=span.end_ms,
                    text=span.text,
                    speaker=span.speaker,
                    confidence=span.confidence,
                )
                for span in result.spans
            )
            upload.active_transcript_id = transcript.id
            upload.state = "transcribed"
            # Phase 2 (ADR-0019): the only server-side transcript is a full_cloud one, so this
            # is the only path that can ever have something to extract. One job per session:
            # a later clip in the same session adds evidence but doesn't retrigger a rerun.
            await enqueue(
                session,
                user_id=user_id,
                kind="extract",
                subject_id=upload.session_id,
                idempotency_key=f"extract:{upload.session_id}",
            )

    async def _persist_sealed(
        self,
        upload_id: uuid.UUID,
        user_id: uuid.UUID,
        device_key_id: uuid.UUID,
        ciphertext: bytes,
    ) -> None:
        async with self.sessions() as session, session.begin():
            # Key before upload: the same order revoke_token takes its locks, so the two can't
            # deadlock. The token (and so its key) may have been revoked meanwhile.
            key = await session.get(DeviceKey, device_key_id, with_for_update=True)
            if key is None or key.revoked_at is not None:
                raise JobError("device_key_revoked", retryable=False)
            upload = await session.get(AudioUpload, upload_id, with_for_update=True)
            if upload is None or upload.state == "transcribed":
                return
            session.add(
                SealedDelivery(
                    user_id=user_id,
                    upload_id=upload_id,
                    device_key_id=device_key_id,
                    ciphertext=ciphertext,
                    expires_at=datetime.now(UTC) + MAX_HOLD,
                ),
            )
            upload.state = "transcribed"

    async def on_dead(self, job: ClaimedJob, error_code: str) -> None:
        """The job won't run again: record why on the upload and delete its audio now, so a
        failed clip never lingers in the bucket (ADR-0003). Deletion failures are left to
        the retention sweep."""
        async with self.sessions() as session:
            key = await session.scalar(
                update(AudioUpload)
                .where(
                    AudioUpload.id == job.subject_id,
                    AudioUpload.user_id == job.user_id,
                    AudioUpload.state == "received",
                )
                .values(state="failed", failure_code=error_code)
                .returning(AudioUpload.object_key),
            )
            await session.commit()
        if key is not None:
            await self.store.delete(key)
