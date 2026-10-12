"""One error shape for every failure, so every client (Android, CLI, API) handles errors once.

    {"error": {"code": "not_found", "message": "Not Found", "request_id": "..."}}

Validation errors list *where* the input was wrong, never the rejected values: request
bodies here can contain transcript text or other private content.
"""

from collections.abc import Mapping
from typing import cast

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.observability import request_id_var


class FieldError(BaseModel):
    location: list[str | int]
    type: str


class ErrorBody(BaseModel):
    code: str
    message: str
    request_id: str | None = None
    fields: list[FieldError] | None = None


class ErrorResponse(BaseModel):
    error: ErrorBody


# Part of the public API contract: clients branch on `code`, so it must not change with the
# Python version (3.13 renamed several HTTPStatus phrases, e.g. 413 and 422).
_STANDARD_ERRORS: dict[int, tuple[str, str]] = {
    400: ("bad_request", "Bad request."),
    401: ("unauthorized", "Authentication required."),
    403: ("forbidden", "Not allowed."),
    404: ("not_found", "Not found."),
    405: ("method_not_allowed", "Method not allowed."),
    409: ("conflict", "Conflict."),
    413: ("payload_too_large", "Request body too large."),
    415: ("unsupported_media_type", "Unsupported media type."),
    422: ("unprocessable_entity", "Request could not be processed."),
    429: ("too_many_requests", "Too many requests."),
    500: ("internal_error", "Internal error."),
    503: ("service_unavailable", "Service unavailable."),
    504: ("timeout", "Request timed out."),
}


def error_code(status_code: int) -> str:
    return _STANDARD_ERRORS.get(status_code, (f"http_{status_code}", ""))[0]


def default_message(status_code: int) -> str:
    return _STANDARD_ERRORS.get(status_code, ("", "Request failed."))[1]


def error_response(
    status_code: int,
    message: str | None = None,
    *,
    code: str | None = None,
    fields: list[FieldError] | None = None,
    headers: Mapping[str, str] | None = None,
) -> JSONResponse:
    body = ErrorResponse(
        error=ErrorBody(
            code=code or error_code(status_code),
            message=message or default_message(status_code),
            request_id=request_id_var.get(),
            fields=fields,
        ),
    )
    return JSONResponse(
        status_code=status_code,
        content=body.model_dump(exclude_none=True),
        headers=headers,
    )


async def _http_exception(request: Request, exc: Exception) -> JSONResponse:
    exc = cast(StarletteHTTPException, exc)  # registered for this type only
    # Preserve protocol headers such as Allow (405) or WWW-Authenticate (401).
    return error_response(exc.status_code, str(exc.detail), headers=exc.headers)


async def _validation_exception(request: Request, exc: Exception) -> JSONResponse:
    exc = cast(RequestValidationError, exc)  # registered for this type only
    fields = [
        FieldError(
            location=[str(p) if not isinstance(p, int) else p for p in err["loc"]],
            type=str(err["type"]),
        )
        for err in exc.errors()
    ]
    return error_response(
        422,
        "Request validation failed.",
        code="validation_error",
        fields=fields,
    )


def install_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(StarletteHTTPException, _http_exception)
    app.add_exception_handler(RequestValidationError, _validation_exception)
