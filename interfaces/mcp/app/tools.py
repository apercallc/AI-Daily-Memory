"""Tool logic, kept separate from FastMCP registration (app/server.py) so it's testable without
a running MCP transport. Every tool is a read of one already-bounded daily report (each list
field is capped at 50 items server-side, app/reports/daily.py on the backend) — never a bulk
transcript export, never free-text search (TODO.md's `ask_memory`/`search_memory` need semantic
recall, which crosses an architecture gate — README Gate F — this repo hasn't crossed yet, so
they are not implemented here).
"""

from datetime import date
from typing import cast

from app.backend_client import BackendClient, CloudStorageRequiredError

_EMPTY_REPORT_NOTE = (
    "No report exists for this day yet. It could be nothing was recorded, nothing has been "
    "processed yet, or the account isn't in full-cloud storage mode."
)


def _list(report: dict[str, object] | None, field: str) -> list[object]:
    if report is None:
        return []
    value = report.get(field, [])
    return cast(list[object], value) if isinstance(value, list) else []


async def get_daily_report(client: BackendClient, memory_day: date) -> dict[str, object]:
    """The full day: headline, every extracted item, coverage, and the session timeline."""
    report = await client.get_report(memory_day)
    if report is None:
        return {"memory_day": memory_day.isoformat(), "note": _EMPTY_REPORT_NOTE}
    return report


async def get_timeline(client: BackendClient, memory_day: date) -> list[object]:
    return _list(await client.get_report(memory_day), "sessions")


async def get_decisions(client: BackendClient, memory_day: date) -> list[object]:
    return _list(await client.get_report(memory_day), "decisions")


async def get_action_items(client: BackendClient, memory_day: date) -> list[object]:
    return _list(await client.get_report(memory_day), "action_items")


async def get_commitments(client: BackendClient, memory_day: date) -> list[object]:
    return _list(await client.get_report(memory_day), "commitments")


__all__ = [
    "CloudStorageRequiredError",
    "get_action_items",
    "get_commitments",
    "get_daily_report",
    "get_decisions",
    "get_timeline",
]
