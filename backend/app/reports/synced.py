"""One read shape for two writers of `daily_reports.report`.

Two code paths store a day's report:

- `POST /v1/sync` stores the phone's camelCase document (`items`, `transcriptLines`, ...);
- server-side extraction (`app.reports.build`) stores the v1 contract (`DailyReport`,
  snake_case sections with evidence).

Whichever wrote last owns the stored JSON, so `GET /v1/reports/{day}` normalises on read:
a phone document is projected onto the v1 contract, and a v1 document passes through. The
stored JSON stays as written (the sync path needs `sessionId` on items to purge deletions
and merge devices); only the response has a single schema.
"""

import textwrap
from datetime import UTC, datetime
from typing import Any

from app.reports.daily import DailyReport

_SECTION_BY_KIND = {
    "action": "action_items",
    "commitment": "commitments",
    "decision": "decisions",
    "idea": "ideas",
    "question": "open_questions",
}
_MAX_PER_SECTION = 50
_MAX_SUMMARY_PARTS = 6
_SHORT_TEXT = 280
_MAX_DAY_MINUTES = 24 * 60
_UNKNOWN_COUNTERPART = "unknown"


def is_phone_document(stored: dict[str, Any]) -> bool:
    return "schema_version" not in stored and "items" in stored


def report_view(stored: dict[str, Any], generated_at: datetime) -> dict[str, Any]:
    """The v1-contract document for a stored report, whichever path wrote it."""
    if not is_phone_document(stored):
        return stored
    default_at = _epoch_ms(stored.get("generatedAtEpochMs")) or generated_at
    sections: dict[str, list[dict[str, Any]]] = {name: [] for name in _SECTION_BY_KIND.values()}
    for item in stored["items"]:
        section = sections[_SECTION_BY_KIND[item["kind"]]]
        if len(section) >= _MAX_PER_SECTION:
            continue
        entry: dict[str, Any] = {
            "text": item["text"],
            "confidence": item["confidence"],
            "evidence": [
                {
                    "session_id": item["sessionId"],
                    "at": _epoch_ms(item.get("evidenceEpochMs")) or default_at,
                    "quote": item.get("quote"),
                },
            ],
        }
        if item["kind"] == "action":
            entry["owner"] = item.get("owner") or "me"
            entry["due"] = item.get("due")
        elif item["kind"] == "commitment":
            entry["direction"] = item["direction"]
            entry["counterpart"] = item.get("counterpart") or _UNKNOWN_COUNTERPART
            entry["due"] = item.get("due")
        section.append(entry)

    coverage = stored["coverage"]
    summary = textwrap.wrap(stored["summary"], width=_SHORT_TEXT)[:_MAX_SUMMARY_PARTS]
    view = DailyReport.model_validate(
        {
            "memory_day": stored["memoryDay"],
            "timezone": stored["zoneId"],
            "generated_at": default_at,
            "status": "complete",
            "headline": stored["headline"] or None,
            "summary": summary,
            **sections,
            "coverage": {
                "recorded_minutes": min(coverage["recordedMs"] // 60_000, _MAX_DAY_MINUTES),
            },
        },
    ).model_dump(mode="json")
    if "transcriptLines" in stored:
        view["transcriptLines"] = stored["transcriptLines"]
    return view


def _epoch_ms(value: object) -> datetime | None:
    if not isinstance(value, int) or value < 0:
        return None
    return datetime.fromtimestamp(value / 1000, tz=UTC)
