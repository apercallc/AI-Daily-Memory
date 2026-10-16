"""Ask a question of your own memory (TODO.md Phase 4 "Recall engine").

    POST /v1/ask   {"question": "What did I promise Sam?"} -> an answer and the items behind it

Retrieval is a bounded, user-scoped query: the dates the question names, the words that matter,
the kind of item it hints at. The answer is built only from what that returns and abstains when
nothing matches. Each call is metered per person per day, server-side.
"""

import logging
import uuid
from datetime import UTC, date, datetime, timedelta
from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import func, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.memory import _like
from app.api.notes import _ensure_notes, _snippet
from app.auth import Principal, require
from app.config import Settings
from app.db import get_session
from app.db.models import MemoryItem, MemoryItemCorrection, Note
from app.errors import ErrorResponse, error_response
from app.plans import limits_for
from app.recall import (
    NOTHING_FOUND,
    Answer,
    DateRange,
    Evidence,
    evidence_only_answer,
    kind_hint,
    resolve_range,
    search_terms,
)
from app.usage import take

logger = logging.getLogger("app.ask")
router = APIRouter(tags=["ask"])

Session = Annotated[AsyncSession, Depends(get_session)]
Reader = Annotated[Principal, Depends(require("memory:read"))]
_ERRORS: dict[int | str, dict[str, object]] = {s: {"model": ErrorResponse} for s in (401, 403, 429)}
MAX_CANDIDATES = 20
MAX_MENTION_CANDIDATES = 40
MAX_TERMS = 6


class AskRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    question: str = Field(min_length=3, max_length=500)
    # The caller's own "today", so "yesterday" means theirs, not the server's UTC date.
    today: date | None = None


class Cited(BaseModel):
    id: uuid.UUID
    day: date
    kind: str
    text: str
    owner: str | None
    counterpart: str | None
    due: date | None


class MentionedIn(BaseModel):
    """A note whose own words (title or transcript) contain what was asked about."""

    session_id: uuid.UUID
    title: str
    memory_day: date
    snippet: str | None


class AskResponse(BaseModel):
    answer: str
    abstained: bool
    source: str
    range_label: str | None
    items: list[Cited]
    # Notes that mention the question's words, for people who read transcripts. Highlights only
    # exist when summaries are on, so this is how a transcript-only library is still askable.
    notes: list[MentionedIn] = []


async def retrieve(
    session: AsyncSession, user_id: uuid.UUID, question: str, today: date
) -> tuple[list[Evidence], DateRange | None]:
    """At most [MAX_CANDIDATES] of this user's own, undismissed items that could answer."""
    window = resolve_range(question, today)
    kind = kind_hint(question)
    terms = search_terms(question)
    text = func.coalesce(MemoryItemCorrection.text, MemoryItem.text)
    base = (
        select(MemoryItem, text.label("effective"))
        .outerjoin(MemoryItemCorrection, MemoryItemCorrection.memory_item_id == MemoryItem.id)
        .where(MemoryItem.user_id == user_id, MemoryItem.dismissed_at.is_(None))
    )
    if window is not None:
        base = base.where(
            MemoryItem.memory_day >= window.start, MemoryItem.memory_day <= window.end
        )

    rows: list[tuple[MemoryItem, str]] = []
    if terms:
        document = func.to_tsvector(
            "english",
            text
            + " "
            + func.coalesce(MemoryItem.owner, "")
            + " "
            + func.coalesce(MemoryItem.counterpart, ""),
        )
        query = func.websearch_to_tsquery("english", " or ".join(terms))
        found = await session.execute(
            base.where(document.op("@@")(query))
            .order_by(func.ts_rank(document, query).desc(), MemoryItem.memory_day.desc())
            .limit(MAX_CANDIDATES)
        )
        rows = [(item, effective) for item, effective in found.all()]
    if not rows and (window is not None or kind is not None):
        # A question about a time or a kind ("what did I decide yesterday?") needs no keywords.
        broad = base
        if kind is not None:
            broad = broad.where(MemoryItem.kind == kind)
        found = await session.execute(
            broad.order_by(MemoryItem.memory_day.desc(), MemoryItem.created_at.desc()).limit(
                MAX_CANDIDATES
            )
        )
        rows = [(item, effective) for item, effective in found.all()]
    evidence = [
        Evidence(
            item_id=str(item.id),
            day=item.memory_day,
            kind=item.kind,
            text=effective,
            owner=item.owner,
            counterpart=item.counterpart,
            due=item.due,
            direction=item.direction,
        )
        for item, effective in rows
    ]
    return evidence, window


