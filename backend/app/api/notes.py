"""Notes: the web app's library of recorded sessions, with folders, stars, archive and search.

    GET    /v1/notes                  search, filter by folder/star/day, sort, page
    GET    /v1/notes/{session_id}     one note with its items and (if allowed) transcript lines
    PATCH  /v1/notes/{session_id}     rename, move to a folder, star, archive
    DELETE /v1/notes/{session_id}     remove it from the account (the phone keeps its copy)
    POST   /v1/notes/bulk             the same change for up to 100 notes at once
    GET    /v1/folders                folders with counts, plus the built-in views' counts
    POST   /v1/folders                make a folder
    PATCH  /v1/folders/{id}           rename a folder
    DELETE /v1/folders/{id}           delete a folder (its notes become unfiled)

Notes are rebuilt from synced sessions (app/notes.py); this module only reads them and changes the
person's own organization. Nothing here deletes a recording on the phone: archiving hides, deleting
a folder only unfiles, and deleting a note removes only the server's copy. Transcript text is
searchable and shown only to tokens that carry `transcript:read`; everything is scoped to the
caller's own rows.
"""

import uuid
from collections.abc import Sequence
from datetime import date, datetime
from typing import Annotated, Any, Literal

from fastapi import APIRouter, Depends, Query, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator
from sqlalchemy import and_, case, delete, exists, func, or_, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.memory import ItemOut, _item_out, _like
from app.api.sync import purge_sessions
from app.auth import Principal, require
from app.db import get_session
from app.db.models import (
    DailyReportRow,
    DeletedNote,
    MemoryItem,
    MemoryItemCorrection,
    Note,
    NoteFolder,
)
from app.errors import ErrorResponse, error_response
from app.notes import UNTITLED, backfill_notes
from app.reports.synced import is_phone_document

router = APIRouter(tags=["notes"])

Session = Annotated[AsyncSession, Depends(get_session)]
Reader = Annotated[Principal, Depends(require("memory:read"))]
Writer = Annotated[Principal, Depends(require("memory:write"))]
_ERRORS: dict[int | str, dict[str, object]] = {
    s: {"model": ErrorResponse} for s in (401, 403, 404, 409, 422)
}

MAX_PAGE = 100
MAX_FOLDERS = 100
MAX_TERMS = 6
SNIPPET_RADIUS = 70
Sort = Literal["newest", "oldest", "title", "longest"]


class NoteOut(BaseModel):
    session_id: uuid.UUID
    memory_day: date
    started_at: datetime | None
    title: str
    renamed: bool
    preview: str
    # Where the search words were found, with a little context; only while searching.
    snippet: str | None
    item_count: int
    line_count: int
    word_count: int
    folder_id: uuid.UUID | None
    starred: bool
    archived: bool


class NotePage(BaseModel):
    notes: list[NoteOut]
    total: int


class TranscriptLineOut(BaseModel):
    start_epoch_ms: int
    speaker: str | None
    text: str


class NoteDetail(BaseModel):
    note: NoteOut
    items: list[ItemOut]
    # None when the token may not read transcripts; an empty list when the server holds none.
    lines: list[TranscriptLineOut] | None


class FolderOut(BaseModel):
    id: uuid.UUID
    name: str
    notes: int


class Totals(BaseModel):
    all: int
    starred: int
    unfiled: int
    archived: int


class FolderList(BaseModel):
    folders: list[FolderOut]
    totals: Totals


class FolderIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str = Field(min_length=1, max_length=60)

    @field_validator("name")
    @classmethod
    def _tidy(cls, value: str) -> str:
        tidy = " ".join(value.split())
        if not tidy:
            raise ValueError("name is blank")
        return tidy


class NotePatch(BaseModel):
    """Only the fields present change. An explicit null folder unfiles; a blank title restores
    the automatic one."""

    model_config = ConfigDict(extra="forbid")

    title: str | None = Field(default=None, max_length=120)
    folder_id: uuid.UUID | None = None
    starred: bool | None = None
    archived: bool | None = None


class BulkPatch(NotePatch):
    session_ids: list[uuid.UUID] = Field(min_length=1, max_length=MAX_PAGE)


class BulkResult(BaseModel):
    updated: int


# Decisions and commitments first, the same order a note's title is picked in.
_KIND_RANK = case(
    (MemoryItem.kind == "decision", 0),
    (MemoryItem.kind == "commitment", 1),
    (MemoryItem.kind == "action", 2),
    (MemoryItem.kind == "idea", 3),
    else_=4,
)


