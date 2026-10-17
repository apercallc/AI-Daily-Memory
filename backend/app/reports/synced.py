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

import logging
import textwrap
from datetime import UTC, datetime
from typing import Any

from app.reports.daily import DailyReport

logger = logging.getLogger("app.reports.synced")

_SECTION_BY_KIND = {
    "action": "action_items",
    "commitment": "commitments",
    "decision": "decisions",
    "idea": "ideas",
    "question": "open_questions",
}
_VALID_KINDS = frozenset(_SECTION_BY_KIND)
_VALID_DIRECTIONS = frozenset({"i_owe", "owed_to_me"})
_MAX_PER_SECTION = 50
_MAX_SUMMARY_PARTS = 6
_SHORT_TEXT = 280
_MAX_DAY_MINUTES = 24 * 60
_UNKNOWN_COUNTERPART = "unknown"


def is_phone_document(stored: dict[str, Any]) -> bool:
    return "schema_version" not in stored and "items" in stored


def _build_entry(item: object, default_at: datetime) -> tuple[str, dict[str, Any]] | None:
    """One phone-document item, read defensively. A row from a different contract version
    (a renamed or missing key) is skipped rather than raised -- one malformed stored item
    degrades gracefully instead of taking down the whole report (AGENTS.md: no silent
    failure, so the caller logs how many items it dropped)."""
    if not isinstance(item, dict):
        return None
    kind = item.get("kind")
    text = item.get("text")
    session_id = item.get("sessionId")
    if kind not in _VALID_KINDS or not isinstance(text, str) or not isinstance(session_id, str):
        return None
    entry: dict[str, Any] = {
        "text": text,
        "confidence": item.get("confidence") or "possible",
        "evidence": [
            {
                "session_id": session_id,
                "at": _epoch_ms(item.get("evidenceEpochMs")) or default_at,
                "quote": item.get("quote"),
            },
        ],
    }
    if kind == "action":
        entry["owner"] = item.get("owner") or "me"
        entry["due"] = item.get("due")
    elif kind == "commitment":
        direction = item.get("direction")
        if direction not in _VALID_DIRECTIONS:
            return None
        entry["direction"] = direction
        entry["counterpart"] = item.get("counterpart") or _UNKNOWN_COUNTERPART
        entry["due"] = item.get("due")
    return kind, entry


def report_view(stored: dict[str, Any], generated_at: datetime) -> dict[str, Any]:
    """The v1-contract document for a stored report, whichever path wrote it."""
    if not is_phone_document(stored):
        return stored
    default_at = _epoch_ms(stored.get("generatedAtEpochMs")) or generated_at
    sections: dict[str, list[dict[str, Any]]] = {name: [] for name in _SECTION_BY_KIND.values()}
    raw_items = stored.get("items")
    skipped = 0
    for item in raw_items if isinstance(raw_items, list) else []:
        built = _build_entry(item, default_at)
        if built is None:
            skipped += 1
            continue
        kind, entry = built
        section = sections[_SECTION_BY_KIND[kind]]
        if len(section) >= _MAX_PER_SECTION:
            continue
        section.append(entry)
    if skipped:
        logger.warning("synced_report_item_malformed", extra={"skipped": skipped})

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
