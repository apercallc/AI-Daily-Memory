"""Assembling the daily report contract from structured memory (Phase 2, ADR-0019).

Two gaps are documented here rather than hidden, both because the upload protocol doesn't yet
carry data the phone already computes locally:

- Every report is rendered in UTC. The backend has no IANA time zone for the account —
  `captured_at` carries a UTC offset from capture time, not a zone name, and offsets alone are
  ambiguous (ADR-0012's capture time model is phone-local).
- `memory_day` is the UTC calendar date of a session's first clip, not the phone's real
  sleep-midpoint memory day (ADR-0012).

Headline/summary text, people, topics, gaps, and highlights are not populated: each needs either
a separate summarization pass or phone-local data (Quick Notes, the schedule) this build does
not have. `SessionSummary.title` is a placeholder for the same reason — the phone's own session
titles aren't synced to the backend.
"""

import uuid
from collections import defaultdict
from datetime import UTC, date, datetime, time, timedelta
from typing import Literal, cast

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import (
    AudioUpload,
    DailyReportRow,
    MemoryItem,
    MemoryItemEvidence,
    Transcript,
    TranscriptSegment,
)
from app.reports.daily import (
    ActionItem,
    Commitment,
    Confidence,
    Coverage,
    DailyReport,
    Decision,
    Evidence,
    Note,
    SessionSummary,
)

PLACEHOLDER_TIMEZONE = "UTC"
DEFAULT_SESSION_TITLE = "Session"


def _day_bounds(memory_day: date) -> tuple[datetime, datetime]:
    start = datetime.combine(memory_day, time.min, UTC)
    return start, start + timedelta(days=1)


async def _evidence_for(session: AsyncSession, memory_item_id: uuid.UUID) -> list[Evidence]:
    # Joined through the segment's own transcript/upload, not `active_transcript_id`: evidence
    # is immutable (ADR-0004) and must still resolve even if a later re-transcription changed
    # which revision is active.
    rows = (
        await session.execute(
            select(
                MemoryItemEvidence.quote,
                TranscriptSegment.start_ms,
                AudioUpload.captured_at,
                AudioUpload.session_id,
            )
            .join(
                TranscriptSegment, TranscriptSegment.id == MemoryItemEvidence.transcript_segment_id
            )
            .join(Transcript, Transcript.id == TranscriptSegment.transcript_id)
            .join(AudioUpload, AudioUpload.id == Transcript.upload_id)
            .where(MemoryItemEvidence.memory_item_id == memory_item_id)
            .order_by(AudioUpload.captured_at, TranscriptSegment.start_ms),
        )
    ).all()
    return [
        Evidence(
            session_id=str(session_id),
            at=captured_at + timedelta(milliseconds=start_ms),
            quote=quote,
        )
        for quote, start_ms, captured_at, session_id in rows
    ] or [Evidence(session_id="unknown", at=datetime.now(UTC))]


async def build_daily_report(
    session: AsyncSession, *, user_id: uuid.UUID, memory_day: date, now: datetime | None = None
) -> DailyReport:
    now = now or datetime.now(UTC)
    start, end = _day_bounds(memory_day)
    uploads = (
        await session.scalars(
            select(AudioUpload).where(
                AudioUpload.user_id == user_id,
                AudioUpload.captured_at >= start,
                AudioUpload.captured_at < end,
            ),
        )
    ).all()

    sessions: dict[uuid.UUID, list[AudioUpload]] = defaultdict(list)
    for upload in uploads:
        sessions[upload.session_id].append(upload)

    recorded_ms = sum(u.duration_ms for u in uploads)
    pending_ms = sum(u.duration_ms for u in uploads if u.state not in ("transcribed", "delivered"))
    first_start = min((u.captured_at for u in uploads), default=None)
    last_end = max(
        (u.captured_at + timedelta(milliseconds=u.duration_ms) for u in uploads), default=None
    )

    session_summaries = []
    for session_id, clips in sessions.items():
        s_start = min(c.captured_at for c in clips)
        s_end = max(c.captured_at + timedelta(milliseconds=c.duration_ms) for c in clips)
        session_summaries.append(
            SessionSummary(
                session_id=str(session_id),
                title=DEFAULT_SESSION_TITLE,
                start=s_start,
                end=s_end,
                timezone=PLACEHOLDER_TIMEZONE,
            ),
        )

    items = (
        await session.scalars(
            select(MemoryItem).where(
                MemoryItem.user_id == user_id,
                MemoryItem.memory_day == memory_day,
                MemoryItem.dismissed_at.is_(None),
            ),
        )
    ).all()

    decisions, actions, commitments, ideas, questions = [], [], [], [], []
    for item in items:
        evidence = await _evidence_for(session, item.id)
        # CHECK constraints already guarantee these come only from the enums (app/db/models.py).
        confidence = cast(Confidence, item.confidence)
        if item.kind == "decision":
            decisions.append(Decision(text=item.text, confidence=confidence, evidence=evidence))
        elif item.kind == "action":
            actions.append(
                ActionItem(
                    text=item.text,
                    confidence=confidence,
                    evidence=evidence,
                    owner=item.owner or "me",
                    due=item.due,
                ),
            )
        elif item.kind == "commitment":
            commitments.append(
                Commitment(
                    text=item.text,
                    confidence=confidence,
                    evidence=evidence,
                    direction=cast(Literal["i_owe", "owed_to_me"], item.direction),
                    counterpart=item.counterpart or "",
                    due=item.due,
                ),
            )
        elif item.kind == "idea":
            ideas.append(Note(text=item.text, confidence=confidence, evidence=evidence))
        else:
            questions.append(Note(text=item.text, confidence=confidence, evidence=evidence))

    status: Literal["complete", "partial"] = "partial" if pending_ms > 0 else "complete"
    return DailyReport(
        memory_day=memory_day,
        timezone=PLACEHOLDER_TIMEZONE,
        generated_at=now,
        status=status,
        action_items=actions,
        commitments=commitments,
        decisions=decisions,
        ideas=ideas,
        open_questions=questions,
        sessions=session_summaries,
        coverage=Coverage(
            recorded_minutes=recorded_ms // 60_000,
            first_start=first_start,
            last_end=last_end,
            pending_minutes=pending_ms // 60_000,
        ),
    )


async def rebuild_daily_report(
    session: AsyncSession, *, user_id: uuid.UUID, memory_day: date, extraction_version: int
) -> DailyReport:
    """Builds the report and upserts it. Called after extraction so a session's report always
    reflects the memory items just written; the caller commits."""
    report = await build_daily_report(session, user_id=user_id, memory_day=memory_day)
    await session.execute(
        insert(DailyReportRow)
        .values(
            id=uuid.uuid4(),
            user_id=user_id,
            memory_day=memory_day,
            status=report.status,
            report=report.model_dump(mode="json"),
            extraction_version=extraction_version,
        )
        .on_conflict_do_update(
            index_elements=["user_id", "memory_day"],
            set_={
                "status": report.status,
                "report": report.model_dump(mode="json"),
                "extraction_version": extraction_version,
                "generated_at": report.generated_at,
            },
        ),
    )
    return report
