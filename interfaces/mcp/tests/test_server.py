from typing import cast

import httpx
import pytest
from mcp.server.fastmcp import FastMCP
from mcp.server.fastmcp.exceptions import ToolError

from app.backend_client import BackendClient
from app.server import build_server

pytestmark = pytest.mark.anyio

REPORT = {
    "memory_day": "2026-09-25",
    "sessions": [{"session_id": "s1"}],
    "decisions": [{"text": "Ship Friday"}],
    "action_items": [{"text": "Send the deck"}],
    "commitments": [{"text": "Call Sam back"}],
}


def _server(handler: object) -> "tuple[FastMCP, BackendClient]":
    client = BackendClient(
        "https://api.example",
        "adm_test",
        transport=httpx.MockTransport(handler),  # type: ignore[arg-type]
    )
    return build_server(client), client


async def _call(mcp: FastMCP, name: str, args: dict[str, object]) -> dict[str, object]:
    # call_tool's declared return type doesn't cover the (content, structured) tuple it
    # actually returns with convert_result=True; the structured half is what tools care about.
    result = await mcp.call_tool(name, args)
    _, structured = cast(tuple[object, dict[str, object]], result)
    return structured


async def test_every_documented_tool_is_registered() -> None:
    mcp, _ = _server(lambda request: httpx.Response(200, json=REPORT))
    names = {t.name for t in await mcp.list_tools()}
    assert names == {
        "get_daily_report",
        "get_day",
        "get_timeline",
        "get_decisions",
        "get_action_items",
        "get_commitments",
    }


async def test_no_tool_is_a_search_or_bulk_export() -> None:
    # ask_memory/search_memory need semantic recall (Gate F), not crossed yet; a tool list
    # someone else's agent introspects must not promise a capability that doesn't exist.
    mcp, _ = _server(lambda request: httpx.Response(200, json=REPORT))
    names = {t.name for t in await mcp.list_tools()}
    assert "ask_memory" not in names
    assert "search_memory" not in names


@pytest.mark.parametrize(
    ("name", "field"),
    [
        ("get_decisions", "decisions"),
        ("get_action_items", "action_items"),
        ("get_commitments", "commitments"),
        ("get_timeline", "sessions"),
    ],
)
async def test_a_tool_call_returns_its_field_of_the_report(name: str, field: str) -> None:
    mcp, _ = _server(lambda request: httpx.Response(200, json=REPORT))

    structured = await _call(mcp, name, {"memory_day": "2026-09-25"})

    assert structured["result"] == REPORT[field]


async def test_get_daily_report_and_get_day_return_the_whole_report() -> None:
    mcp, _ = _server(lambda request: httpx.Response(200, json=REPORT))

    for name in ("get_daily_report", "get_day"):
        structured = await _call(mcp, name, {"memory_day": "2026-09-25"})
        assert structured == REPORT


async def test_a_non_full_cloud_account_gets_a_clear_actionable_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(403, json={"error": {"code": "cloud_storage_required"}})

    mcp, _ = _server(handler)

    with pytest.raises(ToolError) as caught:
        await mcp.call_tool("get_decisions", {"memory_day": "2026-09-25"})
    assert "full-cloud storage mode" in str(caught.value)


async def test_an_unauthenticated_call_is_reported_as_a_tool_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"code": "unauthorized"}})

    mcp, _ = _server(handler)

    with pytest.raises(ToolError):
        await mcp.call_tool("get_decisions", {"memory_day": "2026-09-25"})
