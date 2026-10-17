"""Turning synced sessions into notes (the web app's unit of reading and organizing).

A note is one recorded session. Its derived fields (title, preview, transcript text, counts) are
rebuilt whenever the session syncs; the person's own organization (their title, folder, star,
archive state) is never touched here.
"""

import re
import uuid
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from typing import Any

from sqlalchemy import select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import DailyReportRow, MemoryItem, Note
from app.reports.synced import is_phone_document

TITLE_MAX = 80
PREVIEW_MAX = 200
LEAD_MAX = 200
UNTITLED = "Recording"
# The item kinds that say most about a session, in the order a title is picked from.
_TITLE_ORDER = ("decision", "commitment", "action", "idea", "question")
_SPACES = re.compile(r"\s+")


def clip(text: str, limit: int) -> str:
    """Single-spaced text cut at a word boundary, with an ellipsis when it was shortened."""
    flat = _SPACES.sub(" ", text).strip()
    if len(flat) <= limit:
        return flat
    cut = flat[: limit - 1]
    space = cut.rfind(" ")
    return (cut[:space] if space > limit // 2 else cut).rstrip(" ,.;:-") + "…"


@dataclass
class SessionContent:
    """Everything one session contributes, gathered from items and transcript lines."""

    session_id: uuid.UUID
    memory_day: date
    items: list[tuple[str, str, int | None]] = field(default_factory=list)  # kind, text, epoch ms
    lines: list[tuple[int, str]] = field(default_factory=list)  # start epoch ms, text
    phone_title: str | None = None  # what the person named the session on their phone
    started_ms: int | None = None  # when the phone says it started
    declared: bool = False  # the sync listed this session, so its phone title is authoritative


@dataclass
class NoteFields:
    memory_day: date
    started_at: datetime | None
    phone_title: str | None
    auto_title: str
    title_from_transcript: bool
    item_preview: str
    lead: str
    transcript_text: str
    line_count: int
    word_count: int


def _epoch(ms: int) -> datetime | None:
    try:
        return datetime.fromtimestamp(ms / 1000, UTC)
    except (OverflowError, OSError, ValueError):
        return None


def build_fields(content: SessionContent) -> NoteFields:
    lines = sorted(content.lines)
    spoken = [text.strip() for _, text in lines if text.strip()]
    ranked = sorted(
        content.items,
        key=lambda i: _TITLE_ORDER.index(i[0]) if i[0] in _TITLE_ORDER else len(_TITLE_ORDER),
    )
    times = [ms for _, _, ms in content.items if ms] + [ms for ms, _ in lines if ms]
    if content.started_ms:
        times = [content.started_ms]
    first_spoken = clip(spoken[0], LEAD_MAX) if spoken else ""
    if ranked:
        title, from_transcript = clip(ranked[0][1], TITLE_MAX), False
    elif first_spoken:
        title, from_transcript = clip(first_spoken, TITLE_MAX), True
    else:
        title, from_transcript = UNTITLED, False
    # Previews show what the title does not already say: the other items, and the spoken words
    # after the line a title was taken from.
    leftover = spoken[1:] if from_transcript else spoken
    lead = clip(" · ".join(leftover[:2]), LEAD_MAX)
    return NoteFields(
        memory_day=content.memory_day,
        started_at=_epoch(min(times)) if times else None,
        phone_title=" ".join(content.phone_title.split())[:120] or None
        if content.phone_title
        else None,
        auto_title=title,
        title_from_transcript=from_transcript,
        item_preview=clip(" · ".join(t for _, t, _ in ranked[1:4]), PREVIEW_MAX),
        lead=lead,
        transcript_text="\n".join(spoken),
        line_count=len(spoken),
        word_count=sum(len(t.split()) for t in spoken),
    )


async def upsert_note(session: AsyncSession, user_id: uuid.UUID, content: SessionContent) -> None:
    """Creates or refreshes one session's note from exactly what the sync carried. A sync without
    transcripts therefore empties the note's transcript, which is what turning transcript sync off
    should do. The phone's title is only replaced by a payload that declares the session."""
    fields = build_fields(content)
    values: dict[str, Any] = {
        "memory_day": fields.memory_day,
        "auto_title": fields.auto_title,
        "title_from_transcript": fields.title_from_transcript,
        "item_preview": fields.item_preview,
        "lead": fields.lead,
        "transcript_text": fields.transcript_text,
        "line_count": fields.line_count,
        "word_count": fields.word_count,
    }
    if content.declared:
        values["phone_title"] = fields.phone_title
    existing = await session.scalar(
        select(Note).where(Note.user_id == user_id, Note.session_id == content.session_id)
    )
    if existing is None:
        session.add(
            Note(
                user_id=user_id,
                session_id=content.session_id,
                started_at=fields.started_at,
                **values,
            )
        )
        return
    # A payload with no timestamps must not erase a start time an earlier one provided.
    values["started_at"] = fields.started_at or existing.started_at
    for name, value in values.items():
        setattr(existing, name, value)


async def forget_transcripts(session: AsyncSession, user_id: uuid.UUID, day: date) -> None:
    """Empties the transcript text of a day's notes. A title taken from the spoken words goes with
    it, since it is a piece of the transcript."""
    await session.execute(
        update(Note)
        .where(
            Note.user_id == user_id, Note.memory_day == day, Note.title_from_transcript.is_(True)
        )
        .values(auto_title=UNTITLED, title_from_transcript=False)
    )
    await session.execute(
        update(Note)
        .where(Note.user_id == user_id, Note.memory_day == day)
        .values(lead="", transcript_text="", line_count=0, word_count=0)
    )


BACKFILL_BATCH_SIZE = 300  # rows per read query and rows per write transaction


async def backfill_notes(session: AsyncSession, user_id: uuid.UUID) -> int:
    """Builds notes for a person who synced before notes existed. Safe to repeat.

    A long-lived account's first touch can have an unbounded number of `MemoryItem` and
    `DailyReportRow` rows. Both the reads and the writes proceed in bounded batches
    (`BACKFILL_BATCH_SIZE` rows at a time, keyset-paginated by primary key) so a single
    query can never pull an unbounded resultset into memory, and so the write side commits
    incrementally instead of holding one giant transaction open for the whole account.
    """
    sessions: dict[uuid.UUID, SessionContent] = {}

    def bucket(session_id: uuid.UUID, day: date) -> SessionContent:
        found = sessions.get(session_id)
        if found is None:
            found = sessions[session_id] = SessionContent(session_id, day)
        return found

    last_item_id: uuid.UUID | None = None
    while True:
        item_query = (
            select(MemoryItem)
            .where(MemoryItem.user_id == user_id)
            .order_by(MemoryItem.id)
            .limit(BACKFILL_BATCH_SIZE)
        )
        if last_item_id is not None:
            item_query = item_query.where(MemoryItem.id > last_item_id)
        item_batch = (await session.scalars(item_query)).all()
        for item in item_batch:
            bucket(item.session_id, item.memory_day).items.append((item.kind, item.text, None))
        if len(item_batch) < BACKFILL_BATCH_SIZE:
            break
        last_item_id = item_batch[-1].id

    last_row_id: uuid.UUID | None = None
    while True:
        row_query = (
            select(DailyReportRow)
            .where(DailyReportRow.user_id == user_id)
            .order_by(DailyReportRow.id)
            .limit(BACKFILL_BATCH_SIZE)
        )
        if last_row_id is not None:
            row_query = row_query.where(DailyReportRow.id > last_row_id)
        row_batch = (await session.scalars(row_query)).all()
        for row in row_batch:
            if is_phone_document(row.report):
                lines = row.report.get("transcriptLines")
                for line in lines if isinstance(lines, list) else []:
                    if not isinstance(line, dict):
                        continue
                    try:
                        session_id = uuid.UUID(str(line.get("sessionId")))
                        start = int(line.get("startEpochMs") or 0)
                    except (ValueError, TypeError):
                        continue
                    text = line.get("text")
                    if isinstance(text, str):
                        bucket(session_id, row.memory_day).lines.append((start, text))
        if len(row_batch) < BACKFILL_BATCH_SIZE:
            break
        last_row_id = row_batch[-1].id

    if not sessions:
        return 0
    contents = list(sessions.values())
    for start in range(0, len(contents), BACKFILL_BATCH_SIZE):
        for content in contents[start : start + BACKFILL_BATCH_SIZE]:
            fields = build_fields(content)
            statement = pg_insert(Note).values(
                user_id=user_id,
                session_id=content.session_id,
                memory_day=fields.memory_day,
                started_at=fields.started_at,
                auto_title=fields.auto_title,
                title_from_transcript=fields.title_from_transcript,
                item_preview=fields.item_preview,
                lead=fields.lead,
                transcript_text=fields.transcript_text,
                line_count=fields.line_count,
                word_count=fields.word_count,
            )
            await session.execute(
                statement.on_conflict_do_nothing(index_elements=["user_id", "session_id"])
            )
        await session.commit()
    return len(contents)
