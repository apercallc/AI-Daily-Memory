"""Transcription: one provider behind one thin seam (AGENTS "provider SDK calls live behind
one seam"). No real provider is wired in until Gate B (cost model, provider terms, consent
review); until then `transcription_provider=disabled` and transcribe jobs wait in the queue.
"""

import asyncio
import logging
import uuid
from dataclasses import dataclass
from typing import Protocol

from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.models import AudioUpload, Transcript, TranscriptSegment
from app.jobs import ClaimedJob
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
    ) -> None:
        self.sessions = sessions
        self.store = store
        self.provider = provider
        self.timeout_seconds = timeout_seconds

    async def __call__(self, job: ClaimedJob) -> None:
        async with self.sessions() as session:
            upload = await session.get(AudioUpload, job.subject_id)
            if upload is None or upload.user_id != job.user_id:
                raise JobError("upload_missing", retryable=False)
            key, state = upload.object_key, upload.state
            codec, rate, duration = upload.codec, upload.sample_rate_hz, upload.duration_ms

        if state != "transcribed":
            try:
                audio = await self.store.read(key)
            except ObjectMissingError as e:
                raise JobError("audio_missing", retryable=False) from e
            try:
                async with asyncio.timeout(self.timeout_seconds):
                    result = await self.provider.transcribe(audio, codec=codec, sample_rate_hz=rate)
            except TimeoutError as e:
                raise JobError("provider_timeout", retryable=True) from e
            validate(result, duration)
            await self._persist(job.subject_id, job.user_id, result)

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
            await session.execute(
                update(Transcript)
                .where(Transcript.upload_id == upload_id, Transcript.active)
                .values(active=False),
            )
            transcript = Transcript(
                user_id=user_id,
                upload_id=upload_id,
                revision=int(revision or 0) + 1,
                active=True,
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
            upload.state = "transcribed"
