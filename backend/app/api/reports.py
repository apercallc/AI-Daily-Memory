"""Reading a daily report (Phase 2, ADR-0019).

    GET /v1/reports/{memory_day}   the assembled report for that day, or 404 before it exists

Read-only: reports are always rebuilt from `memory_items` by extraction, never edited here. A
report only ever exists for a `full_cloud` account (ADR-0019), so a non-full_cloud caller gets a
clear 403 rather than an indistinguishable-from-just-not-generated-yet 404 (TODO.md Phase 5:
"API keys and MCP require a cloud storage mode") — the entitlement check server-authoritative
clients (MCP, the CLI, third-party API keys) are meant to rely on, per AGENTS.md rule 3.
"""

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.db import get_session
from app.db.models import DailyReportRow
from app.errors import ErrorResponse, error_response

router = APIRouter(tags=["reports"])

Session = Annotated[AsyncSession, Depends(get_session)]
Reader = Annotated[Principal, Depends(require("memory:read"))]
_ERRORS: dict[int | str, dict[str, object]] = {s: {"model": ErrorResponse} for s in (401, 403, 404)}


@router.get("/reports/{memory_day}", responses=_ERRORS)
async def get_report(memory_day: date, session: Session, reader: Reader) -> dict[str, object]:
    row = await session.scalar(
        select(DailyReportRow).where(
            DailyReportRow.user_id == reader.user_id,
            DailyReportRow.memory_day == memory_day,
        ),
    )
    if row is None:
        return error_response(404, "No report for this day yet.", code="report_not_found")  # type: ignore[return-value]
    return row.report
