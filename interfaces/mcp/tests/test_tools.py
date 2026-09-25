from dataclasses import dataclass
from datetime import date

import pytest

from app import tools
from app.backend_client import CloudStorageRequiredError

pytestmark = pytest.mark.anyio

DAY = date(2026, 9, 25)

REPORT: dict[str, object] = {
    "memory_day": "2026-09-25",
    "sessions": [{"session_id": "s1", "title": "Morning standup"}],
    "decisions": [{"text": "Ship Friday"}],
    "action_items": [{"text": "Send the deck"}, {"text": "Book the room"}],
    "commitments": [{"text": "Call Sam back"}],
}


@dataclass
class FakeClient:
    report: dict[str, object] | None = None
    error: Exception | None = None
    calls: int = 0

    async def get_report(self, memory_day: date) -> dict[str, object] | None:
        self.calls += 1
        if self.error:
            raise self.error
        return self.report


async def test_get_daily_report_returns_the_report_as_is() -> None:
    client = FakeClient(report=REPORT)
    assert await tools.get_daily_report(client, DAY) == REPORT  # type: ignore[arg-type]


async def test_get_daily_report_explains_an_empty_day() -> None:
    client = FakeClient(report=None)
    result = await tools.get_daily_report(client, DAY)  # type: ignore[arg-type]
    assert result["memory_day"] == "2026-09-25"
    assert "note" in result


@pytest.mark.parametrize(
    ("tool", "field"),
    [
        (tools.get_timeline, "sessions"),
        (tools.get_decisions, "decisions"),
        (tools.get_action_items, "action_items"),
        (tools.get_commitments, "commitments"),
    ],
)
async def test_each_tool_returns_its_own_bounded_slice(tool: object, field: str) -> None:
    client = FakeClient(report=REPORT)
    result = await tool(client, DAY)  # type: ignore[operator]
    assert result == REPORT[field]


@pytest.mark.parametrize(
    "tool", [tools.get_timeline, tools.get_decisions, tools.get_action_items, tools.get_commitments]
)
async def test_each_tool_returns_an_empty_list_before_a_report_exists(tool: object) -> None:
    client = FakeClient(report=None)
    assert await tool(client, DAY) == []  # type: ignore[operator]


async def test_a_report_with_a_missing_field_is_treated_as_empty() -> None:
    client = FakeClient(report={"memory_day": "2026-09-25"})
    assert await tools.get_decisions(client, DAY) == []  # type: ignore[arg-type]


async def test_a_malformed_field_never_crashes_a_tool() -> None:
    client = FakeClient(report={"decisions": "not-a-list"})
    assert await tools.get_decisions(client, DAY) == []  # type: ignore[arg-type]


async def test_cloud_storage_required_propagates_to_the_caller() -> None:
    client = FakeClient(error=CloudStorageRequiredError())
    with pytest.raises(CloudStorageRequiredError):
        await tools.get_decisions(client, DAY)  # type: ignore[arg-type]