def _can_read_transcripts(principal: Principal) -> bool:
    return "transcript:read" in principal.scopes


async def _ensure_notes(session: AsyncSession, user_id: uuid.UUID) -> None:
    """Builds notes once for someone who synced before notes existed."""
    if await session.scalar(select(Note.id).where(Note.user_id == user_id).limit(1)) is None:
        await backfill_notes(session, user_id)


def _terms(q: str | None) -> list[str]:
    return [t for t in (q or "").split() if t][:MAX_TERMS]


def _term_matches(term: str, *, transcripts: bool) -> Any:
    pattern = _like(term)
    in_items = exists(
        select(1)
        .select_from(MemoryItem)
        .outerjoin(MemoryItemCorrection, MemoryItemCorrection.memory_item_id == MemoryItem.id)
        .where(
            MemoryItem.user_id == Note.user_id,
            MemoryItem.session_id == Note.session_id,
            MemoryItem.dismissed_at.is_(None),
            or_(
                MemoryItem.text.ilike(pattern, escape="\\"),
                MemoryItem.owner.ilike(pattern, escape="\\"),
                MemoryItem.counterpart.ilike(pattern, escape="\\"),
                MemoryItemCorrection.text.ilike(pattern, escape="\\"),
            ),
        )
    )
    parts = [
        Note.custom_title.ilike(pattern, escape="\\"),
        Note.phone_title.ilike(pattern, escape="\\"),
        in_items,
    ]
    if transcripts:
        parts += [
            Note.auto_title.ilike(pattern, escape="\\"),
            Note.transcript_text.ilike(pattern, escape="\\"),
        ]
    else:
        # A title made of spoken words is spoken content: not searchable without the scope.
        parts.append(
            and_(Note.title_from_transcript.is_(False), Note.auto_title.ilike(pattern, escape="\\"))
        )
    return or_(*parts)


def _snippet(text: str, terms: Sequence[str]) -> str | None:
    """A few words of context around the first search word, cut at word boundaries. Lines of a
    transcript are separated by a dot so they don't read as one run-on sentence."""
    flat = " · ".join(part for part in (line.strip() for line in text.split("\n")) if part)
    lowered = flat.lower()
    for term in terms:
        at = lowered.find(term.lower())
        if at < 0:
            continue
        match_end = at + len(term)
        start = max(0, at - SNIPPET_RADIUS)
        end = min(len(flat), match_end + SNIPPET_RADIUS)
        if start:  # begin at a whole word, never inside the match
            space = flat.find(" ", start, at)
            start = space + 1 if space != -1 else start
        if end < len(flat):  # and end at one
            space = flat.rfind(" ", match_end, end)
            end = space if space != -1 else end
        return ("…" if start else "") + flat[start:end] + ("…" if end < len(flat) else "")
    return None


def _title(note: Note, transcripts: bool) -> str:
    if note.custom_title:
        return note.custom_title
    if note.phone_title:
        return note.phone_title
    if note.title_from_transcript and not transcripts:
        return UNTITLED
    return note.auto_title


def _out(note: Note, *, transcripts: bool, item_count: int, snippet: str | None = None) -> NoteOut:
    return NoteOut(
        session_id=note.session_id,
        memory_day=note.memory_day,
        started_at=note.started_at,
        title=_title(note, transcripts),
        renamed=bool(note.custom_title),
        preview=note.item_preview or (note.lead if transcripts else ""),
        snippet=snippet,
        item_count=item_count,
        line_count=note.line_count if transcripts else 0,
        word_count=note.word_count if transcripts else 0,
        folder_id=note.folder_id,
        starred=note.starred,
        archived=note.archived_at is not None,
    )


async def _item_counts(
    session: AsyncSession, user_id: uuid.UUID, session_ids: Sequence[uuid.UUID]
) -> dict[uuid.UUID, int]:
    if not session_ids:
        return {}
    rows = await session.execute(
        select(MemoryItem.session_id, func.count())
        .where(
            MemoryItem.user_id == user_id,
            MemoryItem.session_id.in_(session_ids),
            MemoryItem.dismissed_at.is_(None),
        )
        .group_by(MemoryItem.session_id)
    )
    return {row[0]: int(row[1]) for row in rows.all()}


