import asyncio
import json
import logging
from collections.abc import AsyncIterator, Iterator

import pytest
from fastapi import FastAPI
from fastapi.responses import JSONResponse, StreamingResponse
from fastapi.testclient import TestClient
from pydantic import BaseModel
from starlette.requests import ClientDisconnect
from starlette.types import Message, Receive, Scope, Send

from app.config import Settings
from app.main import create_app
from app.middleware import PayloadTooLargeError, RequestContextMiddleware
from app.observability import JsonFormatter, request_id_var

LIMIT = 64


class Note(BaseModel):
    title: str
    minutes: int


@pytest.fixture
def app() -> FastAPI:
    application = create_app(Settings(app_env="test", max_request_body_bytes=LIMIT))

    @application.post("/echo")
    async def echo(note: Note) -> dict[str, str | int | None]:
        return {"title": note.title, "request_id": request_id_var.get()}

    @application.get("/boom")
    async def boom() -> None:
        raise RuntimeError("secret transcript text")

    @application.get("/sets-own-request-id")
    async def sets_own_request_id() -> JSONResponse:
        return JSONResponse({}, headers={"X-Request-ID": "downstream"})

    @application.get("/broken-stream")
    async def broken_stream() -> StreamingResponse:
        async def chunks() -> AsyncIterator[bytes]:
            yield b"partial"
            raise RuntimeError("mid-stream failure")

        return StreamingResponse(chunks())

    return application


@pytest.fixture
def client(app: FastAPI) -> Iterator[TestClient]:
    with TestClient(app, raise_server_exceptions=False) as test_client:
        yield test_client


def test_generates_request_id_and_exposes_it_to_handlers(client: TestClient) -> None:
    response = client.post("/echo", json={"title": "t", "minutes": 1})

    request_id = response.headers["x-request-id"]
    assert len(request_id) == 32
    assert response.json()["request_id"] == request_id


def test_keeps_a_valid_client_request_id(client: TestClient) -> None:
    response = client.get("/v1/health", headers={"X-Request-ID": "android-7f3.a_b"})

    assert response.headers["x-request-id"] == "android-7f3.a_b"


@pytest.mark.parametrize("bad", ["has space", "x" * 65, "new\tline", "a;b", ""])
def test_replaces_unsafe_client_request_ids(client: TestClient, bad: str) -> None:
    response = client.get("/v1/health", headers={"X-Request-ID": bad})

    assert response.headers["x-request-id"] != bad
    assert len(response.headers["x-request-id"]) == 32


def test_request_id_header_is_never_duplicated(client: TestClient) -> None:
    response = client.get("/sets-own-request-id")

    assert response.headers.get_list("x-request-id") == [response.headers["x-request-id"]]
    assert response.headers["x-request-id"] != "downstream"


def test_request_id_is_scoped_to_one_request_on_a_shared_event_loop() -> None:
    seen: list[str | None] = []

    async def downstream(scope: Scope, receive: Receive, send: Send) -> None:
        seen.append(request_id_var.get())
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    async def receive() -> Message:
        return {"type": "http.request", "body": b"", "more_body": False}

    async def send(message: Message) -> None:
        return None

    async def two_requests() -> None:
        middleware = RequestContextMiddleware(downstream, max_body_bytes=LIMIT)
        for rid in (b"first", b"second"):
            scope: Scope = {
                "type": "http",
                "method": "GET",
                "path": "/",
                "headers": [(b"x-request-id", rid)],
            }
            await middleware(scope, receive, send)
            seen.append(request_id_var.get())

    asyncio.run(two_requests())

    assert seen == ["first", None, "second", None]


def test_rejects_declared_oversized_body_before_reading_it(client: TestClient) -> None:
    response = client.post("/echo", content=b"x" * (LIMIT + 1))

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "payload_too_large"
    assert response.json()["error"]["request_id"] == response.headers["x-request-id"]


def test_rejects_streamed_body_without_content_length(client: TestClient) -> None:
    def body() -> Iterator[bytes]:
        for _ in range(10):
            yield b"x" * 16

    response = client.post("/echo", content=body())

    assert response.status_code == 413
    assert response.json()["error"]["code"] == "payload_too_large"


def test_body_at_exact_limit_is_accepted(client: TestClient) -> None:
    payload = json.dumps({"title": "", "minutes": 1}).encode()
    padded = payload + b" " * (LIMIT - len(payload))

    response = client.post("/echo", content=padded, headers={"content-type": "application/json"})

    assert response.status_code == 200


