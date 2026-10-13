"""Sync endpoint for on-device processed memory (ADR-0020).

    POST /v1/sync   accept a SyncPayload from the phone, upsert reports + memory items

The phone processes audio on-device (transcription, extraction, report generation) and
pushes structured memory to this service for user-authorized API clients to query. The backend
never processes audio in this flow — it is a sync target and query service, not a processor.
Transcript lines are stored only when the phone explicitly
sets `includeTranscripts=true`.

Idempotent per session: syncing a day replaces the memory items (and transcript lines) of the
sessions present in the payload and leaves every other session's data for that day alone, so
a second device or a partial resync never wipes what it did not send. The day's stored report
is merged the same way (see `app.reports.synced` for how it is read back).
"""

import logging
import re
import uuid
from datetime import date
from typing import Annotated, Any

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, model_validator
from sqlalchemy import Text, cast, delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.db import get_session
from app.db.models import (
    AudioUpload,
    CommitmentDirection,
    Confidence,
    DailyReportRow,
    DeletedNote,
    MemoryItem,
    MemoryItemKind,
    Note,
    ProcessingJob,
)
from app.errors import ErrorResponse
from app.notes import SessionContent, forget_transcripts, upsert_note
from app.reports.daily import ZoneName
from app.reports.synced import is_phone_document
from app.storage import ObjectStore

logger = logging.getLogger("app.sync")

router = APIRouter(tags=["sync"])

Session = Annotated[AsyncSession, Depends(get_session)]
Writer = Annotated[Principal, Depends(require("upload:write"))]
_ERRORS: dict[int | str, dict[str, object]] = {
    s: {"model": ErrorResponse} for s in (401, 403, 422, 503)
}

# Caps match the String(n) columns and the v1 report contract (app/reports/daily.py), so an
# over-long value is a 422 with a field location instead of being silently truncated.
_MAX_REPORTS = 31
_ID_PATTERN = re.compile(r"[0-9a-fA-F-]{36}")


class SyncItemIn(BaseModel):
    """One memory item, mirroring the phone's contract (core `SyncItem`) and the DB CHECK
    constraints, so an out-of-contract item is a 422 with a field location — never a 500."""

    model_config = ConfigDict(populate_by_name=True)

    kind: MemoryItemKind
    text: str = Field(min_length=1, max_length=280)
    confidence: Confidence
    quote: str | None = Field(default=None, max_length=280)
    owner: str | None = Field(default=None, max_length=80)
    due: date | None = None
    direction: CommitmentDirection | None = None
    counterpart: str | None = Field(default=None, max_length=80)
    session_id: uuid.UUID = Field(alias="sessionId")
    evidence_epoch_ms: int | None = Field(default=None, alias="evidenceEpochMs", ge=0)

    @model_validator(mode="after")
    def _commitment_has_direction(self) -> "SyncItemIn":
        # Same rule as the `commitment_has_direction` CHECK on memory_items.
        if self.kind == "commitment" and self.direction is None:
            raise ValueError("direction is required for commitment items")
        return self


class SyncCoverageIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    recorded_ms: int = Field(alias="recordedMs", ge=0)
    speech_ms: int = Field(alias="speechMs", ge=0)
    transcribed_ms: int = Field(alias="transcribedMs", ge=0)
    sessions: int = Field(ge=0)


class SyncTranscriptLineIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    session_id: str = Field(alias="sessionId", min_length=1, max_length=128)
    start_epoch_ms: int = Field(alias="startEpochMs", ge=0)
    speaker: str | None = Field(default=None, max_length=80)
    text: str = Field(min_length=1, max_length=5_000)


class SyncSessionIn(BaseModel):
    """What the phone knows about one session besides its words: the name the person gave it and
    exactly when it started. Declaring a session lets the server own it even when no transcript or
    item is sent for it (transcript sync off)."""

    model_config = ConfigDict(populate_by_name=True, extra="ignore")

    session_id: uuid.UUID = Field(alias="sessionId")
    title: str | None = Field(default=None, max_length=120)
    started_at_epoch_ms: int | None = Field(default=None, alias="startedAtEpochMs", ge=0)


class SyncReportIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    memory_day: date = Field(alias="memoryDay")
    zone_id: ZoneName = Field(alias="zoneId", max_length=64)
    headline: str = Field(max_length=280)
    summary: str = Field(max_length=1_680)
    items: list[SyncItemIn] = Field(max_length=2_000)
    sessions: list[SyncSessionIn] = Field(default_factory=list, max_length=500)
    transcript_lines: list[SyncTranscriptLineIn] | None = Field(
        default=None,
        alias="transcriptLines",
        max_length=20_000,
    )
    coverage: SyncCoverageIn
    generated_at_epoch_ms: int = Field(alias="generatedAtEpochMs", ge=0)
    extraction_model: str = Field(alias="extractionModel", min_length=1, max_length=80)
    extraction_version: int = Field(alias="extractionVersion", ge=0)