async def _items_for(
    session: AsyncSession, user_id: uuid.UUID, session_ids: Sequence[uuid.UUID]
) -> list[ItemOut]:
    rows = await session.execute(
        select(MemoryItem, MemoryItemCorrection.text)
        .outerjoin(MemoryItemCorrection, MemoryItemCorrection.memory_item_id == MemoryItem.id)
        .where(MemoryItem.user_id == user_id, MemoryItem.session_id.in_(session_ids))
        .order_by(_KIND_RANK, MemoryItem.created_at, MemoryItem.id)
    )
    return [_item_out(item, corrected) for item, corrected in rows.all()]


@router.get("/notes", response_model=NotePage, responses=_ERRORS)
async def list_notes(
    session: Session,
    reader: Reader,
    q: Annotated[str | None, Query(max_length=200)] = None,
    folder: Annotated[str | None, Query(pattern=r"^(none|[0-9a-fA-F-]{36})$")] = None,
    starred: bool = False,
    archived: bool = False,
    day: date | None = None,
    start: Annotated[date | None, Query(alias="from")] = None,
    end: Annotated[date | None, Query(alias="to")] = None,
    sort: Sort = "newest",
    limit: Annotated[int, Query(ge=1, le=MAX_PAGE)] = 30,
    offset: Annotated[int, Query(ge=0, le=100_000)] = 0,
) -> NotePage | JSONResponse:
    transcripts = _can_read_transcripts(reader)
    await _ensure_notes(session, reader.user_id)
    conditions: list[Any] = [Note.user_id == reader.user_id]
    conditions.append(Note.archived_at.is_not(None) if archived else Note.archived_at.is_(None))
    if starred:
        conditions.append(Note.starred.is_(True))
    if folder == "none":
        conditions.append(Note.folder_id.is_(None))
    elif folder is not None:
        try:
            conditions.append(Note.folder_id == uuid.UUID(folder))
        except ValueError:
            return error_response(422, "That folder id is not valid.", code="invalid_folder")
    if day is not None:
        conditions.append(Note.memory_day == day)
    if start is not None:
        conditions.append(Note.memory_day >= start)
    if end is not None:
        conditions.append(Note.memory_day <= end)
    terms = _terms(q)
    conditions.extend(_term_matches(t, transcripts=transcripts) for t in terms)

    name = func.lower(func.coalesce(Note.custom_title, Note.phone_title, Note.auto_title))
    order = {
        "newest": (
            Note.memory_day.desc(),
            Note.started_at.desc().nulls_last(),
            Note.created_at.desc(),
        ),
        "oldest": (
            Note.memory_day.asc(),
            Note.started_at.asc().nulls_last(),
            Note.created_at.asc(),
        ),
        "title": (name.asc(), Note.memory_day.desc()),
        "longest": (Note.word_count.desc(), Note.memory_day.desc()),
    }[sort]
    total = await session.scalar(select(func.count()).select_from(Note).where(*conditions))
    notes = (
        await session.scalars(
            select(Note).where(*conditions).order_by(*order, Note.id).limit(limit).offset(offset)
        )
    ).all()
    ids = [n.session_id for n in notes]
    counts = await _item_counts(session, reader.user_id, ids)
    item_text: dict[uuid.UUID, list[str]] = {}
    if terms and ids:
        for item in await _items_for(session, reader.user_id, ids):
            if not item.dismissed:
                item_text.setdefault(item.session_id, []).append(item.text)
    out = []
    for note in notes:
        snippet = None
        if terms:
            if transcripts:
                snippet = _snippet(note.transcript_text, terms)
            for text in item_text.get(note.session_id, []):
                snippet = snippet or _snippet(text, terms)
        out.append(
            _out(
                note,
                transcripts=transcripts,
                item_count=counts.get(note.session_id, 0),
                snippet=snippet,
            )
        )
    return NotePage(notes=out, total=total or 0)


async def _owned_note(
    session: AsyncSession, user_id: uuid.UUID, session_id: uuid.UUID
) -> Note | None:
    return await session.scalar(
        select(Note).where(Note.user_id == user_id, Note.session_id == session_id)
    )


async def _lines(session: AsyncSession, note: Note) -> list[TranscriptLineOut]:
    report = await session.scalar(
        select(DailyReportRow).where(
            DailyReportRow.user_id == note.user_id, DailyReportRow.memory_day == note.memory_day
        )
    )
    if report is None or not is_phone_document(report.report):
        return []
    stored = report.report.get("transcriptLines")
    found: list[TranscriptLineOut] = []
    for line in stored if isinstance(stored, list) else []:
        if not isinstance(line, dict) or line.get("sessionId") != str(note.session_id):
            continue
        text = line.get("text")
        start = line.get("startEpochMs")
        if isinstance(text, str) and isinstance(start, int):
            speaker = line.get("speaker")
            found.append(
                TranscriptLineOut(
                    start_epoch_ms=start,
                    speaker=speaker if isinstance(speaker, str) else None,
                    text=text,
                )
            )
    return sorted(found, key=lambda line: line.start_epoch_ms)


