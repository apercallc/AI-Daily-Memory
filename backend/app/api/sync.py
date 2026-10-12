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

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.db import get_session
from app.db.models import DailyReportRow, MemoryItem
from app.errors import ErrorResponse

router = APIRouter(tags=["sync"])

Session = Annotated[AsyncSession, Depends(get_session)]
Writer = Annotated[Principal, Depends(require("upload:write"))]
_ERRORS: dict[int | str, dict[str, object]] = {s: {"model": ErrorResponse} for s in (401, 403, 422)}


class SyncItemIn(BaseModel):
    kind: str
    text: str
    confidence: str
    quote: str | None = None
    owner: str | None = None
    due: str | None = None
    direction: str | None = None
    counterpart: str | None = None
    session_id: str
    evidence_epoch_ms: int | None = None


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
    items: list[SyncItemIn]
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

    model_config = {"populate_by_name": True}


@router.post("/sync", responses=_ERRORS)
async def sync_memory(
    payload: SyncPayloadIn,
    session: Session,
    writer: Writer,
) -> dict[str, object]:
    """Accepts on-device processed memory from the phone. Idempotent by replacement."""
    if not payload.reports:
        return {"synced": 0, "items": 0}

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
                    due=date.fromisoformat(item.due) if item.due else None,
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
            "items": [item.model_dump(by_alias=True) for item in report.items],
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
    return {"synced": len(payload.reports), "items": total_items}
