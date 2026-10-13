"""Reading a daily report (Phase 2, ADR-0019).

    GET /v1/reports/{memory_day}   the assembled report for that day, or 404 before it exists

Read-only: this endpoint returns the persisted report for its authenticated owner. Phone-only
accounts may access reports they explicitly synced; storage mode is not an authorization check.
"""

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.db import get_session
from app.db.models import DailyReportRow
from app.errors import ErrorResponse, error_response
from app.reports.daily import DailyReport
from app.reports.synced import report_view

router = APIRouter(tags=["reports"])

Session = Annotated[AsyncSession, Depends(get_session)]
Reader = Annotated[Principal, Depends(require("memory:read"))]
_ERRORS: dict[int | str, dict[str, object]] = {s: {"model": ErrorResponse} for s in (401, 403, 404)}


class TranscriptLine(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    session_id: str = Field(alias="sessionId")
    start_epoch_ms: int = Field(alias="startEpochMs")
    speaker: str | None = None
    text: str


class ReportOut(DailyReport):
    """The v1 report contract, plus the opted-in transcript lines for `transcript:read`."""

    transcript_lines: list[TranscriptLine] | None = Field(default=None, alias="transcriptLines")


@router.get(
    "/reports/{memory_day}",
    response_model=ReportOut,
    response_model_exclude_unset=True,
    responses=_ERRORS,
)
async def get_report(
    memory_day: date, session: Session, reader: Reader
) -> ReportOut | JSONResponse:
    row = await session.scalar(
        select(DailyReportRow).where(
            DailyReportRow.user_id == reader.user_id,
            DailyReportRow.memory_day == memory_day,
        ),
    )
    if row is None:
        return error_response(404, "No report for this day yet.", code="report_not_found")
    # Phone-synced and server-built rows have different stored shapes; the response doesn't.
    report = dict(report_view(row.report, row.generated_at))
    if "transcript:read" not in reader.scopes:
        report.pop("transcriptLines", None)
    return ReportOut.model_validate(report)
