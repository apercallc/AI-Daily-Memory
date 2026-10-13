"""Self-service export of everything the server holds about you (a right, not an upsell).

    GET /v1/export   one JSON document: account, memory items with your corrections, folders and
                     how you organized each note, daily reports, transcripts the server holds,
                     sign-ins and clip metadata

Streamed in batches so a large account never sits in memory whole. Raw audio is temporary
(ADR-0003) and is not inlined; the clip list says what is still held. Sealed phone-mode
transcripts are not readable by the server and so are not included: they live on the phone.
"""

import json
import uuid
from collections.abc import AsyncIterator
from datetime import UTC, datetime
from typing import Annotated, Any

from fastapi import APIRouter, Depends
from fastapi.responses import StreamingResponse
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.auth_rate_limit import auth_rate_limit
from app.credentials import email_of
from app.db import get_session
from app.db.models import (
    AccessToken,
    AudioUpload,
    DailyReportRow,
    MemoryItem,
    MemoryItemCorrection,
    Note,
    NoteFolder,
    Transcript,
    TranscriptSegment,
    User,
)
from app.errors import ErrorResponse

router = APIRouter(tags=["export"])

Session = Annotated[AsyncSession, Depends(get_session)]
Owner = Annotated[Principal, Depends(require("upload:write", "account:manage"))]
_ERRORS: dict[int | str, dict[str, object]] = {s: {"model": ErrorResponse} for s in (401, 403, 429)}
BATCH = 500


def _dump(value: object) -> str:
    return json.dumps(value, default=str, ensure_ascii=False, separators=(",", ":"))


async def _array(
    name: str, rows: AsyncIterator[dict[str, Any]], *, first: bool = False
) -> AsyncIterator[str]:
    yield ("" if first else ",") + _dump(name) + ":["
    separator = ""
    async for row in rows:
        yield separator + _dump(row)
        separator = ","
    yield "]"


async def _items(session: AsyncSession, user_id: uuid.UUID) -> AsyncIterator[dict[str, Any]]:
    last: uuid.UUID | None = None
    while True:
        query = (
            select(MemoryItem, MemoryItemCorrection.text)
            .outerjoin(MemoryItemCorrection, MemoryItemCorrection.memory_item_id == MemoryItem.id)
            .where(MemoryItem.user_id == user_id)
            .order_by(MemoryItem.id)
            .limit(BATCH)
        )
        if last is not None:
            query = query.where(MemoryItem.id > last)
        rows = (await session.execute(query)).all()
        if not rows:
            return
        for item, correction in rows:
            yield {
                "id": item.id,
                "session_id": item.session_id,
                "day": item.memory_day,
                "kind": item.kind,
                "text": item.text,
                "your_text": correction,
                "confidence": item.confidence,
                "owner": item.owner,
                "counterpart": item.counterpart,
                "direction": item.direction,
                "due": item.due,
                "dismissed": item.dismissed_at is not None,
                "extraction_model": item.extraction_model,
                "created_at": item.created_at,
            }
        last = rows[-1][0].id


async def _reports(session: AsyncSession, user_id: uuid.UUID) -> AsyncIterator[dict[str, Any]]:
    for row in await session.scalars(
        select(DailyReportRow)
        .where(DailyReportRow.user_id == user_id)
        .order_by(DailyReportRow.memory_day)
    ):
        yield {"day": row.memory_day, "status": row.status, "report": row.report}


async def _clips(session: AsyncSession, user_id: uuid.UUID) -> AsyncIterator[dict[str, Any]]:
    for row in await session.scalars(
        select(AudioUpload).where(AudioUpload.user_id == user_id).order_by(AudioUpload.captured_at)
    ):
        yield {
            "id": row.id,
            "session_id": row.session_id,
            "captured_at": row.captured_at,
            "duration_ms": row.duration_ms,
            "state": row.state,
            "audio_still_held": row.state in ("pending", "received"),
        }