def test_unhandled_error_returns_envelope_and_logs_without_message(
    client: TestClient, caplog: pytest.LogCaptureFixture
) -> None:
    app_logger = logging.getLogger("app")
    app_logger.addHandler(caplog.handler)
    try:
        response = client.get("/boom")
    finally:
        app_logger.removeHandler(caplog.handler)

    assert response.status_code == 500
    body = response.json()["error"]
    assert body["code"] == "internal_error"
    assert "secret" not in response.text
    assert body["request_id"] == response.headers["x-request-id"]
    error_logs = [r for r in caplog.records if r.getMessage() == "unhandled_error"]
    assert len(error_logs) == 1
    formatted = JsonFormatter().format(error_logs[0])
    assert "secret transcript text" not in formatted
    assert json.loads(formatted)["exc_type"] == "RuntimeError"
    access = [r for r in caplog.records if r.getMessage() == "request"][-1]
    assert access.__dict__["status"] == 500


def test_error_after_response_started_is_reraised(app: FastAPI) -> None:
    with TestClient(app) as strict, pytest.raises(RuntimeError, match="mid-stream"):
        strict.get("/broken-stream")


# --- Raw ASGI edge cases -------------------------------------------------------------------


async def _call(
    headers: list[tuple[bytes, bytes]],
    chunks: list[bytes],
    limit: int = LIMIT,
) -> list[Message]:
    sent: list[Message] = []

    async def downstream(scope: Scope, receive: Receive, send: Send) -> None:
        while (await receive()).get("more_body", False):
            pass
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    pending = [
        {"type": "http.request", "body": c, "more_body": i < len(chunks) - 1}
        for i, c in enumerate(chunks)
    ]

    async def receive() -> Message:
        return pending.pop(0)

    async def send(message: Message) -> None:
        sent.append(message)

    scope: Scope = {
        "type": "http",
        "method": "POST",
        "path": "/x",
        "headers": headers,
        "query_string": b"",
    }
    await RequestContextMiddleware(downstream, max_body_bytes=limit)(scope, receive, send)
    return sent


@pytest.mark.parametrize("value", [b"abc", b"-1"])
def test_malformed_content_length_is_a_bad_request(value: bytes) -> None:
    sent = asyncio.run(_call([(b"content-length", value)], [b""]))

    assert sent[0]["status"] == 400


def test_non_ascii_request_id_bytes_are_replaced() -> None:
    sent = asyncio.run(_call([(b"x-request-id", "日本".encode())], [b""]))

    request_id = dict(sent[0]["headers"])[b"x-request-id"]
    assert len(request_id) == 32


def test_lying_content_length_is_still_enforced_on_real_bytes() -> None:
    sent = asyncio.run(_call([(b"content-length", b"1")], [b"x" * 40, b"x" * 40]))

    assert sent[0]["status"] == 413


def test_body_within_limit_passes_through() -> None:
    sent = asyncio.run(_call([], [b"x" * 10, b"x" * 10]))

    assert sent[0]["status"] == 204
    assert (b"x-request-id", sent[0]["headers"][0][1]) in sent[0]["headers"]


def test_oversized_body_after_response_started_is_reraised() -> None:
    async def eager(scope: Scope, receive: Receive, send: Send) -> None:
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await receive()

    async def receive() -> Message:
        return {"type": "http.request", "body": b"x" * (LIMIT + 1), "more_body": False}

    async def send(message: Message) -> None:
        return None

    scope: Scope = {"type": "http", "method": "POST", "path": "/x", "headers": []}
    middleware = RequestContextMiddleware(eager, max_body_bytes=LIMIT)
    with pytest.raises(PayloadTooLargeError):
        asyncio.run(middleware(scope, receive, send))


@pytest.mark.parametrize("disconnect", [asyncio.CancelledError, ClientDisconnect])
def test_client_disconnect_is_logged_as_499_not_500(
    disconnect: type[BaseException], caplog: pytest.LogCaptureFixture
) -> None:
    async def downstream(scope: Scope, receive: Receive, send: Send) -> None:
        raise disconnect

    async def receive() -> Message:
        return {"type": "http.disconnect"}

    async def send(message: Message) -> None:
        return None

    scope: Scope = {"type": "http", "method": "GET", "path": "/x", "headers": []}
    app_logger = logging.getLogger("app")
    app_logger.addHandler(caplog.handler)
    try:
        with pytest.raises(disconnect):
            asyncio.run(
                RequestContextMiddleware(downstream, max_body_bytes=LIMIT)(scope, receive, send)
            )
    finally:
        app_logger.removeHandler(caplog.handler)

    access = [r for r in caplog.records if r.getMessage() == "request"][-1]
    assert access.__dict__["status"] == 499
    assert not [r for r in caplog.records if r.getMessage() == "unhandled_error"]


def test_non_http_scopes_pass_through_untouched() -> None:
    seen: list[str] = []

    async def downstream(scope: Scope, receive: Receive, send: Send) -> None:
        seen.append(scope["type"])

    async def receive() -> Message:
        return {"type": "lifespan.startup"}

    async def send(message: Message) -> None:
        return None

    middleware = RequestContextMiddleware(downstream, max_body_bytes=LIMIT)
    asyncio.run(middleware({"type": "lifespan"}, receive, send))

    assert seen == ["lifespan"]
