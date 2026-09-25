"""One error shape for every failure, so every client (Android, CLI, MCP) handles errors once.

    {"error": {"code": "not_found", "message": "Not Found", "request_id": "..."}}

Validation errors list *where* the input was wrong, never the rejected values: request
bodies here can contain transcript text or other private content.
"""

from collections.abc import Mapping
from http import HTTPStatus
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


def error_code(status_code: int) -> str:
    try:
        return HTTPStatus(status_code).phrase.lower().replace(" ", "_").replace("-", "_")
    except ValueError:
        return "error"


def error_response(
    status_code: int,
    message: str,
    *,
    code: str | None = None,
    fields: list[FieldError] | None = None,
    headers: Mapping[str, str] | None = None,
) -> JSONResponse:
    body = ErrorResponse(
        error=ErrorBody(
            code=code or error_code(status_code),
            message=message,
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
        HTTPStatus.UNPROCESSABLE_ENTITY,
        "Request validation failed.",
        code="validation_error",
        fields=fields,
    )


def install_error_handlers(app: FastAPI) -> None:
    app.add_exception_handler(StarletteHTTPException, _http_exception)
    app.add_exception_handler(RequestValidationError, _validation_exception)
