"""Structured extraction (Phase 2, ADR-0019): turns a session's transcript into decisions,
action items, commitments, ideas, and open questions, each backed by evidence.

Only runs for `full_cloud` accounts (the only mode where the server ever holds a plaintext
transcript — see app/transcription.py). Transcript text is untrusted model input (README
"Untrusted Content"): it is delimited as data inside the prompt, the provider gets no tool
access, and every field of its response is schema-validated before anything is persisted; a
provider that returns malformed output dead-letters the job rather than writing partial data.

Extraction never rewrites a transcript (ADR-0004): it only ever inserts `memory_items` rows
that cite `transcript_segments` by id.
"""

import asyncio
import logging
import uuid
from collections import defaultdict
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from typing import Literal, Protocol

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.db.models import (
    COMMITMENT_DIRECTIONS,
    CONFIDENCE_LEVELS,
    MEMORY_ITEM_KINDS,
    AudioUpload,
    MemoryItem,
    MemoryItemEvidence,
    Transcript,
    TranscriptSegment,
    User,
)
from app.jobs import ClaimedJob
from app.reports.build import rebuild_daily_report
from app.transcription import JobError

logger = logging.getLogger("app.extraction")

_MAX_ITEM_TEXT = 280
# The report contract bounds these too (app/reports/daily.py); a provider value outside the
# bound dead-letters here instead of crashing the insert (or the report build) later.
_MAX_QUOTE = 280
_MAX_NAME = 80
_MAX_EVIDENCE_PER_ITEM = 5
_MAX_ITEMS_PER_SESSION = 50


@dataclass(frozen=True)
class ExtractedItem:
    kind: Literal["decision", "action", "commitment", "idea", "question"]
    text: str
    confidence: Literal["confirmed", "likely", "possible"]
    # Indices into the numbered transcript passed to the provider; validated against the real
    # segment count before anything is trusted.
    evidence_segment_indices: tuple[int, ...]
    quote: str | None = None
    owner: str | None = None
    due: date | None = None
    direction: Literal["i_owe", "owed_to_me"] | None = None
    counterpart: str | None = None


@dataclass(frozen=True)
class ExtractionResult:
    items: tuple[ExtractedItem, ...]


@dataclass(frozen=True)
class Numbered:
    """One line of the transcript handed to the provider, numbered so its response can point
    back at specific segments without echoing full timestamps."""

    index: int
    speaker: str | None
    text: str


class ExtractionProvider(Protocol):
    name: str
    model: str

    async def extract(self, lines: tuple[Numbered, ...]) -> ExtractionResult:
        """Raises JobError for provider failures (retryable for timeouts and 5xx)."""
        ...


def validate(result: ExtractionResult, segment_count: int) -> None:
    """Rejects output that would corrupt evidence or overflow storage: everything must point at
    a real segment, every kind-specific field must be present exactly where it's required, and
    every bounded field must fit both its column and the report contract (ADR-0019: "every
    field of the response is schema- and range-checked" — a violation dead-letters the job
    rather than crashing a later insert or report build)."""
    if len(result.items) > _MAX_ITEMS_PER_SESSION:
        raise JobError("invalid_extraction_result", retryable=False)
    for item in result.items:
        if item.kind not in MEMORY_ITEM_KINDS or item.confidence not in CONFIDENCE_LEVELS:
            raise JobError("invalid_extraction_result", retryable=False)
        if not (1 <= len(item.text) <= _MAX_ITEM_TEXT):
            raise JobError("invalid_extraction_result", retryable=False)
        if item.quote is not None and len(item.quote) > _MAX_QUOTE:
            raise JobError("invalid_extraction_result", retryable=False)
        if item.owner is not None and (item.kind != "action" or len(item.owner) > _MAX_NAME):
            raise JobError("invalid_extraction_result", retryable=False)
        evidence = item.evidence_segment_indices
        if (
            not evidence
            or len(evidence) > _MAX_EVIDENCE_PER_ITEM
            or any(i < 0 or i >= segment_count for i in evidence)
        ):
            raise JobError("invalid_extraction_result", retryable=False)
        if item.kind == "commitment" and (
            item.direction not in COMMITMENT_DIRECTIONS
            or not item.counterpart
            or len(item.counterpart) > _MAX_NAME
        ):
            raise JobError("invalid_extraction_result", retryable=False)
        if item.kind != "commitment" and (item.direction is not None or item.counterpart):
            raise JobError("invalid_extraction_result", retryable=False)