@router.get("/notes/{session_id}", response_model=NoteDetail, responses=_ERRORS)
async def get_note(
    session_id: uuid.UUID, session: Session, reader: Reader
) -> NoteDetail | JSONResponse:
    await _ensure_notes(session, reader.user_id)
    note = await _owned_note(session, reader.user_id, session_id)
    if note is None:
        return error_response(404, "No such note.", code="note_not_found")
    transcripts = _can_read_transcripts(reader)
    items = await _items_for(session, reader.user_id, [session_id])
    live = [i for i in items if not i.dismissed]
    return NoteDetail(
        note=_out(note, transcripts=transcripts, item_count=len(live)),
        items=items,
        lines=await _lines(session, note) if transcripts else None,
    )


async def _folder_belongs(session: AsyncSession, user_id: uuid.UUID, folder_id: uuid.UUID) -> bool:
    return (
        await session.scalar(
            select(NoteFolder.id).where(NoteFolder.id == folder_id, NoteFolder.user_id == user_id)
        )
    ) is not None


def _changes(body: NotePatch) -> dict[str, Any]:
    changes: dict[str, Any] = {}
    sent = body.model_fields_set
    if "title" in sent:
        title = " ".join((body.title or "").split())
        changes["custom_title"] = title or None
    if "folder_id" in sent:
        changes["folder_id"] = body.folder_id
    if body.starred is not None:
        changes["starred"] = body.starred
    if body.archived is not None:
        changes["archived_at"] = func.now() if body.archived else None
    return changes


@router.patch("/notes/{session_id}", response_model=NoteOut, responses=_ERRORS)
async def patch_note(
    session_id: uuid.UUID, body: NotePatch, session: Session, writer: Writer
) -> NoteOut | JSONResponse:
    note = await _owned_note(session, writer.user_id, session_id)
    if note is None:
        return error_response(404, "No such note.", code="note_not_found")
    if body.folder_id is not None and not await _folder_belongs(
        session, writer.user_id, body.folder_id
    ):
        return error_response(404, "No such folder.", code="folder_not_found")
    changes = _changes(body)
    if changes:
        await session.execute(
            update(Note).where(Note.id == note.id, Note.user_id == writer.user_id).values(**changes)
        )
        await session.commit()
        await session.refresh(note)
    counts = await _item_counts(session, writer.user_id, [session_id])
    return _out(
        note, transcripts=_can_read_transcripts(writer), item_count=counts.get(session_id, 0)
    )


@router.delete("/notes/{session_id}", status_code=204, response_model=None, responses=_ERRORS)
async def delete_note(
    session_id: uuid.UUID, request: Request, session: Session, writer: Writer
) -> Response | JSONResponse:
    """Removes the note, its highlights and transcript from the account, and remembers the id so
    the phone, which keeps its own copy, does not send it again."""
    if await _owned_note(session, writer.user_id, session_id) is None:
        return error_response(404, "No such note.", code="note_not_found")
    store = getattr(request.app.state, "object_store", None)
    await purge_sessions(session, store, writer.user_id, [session_id])
    await session.execute(
        pg_insert(DeletedNote)
        .values(user_id=writer.user_id, session_id=session_id)
        .on_conflict_do_nothing()
    )
    await session.commit()
    return Response(status_code=204)


@router.post("/notes/bulk", response_model=BulkResult, responses=_ERRORS)
async def bulk_patch(
    body: BulkPatch, session: Session, writer: Writer
) -> BulkResult | JSONResponse:
    if body.folder_id is not None and not await _folder_belongs(
        session, writer.user_id, body.folder_id
    ):
        return error_response(404, "No such folder.", code="folder_not_found")
    if "title" in body.model_fields_set:
        return error_response(422, "Rename one note at a time.", code="bulk_rename")
    changes = _changes(body)
    if not changes:
        return BulkResult(updated=0)
    result = await session.execute(
        update(Note)
        .where(Note.user_id == writer.user_id, Note.session_id.in_(body.session_ids))
        .values(**changes)
    )
    await session.commit()
    return BulkResult(updated=result.rowcount or 0)  # type: ignore[attr-defined]


