"""Writes the published JSON Schema for the daily report contract.

    uv run python -m app.reports.schema

The committed file (contracts/daily-report.v1.schema.json) is what API clients code
against; a test fails if it drifts from the models.
"""

import json
from pathlib import Path
from typing import Any

from app.reports.daily import DailyReport

SCHEMA_PATH = Path(__file__).resolve().parents[3] / "contracts" / "daily-report.v1.schema.json"


def schema() -> dict[str, Any]:
    document = DailyReport.model_json_schema()
    document["$schema"] = "https://json-schema.org/draft/2020-12/schema"
    document["$id"] = "https://apercallc.com/ai-daily-memory/contracts/daily-report.v1.schema.json"
    return document


def render() -> str:
    return json.dumps(schema(), indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def main() -> None:  # pragma: no cover - thin CLI wrapper, exercised via render()
    SCHEMA_PATH.parent.mkdir(parents=True, exist_ok=True)
    SCHEMA_PATH.write_text(render(), encoding="utf-8")


if __name__ == "__main__":  # pragma: no cover
    main()