class ExtractHandler:
    kind = "extract"

    def __init__(
        self,
        sessions: async_sessionmaker[AsyncSession],
        provider: ExtractionProvider,
        *,
        version: int = 1,
        timeout_seconds: float = 300.0,
    ) -> None:
        self.sessions = sessions
        self.provider = provider
        # A hard ceiling around the whole provider call (httpx's timeout is per phase), so a slow
        # response cannot outlive the job lease and be claimed twice.
        self.timeout_seconds = timeout_seconds
        # Bumping this forces every future rebuild to regenerate its report even without new
        # evidence (TODO.md "Regenerate when extraction version changes"); it does not
        # retroactively re-run past sessions on its own.
        self.version = version

    async def __call__(self, job: ClaimedJob) -> None:
        session_id = job.subject_id
        async with self.sessions() as db:
            mode = await db.scalar(select(User.storage_mode).where(User.id == job.user_id))
            if mode != "full_cloud":
                # A mode switch raced the job: nothing to extract from server-side, and nothing
                # to clean up (no plaintext was ever stored for this account).
                raise JobError("not_full_cloud", retryable=False)

            rows = (
                await db.execute(
                    select(AudioUpload, Transcript)
                    .join(Transcript, Transcript.id == AudioUpload.active_transcript_id)
                    .where(
                        AudioUpload.user_id == job.user_id,
                        AudioUpload.session_id == session_id,
                        AudioUpload.active_transcript_id.is_not(None),
                    )
                    # Clip order, then span order (ADR-0019). captured_at breaks offset ties
                    # (two segments can both start at offset 0), so a provider's evidence
                    # indices mean the same lines on every run.
                    .order_by(AudioUpload.offset_ms, AudioUpload.captured_at),
                )
            ).all()
            if not rows:
                raise JobError("nothing_transcribed_yet", retryable=False)

            # One query for every clip's segments: fetching them per clip was an N+1 (a
            # session's line numbering walked the transcript table once per clip).
            spans_by_transcript: dict[uuid.UUID, list[TranscriptSegment]] = defaultdict(list)
            for span in (
                await db.scalars(
                    select(TranscriptSegment)
                    .where(
                        TranscriptSegment.transcript_id.in_(
                            [transcript.id for _, transcript in rows]
                        )
                    )
                    .order_by(TranscriptSegment.transcript_id, TranscriptSegment.start_ms)
                )
            ).all():
                spans_by_transcript[span.transcript_id].append(span)

            segments: list[tuple[uuid.UUID, datetime]] = []  # (segment_id, absolute start time)
            lines: list[Numbered] = []
            for upload, transcript in rows:
                for span in spans_by_transcript[transcript.id]:
                    at = upload.captured_at + timedelta(milliseconds=span.start_ms)
                    segments.append((span.id, at))
                    lines.append(Numbered(index=len(lines), speaker=span.speaker, text=span.text))

            memory_day = min(upload.captured_at for upload, _ in rows).astimezone(UTC).date()

        if not lines:
            raise JobError("nothing_transcribed_yet", retryable=False)

        try:
            async with asyncio.timeout(self.timeout_seconds):
                result = await self.provider.extract(tuple(lines))
        except TimeoutError as e:
            raise JobError("provider_timeout", retryable=True) from e
        validate(result, len(segments))

        async with self.sessions() as db:
            # Idempotent by design: a rerun (new evidence, a bumped version) replaces this
            # session's items outright rather than trying to diff against the old set. Known
            # limitation: this also clears any earlier dismissal of an item from this session.
            item_ids = (
                await db.scalars(
                    select(MemoryItem.id).where(
                        MemoryItem.user_id == job.user_id, MemoryItem.session_id == session_id
                    ),
                )
            ).all()
            if item_ids:
                await db.execute(
                    delete(MemoryItemEvidence).where(
                        MemoryItemEvidence.memory_item_id.in_(item_ids)
                    ),
                )
                await db.execute(delete(MemoryItem).where(MemoryItem.id.in_(item_ids)))

            for extracted in result.items:
                row = MemoryItem(
                    id=uuid.uuid4(),
                    user_id=job.user_id,
                    session_id=session_id,
                    memory_day=memory_day,
                    kind=extracted.kind,
                    text=extracted.text,
                    confidence=extracted.confidence,
                    owner=extracted.owner if extracted.kind == "action" else None,
                    counterpart=extracted.counterpart,
                    direction=extracted.direction,
                    due=extracted.due,
                    extraction_model=f"{self.provider.name}:{self.provider.model}",
                    extraction_version=self.version,
                )
                db.add(row)
                for i in extracted.evidence_segment_indices:
                    segment_id, _at = segments[i]
                    db.add(
                        MemoryItemEvidence(
                            id=uuid.uuid4(),
                            user_id=job.user_id,
                            memory_item_id=row.id,
                            transcript_segment_id=segment_id,
                            quote=extracted.quote,
                        ),
                    )
            await db.flush()
            await rebuild_daily_report(
                db, user_id=job.user_id, memory_day=memory_day, extraction_version=self.version
            )
            await db.commit()
