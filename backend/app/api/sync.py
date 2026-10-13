"""Sync endpoint for on-device processed memory (ADR-0020).

    POST /v1/sync   accept a SyncPayload from the phone, upsert reports + memory items

The phone processes audio on-device (transcription, extraction, report generation) and
pushes structured memory to this service for user-authorized API clients to query. The backend
never processes audio in this flow — it is a sync target and query service, not a processor.
Transcript lines are stored only when the phone explicitly
sets `includeTranscripts=true`.

Idempotent by replacement: syncing the same day's data replaces prior items for those
sessions, matching the phone's own idempotent-by-replacement extraction (ADR-0019).
"""

import uuid
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field, model_validator
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.db import get_session
from app.db.models import (
    CommitmentDirection,
    Confidence,
    DailyReportRow,
    MemoryItem,
    MemoryItemKind,
)
from app.errors import ErrorResponse

router = APIRouter(tags=["sync"])

Session = Annotated[AsyncSession, Depends(get_session)]
Writer = Annotated[Principal, Depends(require("upload:write"))]
_ERRORS: dict[int | str, dict[str, object]] = {s: {"model": ErrorResponse} for s in (401, 403, 422)}


class SyncItemIn(BaseModel):
    """One memory item, mirroring the phone's contract (core `SyncItem`) and the DB CHECK
    constraints, so an out-of-contract item is a 422 with a field location — never a 500."""

    model_config = ConfigDict(populate_by_name=True)

    kind: MemoryItemKind
    text: str = Field(max_length=2_000)
    confidence: Confidence
    quote: str | None = None
    owner: str | None = None
    due: date | None = None
    direction: CommitmentDirection | None = None
    counterpart: str | None = None
    session_id: uuid.UUID = Field(alias="sessionId")
    evidence_epoch_ms: int | None = Field(default=None, alias="evidenceEpochMs")

    @model_validator(mode="after")
    def _commitment_has_direction(self) -> "SyncItemIn":
        # Same rule as the `commitment_has_direction` CHECK on memory_items.
        if self.kind == "commitment" and self.direction is None:
            raise ValueError("direction is required for commitment items")
        return self


class SyncCoverageIn(BaseModel):
    recorded_ms: int = Field(alias="recordedMs")
    speech_ms: int = Field(alias="speechMs")
    transcribed_ms: int = Field(alias="transcribedMs")
    sessions: int

    model_config = {"populate_by_name": True}


class SyncTranscriptLineIn(BaseModel):
    model_config = ConfigDict(extra="forbid")

    session_id: str = Field(alias="sessionId", min_length=1, max_length=128)
    start_epoch_ms: int = Field(alias="startEpochMs", ge=0)
    speaker: str | None = Field(default=None, max_length=80)
    text: str = Field(min_length=1, max_length=5_000)


class SyncReportIn(BaseModel):
    memory_day: str = Field(alias="memoryDay")
    zone_id: str = Field(alias="zoneId")
    headline: str
    summary: str
    items: list[SyncItemIn] = Field(max_length=2_000)
    transcript_lines: list[SyncTranscriptLineIn] | None = Field(
        default=None,
        alias="transcriptLines",
        max_length=20_000,
    )
    coverage: SyncCoverageIn
    generated_at_epoch_ms: int = Field(alias="generatedAtEpochMs")
    extraction_model: str = Field(alias="extractionModel")
    extraction_version: int = Field(alias="extractionVersion")

    model_config = {"populate_by_name": True}


class SyncPayloadIn(BaseModel):
    device: str
    generated_at_epoch_ms: int = Field(alias="generatedAtEpochMs")
    reports: list[SyncReportIn]
    include_transcripts: bool = Field(alias="includeTranscripts", default=False)
    deleted_sessions: list[uuid.UUID] = Field(
        default_factory=list,
        alias="deletedSessions",
        max_length=500,
    )

    model_config = {"populate_by_name": True}