@router.get("/folders", response_model=FolderList, responses=_ERRORS)
async def list_folders(session: Session, reader: Reader) -> FolderList:
    await _ensure_notes(session, reader.user_id)
    live = and_(Note.user_id == reader.user_id, Note.archived_at.is_(None))
    per_folder = dict(
        (
            await session.execute(
                select(Note.folder_id, func.count()).where(live).group_by(Note.folder_id)
            )
        ).all()
    )
    folders = (
        await session.scalars(
            select(NoteFolder)
            .where(NoteFolder.user_id == reader.user_id)
            .order_by(func.lower(NoteFolder.name))
        )
    ).all()
    starred = await session.scalar(
        select(func.count()).select_from(Note).where(live, Note.starred.is_(True))
    )
    archived = await session.scalar(
        select(func.count())
        .select_from(Note)
        .where(Note.user_id == reader.user_id, Note.archived_at.is_not(None))
    )
    return FolderList(
        folders=[FolderOut(id=f.id, name=f.name, notes=per_folder.get(f.id, 0)) for f in folders],
        totals=Totals(
            all=sum(per_folder.values()),
            starred=starred or 0,
            unfiled=per_folder.get(None, 0),
            archived=archived or 0,
        ),
    )


async def _name_taken(
    session: AsyncSession, user_id: uuid.UUID, name: str, *, except_id: uuid.UUID | None = None
) -> bool:
    query = select(NoteFolder.id).where(
        NoteFolder.user_id == user_id, func.lower(NoteFolder.name) == name.lower()
    )
    if except_id is not None:
        query = query.where(NoteFolder.id != except_id)
    return (await session.scalar(query.limit(1))) is not None


@router.post("/folders", response_model=FolderOut, status_code=201, responses=_ERRORS)
async def create_folder(
    body: FolderIn, session: Session, writer: Writer
) -> FolderOut | JSONResponse:
    count = await session.scalar(
        select(func.count()).select_from(NoteFolder).where(NoteFolder.user_id == writer.user_id)
    )
    if (count or 0) >= MAX_FOLDERS:
        return error_response(
            409, f"You can have up to {MAX_FOLDERS} folders.", code="too_many_folders"
        )
    if await _name_taken(session, writer.user_id, body.name):
        return error_response(
            409, "You already have a folder with that name.", code="folder_exists"
        )
    folder = NoteFolder(user_id=writer.user_id, name=body.name)
    session.add(folder)
    try:
        await session.commit()
    except IntegrityError:
        # Two creations of the same name raced; the unique index decided.
        await session.rollback()
        return error_response(
            409, "You already have a folder with that name.", code="folder_exists"
        )
    return FolderOut(id=folder.id, name=folder.name, notes=0)


async def _owned_folder(
    session: AsyncSession, user_id: uuid.UUID, folder_id: uuid.UUID
) -> NoteFolder | None:
    return await session.scalar(
        select(NoteFolder).where(NoteFolder.id == folder_id, NoteFolder.user_id == user_id)
    )


@router.patch("/folders/{folder_id}", response_model=FolderOut, responses=_ERRORS)
async def rename_folder(
    folder_id: uuid.UUID, body: FolderIn, session: Session, writer: Writer
) -> FolderOut | JSONResponse:
    folder = await _owned_folder(session, writer.user_id, folder_id)
    if folder is None:
        return error_response(404, "No such folder.", code="folder_not_found")
    if await _name_taken(session, writer.user_id, body.name, except_id=folder_id):
        return error_response(
            409, "You already have a folder with that name.", code="folder_exists"
        )
    folder.name = body.name
    try:
        await session.commit()
    except IntegrityError:
        await session.rollback()
        return error_response(
            409, "You already have a folder with that name.", code="folder_exists"
        )
    notes = await session.scalar(
        select(func.count())
        .select_from(Note)
        .where(
            Note.user_id == writer.user_id, Note.folder_id == folder_id, Note.archived_at.is_(None)
        )
    )
    return FolderOut(id=folder.id, name=folder.name, notes=notes or 0)


@router.delete("/folders/{folder_id}", status_code=204, response_model=None, responses=_ERRORS)
async def delete_folder(
    folder_id: uuid.UUID, session: Session, writer: Writer
) -> Response | JSONResponse:
    if await _owned_folder(session, writer.user_id, folder_id) is None:
        return error_response(404, "No such folder.", code="folder_not_found")
    # The foreign key sets each note's folder to null; the notes themselves stay.
    await session.execute(
        delete(NoteFolder).where(NoteFolder.id == folder_id, NoteFolder.user_id == writer.user_id)
    )
    await session.commit()
    return Response(status_code=204)
