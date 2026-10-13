"""Browsing and correcting memory (web and API clients).

    GET   /v1/memory/days                     days that have memory, newest first
    GET   /v1/memory/items                    items, filterable by day range, kind and text
    PATCH /v1/memory/items/{id}               dismiss or restore an item, or set/clear your wording
    GET   /v1/memory/items/{id}/evidence      the transcript lines an item cites

Evidence is immutable and derived output is always correctable (AGENTS.md): a correction is
stored beside the item, never over it, and dismissing hides an item without deleting what it
cites. Every query is scoped to the caller's own rows.
"""

import uuid
from datetime import date
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Query
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import and_, case, delete, func, or_, select
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.db import get_session
from app.db.models import (
    DailyReportRow,
    MemoryItem,
    MemoryItemCorrection,
    MemoryItemEvidence,
    TranscriptSegment,
)
from app.errors import ErrorResponse, error_response

router = APIRouter(tags=["memory"])

Session = Annotated[AsyncSession, Depends(get_session)]
Reader = Annotated[Principal, Depends(require("memory:read"))]
Writer = Annotated[Principal, Depends(require("memory:write"))]
TranscriptReader = Annotated[Principal, Depends(require("transcript:read"))]
_ERRORS: dict[int | str, dict[str, object]] = {
    s: {"model": ErrorResponse} for s in (401, 403, 404, 422)
}

Kind = Literal["decision", "action", "commitment", "idea", "question"]
MAX_PAGE = 100


class ItemOut(BaseModel):
    id: uuid.UUID
    session_id: uuid.UUID
    memory_day: date
    kind: str
    # What the person sees: their own wording when they corrected it, else the extracted text.
    text: str
    original_text: str
    corrected: bool
    confidence: str
    owner: str | None
    counterpart: str | None
    direction: str | None
    due: date | None
    dismissed: bool


class ItemPage(BaseModel):
    items: list[ItemOut]
    total: int


class DayOut(BaseModel):
    day: date
    items: int
    has_report: bool


class ItemPatch(BaseModel):
    model_config = ConfigDict(extra="forbid")

    dismissed: bool | None = None
    # A string sets the person's wording; an explicit null restores the extracted text.
    text: str | None = Field(default=None, min_length=1, max_length=280)
    clear_text: bool = False


class EvidenceOut(BaseModel):
    quote: str | None
    text: str | None
    speaker: str | None
    start_ms: int | None


def _item_out(item: MemoryItem, correction: str | None) -> ItemOut:
    return ItemOut(
        id=item.id,
        session_id=item.session_id,
        memory_day=item.memory_day,
        kind=item.kind,
        text=correction or item.text,
        original_text=item.text,
        corrected=correction is not None,
        confidence=item.confidence,
        owner=item.owner,
        counterpart=item.counterpart,
        direction=item.direction,
        due=item.due,
        dismissed=item.dismissed_at is not None,
    )


def _like(q: str) -> str:
    escaped = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return f"%{escaped}%"


@router.get("/memory/days", response_model=list[DayOut], responses=_ERRORS)
async def list_days(
    session: Session,
    reader: Reader,
    limit: Annotated[int, Query(ge=1, le=366)] = 90,
) -> list[DayOut]:
    rows = (
        await session.execute(
            select(
                MemoryItem.memory_day,
                func.count().filter(MemoryItem.dismissed_at.is_(None)).label("items"),
            )
            .where(MemoryItem.user_id == reader.user_id)
            .group_by(MemoryItem.memory_day)
            .order_by(MemoryItem.memory_day.desc())
            .limit(limit),
        )
    ).all()
    report_days = set(
        (
            await session.scalars(
                select(DailyReportRow.memory_day).where(DailyReportRow.user_id == reader.user_id),
            )
        ).all()
    )
    return [
        DayOut(day=r.memory_day, items=r.items, has_report=r.memory_day in report_days)
        for r in rows
    ]