MAX_MENTIONS = 5
NOTHING_MATCHED_BUT_MENTIONED = (
    "No decision, task or idea matches, but these notes mention it. Nothing was guessed."
)


async def mentioned_in(
    session: AsyncSession, user_id: uuid.UUID, question: str, window: DateRange | None
) -> list[MentionedIn]:
    """The notes whose title or transcript contain the most of the question's keywords."""
    terms = search_terms(question)[:MAX_TERMS]
    if not terms:
        return []
    await _ensure_notes(session, user_id)
    patterns = [_like(t) for t in terms]
    conditions = [
        or_(
            *(Note.transcript_text.ilike(p, escape="\\") for p in patterns),
            *(Note.auto_title.ilike(p, escape="\\") for p in patterns),
            *(Note.custom_title.ilike(p, escape="\\") for p in patterns),
            *(Note.phone_title.ilike(p, escape="\\") for p in patterns),
        )
    ]
    query = select(Note).where(Note.user_id == user_id, Note.archived_at.is_(None), *conditions)
    if window is not None:
        query = query.where(Note.memory_day >= window.start, Note.memory_day <= window.end)
    rows = (
        await session.scalars(query.order_by(Note.memory_day.desc()).limit(MAX_MENTION_CANDIDATES))
    ).all()

    def score(note: Note) -> int:
        haystack = (
            f"{note.custom_title or ''} {note.phone_title or ''} {note.transcript_text}".lower()
        )
        return sum(1 for t in terms if t.lower() in haystack)

    best = sorted(rows, key=lambda n: (-score(n), -n.memory_day.toordinal()))[:MAX_MENTIONS]
    return [
        MentionedIn(
            session_id=n.session_id,
            title=n.custom_title or n.phone_title or n.auto_title,
            memory_day=n.memory_day,
            snippet=_snippet(n.transcript_text, terms),
        )
        for n in best
    ]


def _seconds_until_utc_midnight() -> int:
    now = datetime.now(UTC)
    tomorrow = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    return max(1, int((tomorrow - now).total_seconds()))


@router.post("/ask", response_model=AskResponse, responses=_ERRORS)
async def ask(
    body: AskRequest, request: Request, session: Session, reader: Reader
) -> AskResponse | JSONResponse:
    settings: Settings = request.app.state.settings
    limit = limits_for(settings).ask_per_day
    if not await take(session, reader.user_id, "ask", limit=limit):
        await session.rollback()
        return error_response(
            429,
            "You've reached today's limit for questions. It resets at midnight UTC.",
            code="quota_exceeded",
            headers={"Retry-After": str(_seconds_until_utc_midnight())},
        )
    today = body.today or datetime.now(UTC).date()
    # A caller can name its own "today" but not one far from the real one: it only decides what
    # "yesterday" means, never what data is reachable.
    real = datetime.now(UTC).date()
    if abs((today - real).days) > 1:
        today = real
    evidence, window = await retrieve(session, reader.user_id, body.question, today)
    mentions = (
        await mentioned_in(session, reader.user_id, body.question, window)
        if "transcript:read" in reader.scopes
        else []
    )
    await session.commit()

    label = window.label if window else None
    # Ask is retrieval-only (ADR-0028): no model provider ever sees this person's memory, so
    # the answer is always just the matching items, read back plainly.
    if not evidence and mentions:
        answer = Answer(NOTHING_MATCHED_BUT_MENTIONED, (), abstained=True, source="evidence_only")
    elif not evidence:
        answer = Answer(NOTHING_FOUND, (), abstained=True, source="evidence_only")
    else:
        answer = evidence_only_answer(body.question, evidence, label)
    by_id = {e.item_id: e for e in evidence}
    cited = [by_id[i] for i in answer.used if i in by_id]
    return AskResponse(
        answer=answer.text,
        abstained=answer.abstained,
        source=answer.source,
        range_label=label,
        notes=mentions,
        items=[
            Cited(
                id=uuid.UUID(e.item_id),
                day=e.day,
                kind=e.kind,
                text=e.text,
                owner=e.owner,
                counterpart=e.counterpart,
                due=e.due,
            )
            for e in cited
        ],
    )
