"""Read-only MCP server over the backend's Memory Query API (TODO.md Phase 5).

Run with:

    ADM_BASE_URL=https://api.example ADM_API_KEY=adm_... python -m app.server

One process per account, authenticated as that account's own API key (never a shared service
credential — TODO.md "MCP transport auth"). A key is scoped `memory:read` by default
(app/auth.py on the backend) and can never write.
"""

from collections.abc import Awaitable
from datetime import date

from mcp.server.fastmcp import FastMCP

from app import tools
from app.backend_client import BackendClient, CloudStorageRequiredError
from app.config import Settings

_CLOUD_STORAGE_MESSAGE = (
    "This account isn't in full-cloud storage mode, so it has no server-side daily reports to "
    "read. Switch to full-cloud storage mode in the app to use this tool."
)


def build_server(client: BackendClient) -> FastMCP:
    mcp = FastMCP(
        name="ai-daily-memory",
        instructions=(
            "Read-only access to one person's AI Daily Memory account: their daily reports and "
            "the decisions, action items, and commitments extracted from them, each backed by "
            "evidence timestamps. Never returns raw transcript text or bulk exports."
        ),
    )

    @mcp.tool()
    async def get_daily_report(memory_day: date) -> dict[str, object]:
        """The full report for one memory day: headline, every extracted item, coverage, and
        the session timeline."""
        return await _guarded(tools.get_daily_report(client, memory_day))

    @mcp.tool()
    async def get_day(memory_day: date) -> dict[str, object]:
        """Alias for get_daily_report."""
        return await _guarded(tools.get_daily_report(client, memory_day))

    @mcp.tool()
    async def get_timeline(memory_day: date) -> list[object]:
        """The day's sessions in order, each with its start/end time and title."""
        return await _guarded(tools.get_timeline(client, memory_day))

    @mcp.tool()
    async def get_decisions(memory_day: date) -> list[object]:
        """Decisions extracted from this day, each with confidence and evidence."""
        return await _guarded(tools.get_decisions(client, memory_day))

    @mcp.tool()
    async def get_action_items(memory_day: date) -> list[object]:
        """Action items extracted from this day, each with an owner, an optional due date,
        confidence, and evidence."""
        return await _guarded(tools.get_action_items(client, memory_day))

    @mcp.tool()
    async def get_commitments(memory_day: date) -> list[object]:
        """Commitments (promises between people) extracted from this day, each with a
        direction, a counterpart, confidence, and evidence."""
        return await _guarded(tools.get_commitments(client, memory_day))

    return mcp


async def _guarded[T](call: Awaitable[T]) -> T:
    try:
        return await call
    except CloudStorageRequiredError as e:
        raise ValueError(_CLOUD_STORAGE_MESSAGE) from e


def main() -> None:  # pragma: no cover - process entry point
    # api_key has no default in code; pydantic-settings requires ADM_API_KEY at runtime.
    settings = Settings()  # type: ignore[call-arg]
    client = BackendClient(
        settings.base_url,
        settings.api_key.get_secret_value(),
        timeout_seconds=settings.request_timeout_seconds,
    )
    build_server(client).run()


if __name__ == "__main__":  # pragma: no cover
    main()