@router.get("/memory/items", response_model=ItemPage, responses=_ERRORS)
async def list_items(
    session: Session,
    reader: Reader,
    day: date | None = None,
    start: Annotated[date | None, Query(alias="from")] = None,
    end: Annotated[date | None, Query(alias="to")] = None,
    kind: Kind | None = None,
    q: Annotated[str | None, Query(max_length=200)] = None,
    include_dismissed: bool = False,
    limit: Annotated[int, Query(ge=1, le=MAX_PAGE)] = 50,
    offset: Annotated[int, Query(ge=0, le=100_000)] = 0,
) -> ItemPage:
    conditions = [MemoryItem.user_id == reader.user_id]
    if day is not None:
        conditions.append(MemoryItem.memory_day == day)
    if start is not None:
        conditions.append(MemoryItem.memory_day >= start)
    if end is not None:
        conditions.append(MemoryItem.memory_day <= end)
    if kind is not None:
        conditions.append(MemoryItem.kind == kind)
    if not include_dismissed:
        conditions.append(MemoryItem.dismissed_at.is_(None))
    if q and q.strip():
        pattern = _like(q.strip())
        conditions.append(
            or_(
                MemoryItem.text.ilike(pattern, escape="\\"),
                MemoryItem.owner.ilike(pattern, escape="\\"),
                MemoryItem.counterpart.ilike(pattern, escape="\\"),
                MemoryItemCorrection.text.ilike(pattern, escape="\\"),
            )
        )
    base = (
        select(MemoryItem, MemoryItemCorrection.text)
        .outerjoin(MemoryItemCorrection, MemoryItemCorrection.memory_item_id == MemoryItem.id)
        .where(and_(*conditions))
    )
    total = await session.scalar(
        select(func.count()).select_from(base.subquery()),
    )
    rows = (
        await session.execute(
            base.order_by(
                MemoryItem.memory_day.desc(),
                case(
                    (MemoryItem.kind == "decision", 0),
                    (MemoryItem.kind == "commitment", 1),
                    else_=2,
                ),
                MemoryItem.created_at.desc(),
            )
            .limit(limit)
            .offset(offset),
        )
    ).all()
    return ItemPage(
        items=[_item_out(item, corrected) for item, corrected in rows], total=total or 0
    )


async def _owned(
    session: AsyncSession, item_id: uuid.UUID, user_id: uuid.UUID
) -> MemoryItem | None:
    return await session.scalar(
        select(MemoryItem).where(MemoryItem.id == item_id, MemoryItem.user_id == user_id),
    )


@router.patch("/memory/items/{item_id}", response_model=ItemOut, responses=_ERRORS)
async def patch_item(
    item_id: uuid.UUID, body: ItemPatch, session: Session, writer: Writer
) -> ItemOut | JSONResponse:
    item = await _owned(session, item_id, writer.user_id)
    if item is None:
        return error_response(404, "No such memory item.", code="item_not_found")
    if body.dismissed is not None:
        item.dismissed_at = func.now() if body.dismissed else None
    if body.clear_text:
        await session.execute(
            delete(MemoryItemCorrection).where(MemoryItemCorrection.memory_item_id == item.id)
        )
    elif body.text is not None:
        statement = pg_insert(MemoryItemCorrection).values(
            user_id=writer.user_id, memory_item_id=item.id, text=body.text.strip()
        )
        await session.execute(
            statement.on_conflict_do_update(
                index_elements=[MemoryItemCorrection.memory_item_id],
                set_={"text": statement.excluded.text},
            )
        )
    await session.commit()
    await session.refresh(item)
    correction = await session.scalar(
        select(MemoryItemCorrection.text).where(MemoryItemCorrection.memory_item_id == item.id)
    )
    return _item_out(item, correction)


@router.get("/memory/items/{item_id}/evidence", response_model=list[EvidenceOut], responses=_ERRORS)
async def item_evidence(
    item_id: uuid.UUID, session: Session, reader: TranscriptReader
) -> list[EvidenceOut] | JSONResponse:
    if await _owned(session, item_id, reader.user_id) is None:
        return error_response(404, "No such memory item.", code="item_not_found")
    rows = (
        await session.execute(
            select(
                MemoryItemEvidence.quote,
                TranscriptSegment.text,
                TranscriptSegment.speaker,
                TranscriptSegment.start_ms,
            )
            .join(
                TranscriptSegment, TranscriptSegment.id == MemoryItemEvidence.transcript_segment_id
            )
            .where(
                MemoryItemEvidence.memory_item_id == item_id,
                MemoryItemEvidence.user_id == reader.user_id,
            )
            .order_by(TranscriptSegment.start_ms),
        )
    ).all()
    return [
        EvidenceOut(quote=r.quote, text=r.text, speaker=r.speaker, start_ms=r.start_ms)
        for r in rows
    ]
