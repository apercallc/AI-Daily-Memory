from datetime import date

import httpx
import pytest

from app.backend_client import BackendClient, BackendError, CloudStorageRequiredError

pytestmark = pytest.mark.anyio

DAY = date(2026, 9, 25)


def _client(handler: object) -> BackendClient:
    return BackendClient(
        "https://api.example",
        "adm_test",
        transport=httpx.MockTransport(handler),  # type: ignore[arg-type]
    )


async def test_a_report_that_exists_is_returned() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/v1/reports/2026-09-25"
        assert request.headers["authorization"] == "Bearer adm_test"
        return httpx.Response(200, json={"status": "complete"})

    report = await _client(handler).get_report(DAY)
    assert report == {"status": "complete"}


async def test_a_missing_report_is_none() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"error": {"code": "report_not_found"}})

    assert await _client(handler).get_report(DAY) is None


async def test_a_non_full_cloud_account_raises_a_named_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(403, json={"error": {"code": "cloud_storage_required"}})

    with pytest.raises(CloudStorageRequiredError):
        await _client(handler).get_report(DAY)


async def test_unauthorized_is_a_backend_error() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"error": {"code": "unauthorized"}})

    with pytest.raises(BackendError) as caught:
        await _client(handler).get_report(DAY)
    assert caught.value.status == 401
    assert caught.value.code == "unauthorized"


async def test_a_body_without_an_error_envelope_is_still_reported() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(500, content=b"internal error")

    with pytest.raises(BackendError) as caught:
        await _client(handler).get_report(DAY)
    assert caught.value.code == "unknown"


async def test_a_404_with_a_different_code_is_a_backend_error_not_a_missing_report() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(404, json={"error": {"code": "not_found"}})

    with pytest.raises(BackendError):
        await _client(handler).get_report(DAY)


async def test_aclose_releases_the_underlying_client() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json={"status": "complete"})

    client = _client(handler)
    await client.get_report(DAY)
    await client.aclose()
    assert client._client.is_closed
