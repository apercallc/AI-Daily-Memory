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

The build is total: whatever the database holds, the result satisfies the report contract. The
contract bounds every list and the day's coverage; extraction only bounds one session's
contribution (app/extraction.py `validate`), so a day that aggregates past a bound — many
sessions, many items, evidence written by an older build, clip durations a buggy client
invented — is truncated deterministically here instead of failing the whole build (which would
dead-letter the extraction job for data the provider legitimately produced).
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

# The contract's own caps (app/reports/daily.py), enforced at build time.
_MAX_SESSIONS = 50
_MAX_ITEMS_PER_SECTION = 50
_MAX_EVIDENCE_PER_ITEM = 5
# One memory day has at most 24*60 minutes; a client claiming more has overlapping or bogus
# clip metadata. Clamp instead of failing the build (Coverage rejects anything above this).
_MAX_DAY_MINUTES = 24 * 60
# Higher-confidence items survive truncation first; ties keep insertion order.
_CONFIDENCE_RANK: dict[str, int] = {"confirmed": 0, "likely": 1, "possible": 2}
# A commitment row whose counterpart is missing (only possible for rows written before
# extraction validated it, or by hand): the contract requires 1-80 chars, and the build
# stays total rather than crashing on it.
_UNKNOWN_COUNTERPART = "unknown"


def _day_bounds(memory_day: date) -> tuple[datetime, datetime]:
    start = datetime.combine(memory_day, time.min, UTC)
    return start, start + timedelta(days=1)


async def _evidence_map(
    session: AsyncSession, item_ids: list[uuid.UUID], *, now: datetime
) -> dict[uuid.UUID, list[Evidence]]:
    """Every item's evidence in one query (the items were fetched in one go, so a per-item
    lookup was an N+1). Joined through the segment's own transcript/upload, not
    `active_transcript_id`: evidence is immutable (ADR-0004) and must still resolve even if a
    later re-transcription changed which revision is active. Each item keeps at most
    the contract's five evidence entries, earliest first."""
    grouped: dict[uuid.UUID, list[Evidence]] = {item_id: [] for item_id in item_ids}
    if item_ids:
        rows = (
            await session.execute(
                select(
                    MemoryItemEvidence.memory_item_id,
                    MemoryItemEvidence.quote,
                    TranscriptSegment.start_ms,
                    AudioUpload.captured_at,
                    AudioUpload.session_id,
                )
                .join(
                    TranscriptSegment,
                    TranscriptSegment.id == MemoryItemEvidence.transcript_segment_id,
                )
                .join(Transcript, Transcript.id == TranscriptSegment.transcript_id)
                .join(AudioUpload, AudioUpload.id == Transcript.upload_id)
                .where(MemoryItemEvidence.memory_item_id.in_(item_ids))
                .order_by(
                    MemoryItemEvidence.memory_item_id,
                    AudioUpload.captured_at,
                    TranscriptSegment.start_ms,
                )
            )
        ).all()
        for item_id, quote, start_ms, captured_at, session_id in rows:
            grouped[item_id].append(
                Evidence(
                    session_id=str(session_id),
                    at=captured_at + timedelta(milliseconds=start_ms),
                    quote=quote,
                )
            )
    for item_id in item_ids:
        # Cascading deletes can leave an item with no evidence rows; the contract still
        # requires at least one, so the build stays honest instead of crashing.
        if not grouped[item_id]:
            grouped[item_id] = [Evidence(session_id="unknown", at=now)]
        else:
            grouped[item_id] = grouped[item_id][:_MAX_EVIDENCE_PER_ITEM]
    return grouped


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

    session_summaries: list[SessionSummary] = []
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
    session_summaries.sort(key=lambda s: s.start)
    session_summaries = session_summaries[:_MAX_SESSIONS]

    items = (
        await session.scalars(
            select(MemoryItem)
            .where(
                MemoryItem.user_id == user_id,
                MemoryItem.memory_day == memory_day,
                MemoryItem.dismissed_at.is_(None),
            )
            .order_by(MemoryItem.created_at, MemoryItem.id),
        )
    ).all()
    evidence = await _evidence_map(session, [item.id for item in items], now=now)

    # Truncate each section to the contract's cap, best-confidence first, insertion order
    # after that. The contract's caps are what every client (app, MCP) is built against.
    kept: dict[str, list[MemoryItem]] = defaultdict(list)
    for item in sorted(items, key=lambda i: (_CONFIDENCE_RANK[i.confidence], i.created_at, i.id)):
        if len(kept[item.kind]) < _MAX_ITEMS_PER_SECTION:
            kept[item.kind].append(item)

    decisions = [
        Decision(
            text=item.text,
            confidence=cast(Confidence, item.confidence),
            evidence=evidence[item.id],
        )
        for item in kept["decision"]
    ]
    actions = [
        ActionItem(
            text=item.text,
            confidence=cast(Confidence, item.confidence),
            evidence=evidence[item.id],
            owner=item.owner or "me",
            due=item.due,
        )
        for item in kept["action"]
    ]
    commitments = [
        Commitment(
            text=item.text,
            confidence=cast(Confidence, item.confidence),
            evidence=evidence[item.id],
            direction=cast(Literal["i_owe", "owed_to_me"], item.direction),
            counterpart=item.counterpart or _UNKNOWN_COUNTERPART,
            due=item.due,
        )
        for item in kept["commitment"]
    ]
    ideas = [
        Note(
            text=item.text,
            confidence=cast(Confidence, item.confidence),
            evidence=evidence[item.id],
        )
        for item in kept["idea"]
    ]
    questions = [
        Note(
            text=item.text,
            confidence=cast(Confidence, item.confidence),
            evidence=evidence[item.id],
        )
        for item in kept["question"]
    ]

    # Round pending time up: a report with 30 seconds of unprocessed audio is partial, and
    # the contract ties `partial` to a non-zero pending minute count.
    pending_minutes = min(-(-pending_ms // 60_000), _MAX_DAY_MINUTES)
    status: Literal["complete", "partial"] = "partial" if pending_minutes > 0 else "complete"
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
            recorded_minutes=min(recorded_ms // 60_000, _MAX_DAY_MINUTES),
            first_start=first_start,
            last_end=last_end,
            pending_minutes=pending_minutes,
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
