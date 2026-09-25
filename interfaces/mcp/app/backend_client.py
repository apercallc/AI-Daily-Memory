"""The one seam to the backend (README: "Must go through the Memory Query Service, never the DB
directly"). Today that service is `GET /v1/reports/{memory_day}` — the only read this MCP
server needs, since every tool it exposes is a bounded slice of one day's already-assembled
report (app/reports/build.py on the backend). No tool here ever queries a database.
"""

from datetime import date

import httpx


class BackendError(Exception):
    """The backend said no. `code` is its error envelope's `error.code` (app/errors.py)."""

    def __init__(self, status: int, code: str) -> None:
        super().__init__(f"backend_error status={status} code={code}")
        self.status = status
        self.code = code


class CloudStorageRequiredError(Exception):
    """This account isn't in full_cloud storage mode, so it has no daily reports to read
    (ADR-0019, ADR-0014). Not a bug — a clear, actionable state a tool call should report as
    such rather than an empty result indistinguishable from "no report yet"."""


class BackendClient:
    def __init__(
        self,
        base_url: str,
        api_key: str,
        *,
        timeout_seconds: float = 30.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            timeout=timeout_seconds,
            transport=transport,
            headers={"Authorization": f"Bearer {api_key}", "Accept": "application/json"},
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def get_report(self, memory_day: date) -> dict[str, object] | None:
        """The day's report, or None if nothing has been generated for it yet."""
        response = await self._client.get(f"/v1/reports/{memory_day.isoformat()}")
        if response.status_code == httpx.codes.OK:
            body: dict[str, object] = response.json()
            return body
        code = _error_code(response)
        if response.status_code == httpx.codes.NOT_FOUND and code == "report_not_found":
            return None
        if response.status_code == httpx.codes.FORBIDDEN and code == "cloud_storage_required":
            raise CloudStorageRequiredError
        raise BackendError(response.status_code, code)


def _error_code(response: httpx.Response) -> str:
    try:
        body = response.json()
    except ValueError:
        return "unknown"
    error = body.get("error") if isinstance(body, dict) else None
    code = error.get("code") if isinstance(error, dict) else None
    return code if isinstance(code, str) else "unknown"
