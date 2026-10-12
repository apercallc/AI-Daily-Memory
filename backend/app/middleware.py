"""Pure-ASGI request middleware: correlation IDs, body limits, last-resort error handling.

Implemented as raw ASGI rather than `BaseHTTPMiddleware`: no per-request task or body
buffering, streaming stays intact, and context variables propagate to the endpoint.
"""

import asyncio
import logging
import re
import time
import uuid
from http import HTTPStatus
from typing import Final

from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.requests import ClientDisconnect
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from app.errors import error_response
from app.observability import request_id_var
from app.sentry import capture_unhandled_error

logger = logging.getLogger("app.request")

REQUEST_ID_HEADER: Final = "x-request-id"
_REQUEST_ID_HEADER_BYTES: Final = REQUEST_ID_HEADER.encode()
# nginx convention for "client went away before we answered"; never a server error.
CLIENT_CLOSED_REQUEST: Final = 499
# Client-supplied IDs are echoed into logs, so accept only a safe, bounded charset.
_VALID_REQUEST_ID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")


class PayloadTooLargeError(StarletteHTTPException):
    """Raised from `receive` when a streamed body exceeds the configured limit.

    An HTTPException on purpose: FastAPI turns any other exception raised while reading the
    body into a generic 400, but re-raises HTTPExceptions to the error handlers.
    """

    def __init__(self) -> None:
        super().__init__(status_code=HTTPStatus.REQUEST_ENTITY_TOO_LARGE)


class RequestContextMiddleware:
    def __init__(
        self,
        app: ASGIApp,
        *,
        max_body_bytes: int,
        timeout_seconds: float | None = None,
    ) -> None:
        self.app = app
        self.max_body_bytes = max_body_bytes
        self.timeout_seconds = timeout_seconds

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        request_id = _request_id(scope)
        token = request_id_var.set(request_id)
        started = time.perf_counter()
        response_started = False
        status_code = HTTPStatus.INTERNAL_SERVER_ERROR.value

        async def send_with_request_id(message: Message) -> None:
            nonlocal response_started, status_code
            if message["type"] == "http.response.start":
                response_started = True
                status_code = message["status"]
                # Replace, never duplicate, a request ID set further down the stack.
                headers = [
                    (name, value)
                    for name, value in message.get("headers", [])
                    if name.lower() != _REQUEST_ID_HEADER_BYTES
                ]
                headers.append((_REQUEST_ID_HEADER_BYTES, request_id.encode()))
                message = {**message, "headers": headers}
            await send(message)

        try:
            limit_status = self._declared_length_problem(scope)
            if limit_status is not None:
                status_code = limit_status
                await _send_error(scope, send_with_request_id, limit_status)
                return
            async with asyncio.timeout(self.timeout_seconds):
                await self.app(scope, self._limited(receive), send_with_request_id)
        except TimeoutError:
            # Over the request budget: a slow dependency must not pin a worker forever.
            logger.warning("request_timeout", extra={"path": scope["path"]})
            if response_started:
                raise
            status_code = HTTPStatus.GATEWAY_TIMEOUT.value
            await _send_error(scope, send_with_request_id, HTTPStatus.GATEWAY_TIMEOUT)
        except (asyncio.CancelledError, ClientDisconnect):
            # The client disconnected. Nothing to send; record it honestly, not as a 500.
            if not response_started:
                status_code = CLIENT_CLOSED_REQUEST
            raise
        except PayloadTooLargeError:
            if response_started:
                raise
            await _send_error(scope, send_with_request_id, HTTPStatus.REQUEST_ENTITY_TOO_LARGE)
        except Exception as exc:
            # Last resort: log type and stack (no message, no body), return the standard
            # envelope. If the response already started, the client sees a broken stream.
            logger.exception("unhandled_error", extra={"path": scope["path"]})
            capture_unhandled_error(exc)
            if response_started:
                raise
            await _send_error(scope, send_with_request_id, HTTPStatus.INTERNAL_SERVER_ERROR)
        finally:
            logger.info(
                "request",
                extra={
                    "method": scope["method"],
                    # Path only: query strings can carry search terms from user memories.
                    "path": scope["path"],
                    "status": status_code,
                    "duration_ms": round((time.perf_counter() - started) * 1000, 2),
                },
            )
            request_id_var.reset(token)

    def _declared_length_problem(self, scope: Scope) -> HTTPStatus | None:
        for name, value in scope["headers"]:
            if name == b"content-length":
                try:
                    length = int(value)
                except ValueError:
                    return HTTPStatus.BAD_REQUEST
                if length < 0:
                    return HTTPStatus.BAD_REQUEST
                if length > self.max_body_bytes:
                    return HTTPStatus.REQUEST_ENTITY_TOO_LARGE
        return None

    def _limited(self, receive: Receive) -> Receive:
        # Content-Length can be absent (chunked) or wrong; count the real bytes too.
        received = 0

        async def limited_receive() -> Message:
            nonlocal received
            message = await receive()
            if message["type"] == "http.request":
                received += len(message.get("body", b""))
                if received > self.max_body_bytes:
                    raise PayloadTooLargeError
            return message

        return limited_receive


def _request_id(scope: Scope) -> str:
    for name, value in scope["headers"]:
        if name == _REQUEST_ID_HEADER_BYTES:
            candidate = bytes(value).decode("latin-1")
            if _VALID_REQUEST_ID.match(candidate):
                return candidate
            break
    return uuid.uuid4().hex


async def _send_error(scope: Scope, send: Send, status: HTTPStatus) -> None:
    response = error_response(status.value)

    async def no_body() -> Message:  # pragma: no cover - JSONResponse never reads the body
        return {"type": "http.disconnect"}

    await response(scope, no_body, send)