async def _transcripts(session: AsyncSession, user_id: uuid.UUID) -> AsyncIterator[dict[str, Any]]:
    rows = await session.execute(
        select(Transcript, AudioUpload.captured_at, AudioUpload.active_transcript_id)
        .join(AudioUpload, AudioUpload.id == Transcript.upload_id)
        .where(Transcript.user_id == user_id)
        .order_by(AudioUpload.captured_at, Transcript.revision)
    )
    for transcript, captured_at, active_id in rows.all():
        segments = await session.scalars(
            select(TranscriptSegment)
            .where(TranscriptSegment.transcript_id == transcript.id)
            .order_by(TranscriptSegment.start_ms)
        )
        yield {
            "upload_id": transcript.upload_id,
            "captured_at": captured_at,
            "revision": transcript.revision,
            "active": transcript.id == active_id,
            "provider": transcript.provider,
            "segments": [
                {"start_ms": s.start_ms, "end_ms": s.end_ms, "speaker": s.speaker, "text": s.text}
                for s in segments
            ],
        }


async def _folders(session: AsyncSession, user_id: uuid.UUID) -> AsyncIterator[dict[str, Any]]:
    for folder in await session.scalars(
        select(NoteFolder).where(NoteFolder.user_id == user_id).order_by(NoteFolder.created_at)
    ):
        yield {"id": folder.id, "name": folder.name, "created_at": folder.created_at}


async def _notes(session: AsyncSession, user_id: uuid.UUID) -> AsyncIterator[dict[str, Any]]:
    # How the person organized each session. The words themselves are in the reports and
    # transcripts above; this adds their titles, folders, stars and archive state.
    for note in await session.scalars(
        select(Note).where(Note.user_id == user_id).order_by(Note.memory_day, Note.created_at)
    ):
        yield {
            "session_id": note.session_id,
            "memory_day": note.memory_day,
            "started_at": note.started_at,
            "title": note.custom_title or note.phone_title or note.auto_title,
            "renamed": note.custom_title is not None,
            "folder_id": note.folder_id,
            "starred": note.starred,
            "archived_at": note.archived_at,
        }


async def _tokens(session: AsyncSession, user_id: uuid.UUID) -> AsyncIterator[dict[str, Any]]:
    for row in await session.scalars(
        select(AccessToken).where(AccessToken.user_id == user_id).order_by(AccessToken.created_at)
    ):
        # Metadata only: the secret is never stored, so it cannot be exported.
        yield {
            "kind": row.kind,
            "name": row.name,
            "scopes": row.scopes,
            "created_at": row.created_at,
            "last_used_at": row.last_used_at,
            "revoked_at": row.revoked_at,
        }


async def _document(session: AsyncSession, user_id: uuid.UUID) -> AsyncIterator[str]:
    user = await session.get(User, user_id)
    email = await email_of(session, user_id)
    yield "{" + _dump("exported_at") + ":" + _dump(datetime.now(UTC)) + ","
    yield (
        _dump("account")
        + ":"
        + _dump(
            {
                "email": email,
                "storage_mode": user.storage_mode if user else None,
                "created_at": user.created_at if user else None,
            }
        )
    )
    for name, rows in (
        ("memory_items", _items(session, user_id)),
        ("note_folders", _folders(session, user_id)),
        ("notes", _notes(session, user_id)),
        ("daily_reports", _reports(session, user_id)),
        ("transcripts", _transcripts(session, user_id)),
        ("clips", _clips(session, user_id)),
        ("sign_ins", _tokens(session, user_id)),
    ):
        async for chunk in _array(name, rows):
            yield chunk
    yield "}"


@router.get(
    "/export",
    responses=_ERRORS,
    dependencies=[Depends(auth_rate_limit("export", limit=3, window_seconds=3_600))],
)
async def export(session: Session, owner: Owner) -> StreamingResponse:
    filename = f"ai-daily-memory-export-{datetime.now(UTC).date().isoformat()}.json"
    return StreamingResponse(
        _document(session, owner.user_id),
        media_type="application/json",
        headers={
            "Content-Disposition": f'attachment; filename="{filename}"',
            "Cache-Control": "no-store",
        },
    )