class SyncPayloadIn(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    device: str = Field(min_length=1, max_length=80)
    generated_at_epoch_ms: int = Field(alias="generatedAtEpochMs", ge=0)
    reports: list[SyncReportIn] = Field(max_length=_MAX_REPORTS)
    include_transcripts: bool = Field(alias="includeTranscripts", default=False)
    deleted_sessions: list[uuid.UUID] = Field(
        default_factory=list,
        alias="deletedSessions",
        max_length=500,
    )


class SyncResult(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    synced: int
    items: int
    deleted_sessions: int = Field(alias="deletedSessions")


@router.post("/sync", response_model=SyncResult, responses=_ERRORS)
async def sync_memory(
    payload: SyncPayloadIn,
    request: Request,
    session: Session,
    writer: Writer,
) -> SyncResult:
    """Accepts on-device processed memory from the phone. Idempotent per session.

    `deletedSessions` propagates the phone's local deletions (ADR-0020, ADR-0004 amendment):
    everything derived from those sessions is removed here too — memory items, report content,
    and any server-held clips, transcripts and sealed deliveries. Repeating an id is a no-op.
    """
    store: ObjectStore | None = getattr(request.app.state, "object_store", None)
    await purge_sessions(session, store, writer.user_id, payload.deleted_sessions)
    await _drop_removed_sessions(session, writer.user_id, payload)

    total_items = 0
    for report in payload.reports:
        total_items += await _upsert_report(session, writer.user_id, report, payload)
    await session.commit()
    return SyncResult(
        synced=len(payload.reports),
        items=total_items,
        deletedSessions=len(payload.deleted_sessions),
    )


async def _drop_removed_sessions(
    session: AsyncSession, user_id: uuid.UUID, payload: SyncPayloadIn
) -> None:
    """Takes out of the payload every session the person removed from their account on the web,
    so a phone that still holds its own copy cannot bring the note back."""
    mentioned: set[uuid.UUID] = set()
    for report in payload.reports:
        mentioned |= {i.session_id for i in report.items}
        mentioned |= {s.session_id for s in report.sessions}
        mentioned |= {
            u for line in report.transcript_lines or [] if (u := _uuid_or_none(line.session_id))
        }
    if not mentioned:
        return
    gone = set(
        (
            await session.scalars(
                select(DeletedNote.session_id).where(
                    DeletedNote.user_id == user_id, DeletedNote.session_id.in_(mentioned)
                )
            )
        ).all()
    )
    if not gone:
        return
    for report in payload.reports:
        report.items = [i for i in report.items if i.session_id not in gone]
        report.sessions = [s for s in report.sessions if s.session_id not in gone]
        if report.transcript_lines is not None:
            report.transcript_lines = [
                line
                for line in report.transcript_lines
                if _uuid_or_none(line.session_id) not in gone
            ]


def _uuid_or_none(value: str) -> uuid.UUID | None:
    if not _ID_PATTERN.fullmatch(value):
        return None
    try:
        return uuid.UUID(value)
    except ValueError:
        return None


async def _upsert_report(
    session: AsyncSession,
    user_id: uuid.UUID,
    report: SyncReportIn,
    payload: SyncPayloadIn,
) -> int:
    memory_day = report.memory_day
    # Two first syncs for one day (two phones, or a retry racing the original) would both insert
    # the day's row; serialize them for the length of this transaction instead.
    await session.execute(
        select(
            func.pg_advisory_xact_lock(func.hashtextextended(f"sync:{user_id}:{memory_day}", 0))
        ),
    )
    item_sessions = {str(item.session_id) for item in report.items}
    line_sessions = {line.session_id for line in report.transcript_lines or []}
    # The sessions this payload speaks for. Everything else stored for the day is left alone.
    declared = {str(s.session_id) for s in report.sessions}
    owned_strings = item_sessions | line_sessions | declared
    owned_uuids = {u for s in owned_strings if (u := _uuid_or_none(s)) is not None}

    if owned_uuids:
        await session.execute(
            delete(MemoryItem).where(
                MemoryItem.user_id == user_id,
                MemoryItem.memory_day == memory_day,
                MemoryItem.session_id.in_(owned_uuids),
            ),
        )
    for item in report.items:
        session.add(
            MemoryItem(
                user_id=user_id,
                session_id=item.session_id,
                memory_day=memory_day,
                kind=item.kind,
                text=item.text,
                confidence=item.confidence,
                owner=item.owner,
                counterpart=item.counterpart,
                direction=item.direction,
                due=item.due,
                extraction_model=report.extraction_model,
                extraction_version=report.extraction_version,
            ),
        )
    await session.flush()
    if not payload.include_transcripts:
        await forget_transcripts(session, user_id, memory_day)
    await _refresh_notes(session, user_id, report, payload)

    existing = await session.scalar(
        select(DailyReportRow)
        .where(DailyReportRow.user_id == user_id, DailyReportRow.memory_day == memory_day)
        .with_for_update(),
    )
    kept_items = await _items_to_keep(session, user_id, memory_day, existing, owned_strings)
    new_items = [item.model_dump(by_alias=True, mode="json") for item in report.items]
    report_json: dict[str, Any] = {
        "memoryDay": memory_day.isoformat(),
        "zoneId": report.zone_id,
        "headline": report.headline,
        "summary": report.summary,
        "items": [*kept_items, *new_items],
        "coverage": report.coverage.model_dump(by_alias=True),
        "generatedAtEpochMs": report.generated_at_epoch_ms,
        "extractionModel": report.extraction_model,
        "extractionVersion": report.extraction_version,
    }
    # With transcript sync on, lines of sessions this payload does not carry stay and the carried
    # sessions' lines are replaced. With it off the day keeps no lines at all: the person turned
    # it off, so what was synced before goes too (the note texts are cleared the same way).
    transcript_lines: list[Any] = []
    if payload.include_transcripts:
        old_lines = (
            existing.report.get("transcriptLines")
            if existing is not None and is_phone_document(existing.report)
            else None
        )
        transcript_lines = [
            line
            for line in (old_lines if isinstance(old_lines, list) else [])
            if isinstance(line, dict) and line.get("sessionId") not in owned_strings
        ]
        transcript_lines += [
            line.model_dump(by_alias=True) for line in report.transcript_lines or []
        ]
    if transcript_lines:
        report_json["transcriptLines"] = transcript_lines
    if existing is not None:
        existing.report = report_json
        existing.status = "complete"
        existing.extraction_version = report.extraction_version
    else:
        session.add(
            DailyReportRow(
                user_id=user_id,
                memory_day=memory_day,
                status="complete",
                report=report_json,
                extraction_version=report.extraction_version,
            ),
        )
    return len(report.items)


async def _refresh_notes(
    session: AsyncSession, user_id: uuid.UUID, report: SyncReportIn, payload: SyncPayloadIn
) -> None:
    """One note per session in this payload, rebuilt from what the payload carries."""
    sessions: dict[uuid.UUID, SessionContent] = {}

    def content(session_id: uuid.UUID) -> SessionContent:
        return sessions.setdefault(session_id, SessionContent(session_id, report.memory_day))

    for item in report.items:
        content(item.session_id).items.append((item.kind, item.text, item.evidence_epoch_ms))
    if payload.include_transcripts:
        for line in report.transcript_lines or []:
            if (session_id := _uuid_or_none(line.session_id)) is not None:
                content(session_id).lines.append((line.start_epoch_ms, line.text))
    for declared in report.sessions:
        note = content(declared.session_id)
        note.phone_title = declared.title
        note.started_ms = declared.started_at_epoch_ms
        note.declared = True
    for note in sessions.values():
        await upsert_note(session, user_id, note)


async def _items_to_keep(
    session: AsyncSession,
    user_id: uuid.UUID,
    memory_day: date,
    existing: DailyReportRow | None,
    owned_strings: set[str],
) -> list[dict[str, Any]]:
    """The day's items from sessions this payload does not carry, in phone-document form."""
    if existing is None:
        return []
    if is_phone_document(existing.report):
        stored = existing.report.get("items")
        return [
            item
            for item in (stored if isinstance(stored, list) else [])
            if isinstance(item, dict) and item.get("sessionId") not in owned_strings
        ]
    # The last writer was server-side extraction: its items live in memory_items.
    rows = (
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
    return [
        {
            "kind": row.kind,
            "text": row.text,
            "confidence": row.confidence,
            "quote": None,
            "owner": row.owner,
            "due": row.due.isoformat() if row.due else None,
            "direction": row.direction,
            "counterpart": row.counterpart,
            "sessionId": str(row.session_id),
            "evidenceEpochMs": None,
        }
        for row in rows
        if str(row.session_id) not in owned_strings
    ]


# Sections of the v1 report contract whose entries cite sessions through `evidence`.
_EVIDENCED_SECTIONS = ("action_items", "commitments", "decisions", "ideas", "open_questions")


def _scrub_report(report: dict[str, Any], gone: set[str]) -> dict[str, Any] | None:
    """The report without any content derived from the sessions in `gone`, in either stored
    shape (phone document or v1 contract). None when nothing derived is left."""
    scrubbed = dict(report)
    remaining = 0

    def keep(entries: object, key: str) -> list[Any]:
        return [
            e
            for e in (entries if isinstance(entries, list) else [])
            if _session(e, key) not in gone
        ]

    if "items" in report:
        scrubbed["items"] = keep(report["items"], "sessionId")
        remaining += len(scrubbed["items"])
    if "transcriptLines" in report:
        scrubbed["transcriptLines"] = keep(report["transcriptLines"], "sessionId")
        remaining += len(scrubbed["transcriptLines"])
    for name in _EVIDENCED_SECTIONS:
        if name not in report:
            continue
        entries: list[Any] = []
        for entry in report[name] if isinstance(report[name], list) else []:
            evidence = entry.get("evidence") if isinstance(entry, dict) else None
            if isinstance(evidence, list):
                left = [e for e in evidence if _session(e, "session_id") not in gone]
                if not left:
                    continue
                entry = {**entry, "evidence": left}
            entries.append(entry)
        scrubbed[name] = entries
        remaining += len(entries)
    for name in ("sessions", "highlights"):
        if name in report:
            scrubbed[name] = keep(report[name], "session_id")
            remaining += len(scrubbed[name])
    return scrubbed if remaining else None


def _session(entry: object, key: str) -> object:
    return entry.get(key) if isinstance(entry, dict) else None


async def purge_sessions(
    session: AsyncSession,
    store: ObjectStore | None,
    user_id: uuid.UUID,
    session_ids: list[uuid.UUID],
) -> None:
    """Removes everything derived from sessions deleted on the phone, for this user only
    (ADR-0004 amendment): memory items, report content (both stored shapes), uploaded clips
    and their objects, transcripts and segments, sealed deliveries, and queued jobs.

    Objects are deleted first; if the store fails the request fails (503) with nothing
    changed, and the phone retries the same ids.
    """
    if not session_ids:
        return
    ids = set(session_ids)
    id_strings = {str(i) for i in ids}

    uploads = (
        await session.execute(
            select(AudioUpload.id, AudioUpload.object_key).where(
                AudioUpload.user_id == user_id, AudioUpload.session_id.in_(ids)
            ),
        )
    ).all()
    if uploads:
        if store is None:
            raise HTTPException(
                status_code=503,
                detail="Deleting stored audio is temporarily unavailable. Retry later.",
            )
        for _, key in uploads:
            try:
                await store.delete(key)
            except Exception as error:
                logger.error(
                    "sync_session_delete_failed",
                    extra={"error_type": type(error).__name__, "session_count": len(ids)},
                )
                raise HTTPException(
                    status_code=503,
                    detail="Deleting stored audio failed. Retry later.",
                ) from error
    upload_ids = {row.id for row in uploads}

    # Transcripts, segments and sealed deliveries go with the upload rows (ON DELETE CASCADE).
    await session.execute(
        delete(AudioUpload).where(AudioUpload.user_id == user_id, AudioUpload.session_id.in_(ids))
    )
    await session.execute(
        delete(ProcessingJob).where(
            ProcessingJob.user_id == user_id,
            ProcessingJob.subject_id.in_(upload_ids | ids),
        )
    )
    # Memory item evidence cascades from memory_items.
    await session.execute(
        delete(MemoryItem).where(MemoryItem.user_id == user_id, MemoryItem.session_id.in_(ids))
    )
    await session.execute(delete(Note).where(Note.user_id == user_id, Note.session_id.in_(ids)))
    # Gone from the phone as well: nothing is left that could come back, so forget the marker.
    await session.execute(
        delete(DeletedNote).where(DeletedNote.user_id == user_id, DeletedNote.session_id.in_(ids))
    )

    # Only this user's reports that mention a deleted id (ids are canonical UUID strings, so
    # the pattern needs no escaping); report JSON is searched in the database, not loaded.
    pattern = "|".join(sorted(id_strings))
    rows = (
        await session.scalars(
            select(DailyReportRow).where(
                DailyReportRow.user_id == user_id,
                cast(DailyReportRow.report, Text).op("~*")(pattern),
            ),
        )
    ).all()
    for row in rows:
        scrubbed = _scrub_report(row.report, id_strings)
        if scrubbed is None:
            await session.delete(row)
        else:
            # Reassignment (not in-place mutation) so the JSONB change is always detected.
            row.report = scrubbed