@router.post("/sync", responses=_ERRORS)
async def sync_memory(
    payload: SyncPayloadIn,
    session: Session,
    writer: Writer,
) -> dict[str, object]:
    """Accepts on-device processed memory from the phone. Idempotent by replacement.

    `deletedSessions` propagates the phone's local deletions (ADR-0020): the derived memory
    of those sessions is removed here too, so deleting a session on the phone does not leave
    a server-side copy behind. Repeating the same id is a no-op.
    """
    await _purge_deleted_sessions(session, writer.user_id, payload.deleted_sessions)
    if not payload.reports:
        await session.commit()
        return {"synced": 0, "items": 0, "deletedSessions": len(payload.deleted_sessions)}

    total_items = 0
    for report in payload.reports:
        memory_day = date.fromisoformat(report.memory_day)

        # Delete prior memory items for this user on this day (idempotent by replacement).
        await session.execute(
            delete(MemoryItem).where(
                MemoryItem.user_id == writer.user_id,
                MemoryItem.memory_day == memory_day,
            ),
        )

        # Insert new memory items.
        for item in report.items:
            session.add(
                MemoryItem(
                    user_id=writer.user_id,
                    session_id=item.session_id,
                    memory_day=memory_day,
                    kind=item.kind,
                    text=item.text[:280],
                    confidence=item.confidence,
                    owner=item.owner,
                    counterpart=item.counterpart,
                    direction=item.direction,
                    due=item.due,
                    extraction_model=report.extraction_model,
                    extraction_version=report.extraction_version,
                ),
            )
            total_items += 1

        # Upsert the daily report.
        existing = await session.scalar(
            select(DailyReportRow).where(
                DailyReportRow.user_id == writer.user_id,
                DailyReportRow.memory_day == memory_day,
            ),
        )
        report_json = {
            "memoryDay": report.memory_day,
            "zoneId": report.zone_id,
            "headline": report.headline,
            "summary": report.summary,
            "items": [
                # mode="json": UUIDs and dates stay JSON scalars inside the stored report.
                item.model_dump(by_alias=True, mode="json")
                for item in report.items
            ],
            "coverage": report.coverage.model_dump(by_alias=True),
            "extractionModel": report.extraction_model,
            "extractionVersion": report.extraction_version,
        }
        if payload.include_transcripts:
            report_json["transcriptLines"] = [
                line.model_dump(by_alias=True) for line in (report.transcript_lines or [])
            ]
        if existing is not None:
            existing.report = report_json
            existing.extraction_version = report.extraction_version
        else:
            session.add(
                DailyReportRow(
                    user_id=writer.user_id,
                    memory_day=memory_day,
                    status="complete",
                    report=report_json,
                    extraction_version=report.extraction_version,
                ),
            )

    await session.commit()
    return {
        "synced": len(payload.reports),
        "items": total_items,
        "deletedSessions": len(payload.deleted_sessions),
    }


async def _purge_deleted_sessions(
    session: AsyncSession, user_id: uuid.UUID, session_ids: list[uuid.UUID]
) -> None:
    """Removes this user's derived memory for sessions deleted on the phone.

    Memory item rows are deleted directly. Daily reports keep other sessions' data and drop
    the deleted sessions' items and transcript lines from the stored JSON; a day whose derived
    data belonged entirely to deleted sessions is removed outright — user deletion cascades
    to everything derived from it (ADR-0004 amendment).
    """
    if not session_ids:
        return
    ids = set(session_ids)
    # The report JSON stores session ids as canonical strings.
    id_strings = {str(session_id) for session_id in ids}
    await session.execute(
        delete(MemoryItem).where(
            MemoryItem.user_id == user_id,
            MemoryItem.session_id.in_(ids),
        )
    )
    reports = (
        await session.scalars(select(DailyReportRow).where(DailyReportRow.user_id == user_id))
    ).all()
    for row in reports:
        report = row.report
        raw_items = report.get("items")
        items = raw_items if isinstance(raw_items, list) else []
        kept_items = [
            i for i in items if not isinstance(i, dict) or i.get("sessionId") not in id_strings
        ]
        raw_lines = report.get("transcriptLines")
        lines = raw_lines if isinstance(raw_lines, list) else None
        kept_lines = None
        if lines is not None:
            kept_lines = [
                line
                for line in lines
                if not isinstance(line, dict) or line.get("sessionId") not in id_strings
            ]
        touched = len(kept_items) < len(items) or (
            kept_lines is not None and lines is not None and len(kept_lines) < len(lines)
        )
        if not touched:
            continue
        if kept_items or kept_lines:
            updated = dict(report)
            updated["items"] = kept_items
            if kept_lines is not None:
                updated["transcriptLines"] = kept_lines
            # Reassignment (not in-place mutation) so the JSONB change is always detected.
            row.report = updated
        else:
            await session.delete(row)
