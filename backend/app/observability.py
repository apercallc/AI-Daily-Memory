"""Request correlation and redacted structured logging.

Logs must never contain raw audio, transcript text, prompts, queries, or credentials
(SECURITY.md). The formatter enforces that in three ways:

1. The message is the *template* only (`record.msg`). `%s` arguments are never
   interpolated, so a call like `logger.info("got %s", transcript)` cannot leak content. Use
   constant event names as messages and put data in `extra=`.
2. `extra=` fields, including nested dict and list values, are redacted when a word in the
   key names sensitive content (`transcriptText`, `access-token`, `x_api_key`, and so on).
3. Exceptions log their type and frame locations, never their message or source lines.
"""

import json
import logging
import re
import traceback
from collections.abc import Mapping
from contextvars import ContextVar
from datetime import UTC, datetime
from typing import Any, Final

request_id_var: ContextVar[str | None] = ContextVar("request_id", default=None)

REDACTED: Final = "[REDACTED]"
_SENSITIVE_WORDS: Final = frozenset(
    {
        "audio",
        "authorization",
        "bearer",
        "body",
        "cookie",
        "cookies",
        "credential",
        "credentials",
        "password",
        "passwords",
        "private",
        "prompt",
        "prompts",
        "query",
        "secret",
        "secrets",
        "text",
        "token",
        "tokens",
        "transcript",
        "transcription",
        "transcripts",
    }
)
_SENSITIVE_COMPOUNDS: Final = ("apikey", "api_key", "privatekey", "private_key", "passwd")
_CAMEL_BOUNDARY = re.compile(r"(?<=[a-z0-9])(?=[A-Z])")
_WORD_SEPARATORS = re.compile(r"[^a-zA-Z0-9]+")
_MAX_DEPTH: Final = 6
# Attributes every LogRecord has; anything else came from `extra=`.
_RESERVED = frozenset(logging.makeLogRecord({}).__dict__) | {"message", "asctime"}
_HANDLER_NAME: Final = "app-json"


def is_sensitive_key(key: str) -> bool:
    normalized = _WORD_SEPARATORS.sub("_", _CAMEL_BOUNDARY.sub("_", key)).lower().strip("_")
    if any(compound in normalized for compound in _SENSITIVE_COMPOUNDS):
        return True
    return any(word in _SENSITIVE_WORDS for word in normalized.split("_"))


def redact(value: Any, depth: int = 0) -> Any:
    """Recursively redact sensitive keys inside mappings and sequences."""
    if depth >= _MAX_DEPTH:
        return REDACTED
    if isinstance(value, Mapping):
        return {
            str(k): REDACTED if is_sensitive_key(str(k)) else redact(v, depth + 1)
            for k, v in value.items()
        }
    if isinstance(value, list | tuple | set | frozenset):
        return [redact(item, depth + 1) for item in value]
    return value


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, tz=UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "msg": str(record.msg),
        }
        request_id = request_id_var.get()
        if request_id is not None:
            payload["request_id"] = request_id
        for key, value in record.__dict__.items():
            if key in _RESERVED or key.startswith("_"):
                continue
            payload[key] = REDACTED if is_sensitive_key(key) else redact(value)
        if record.exc_info and record.exc_info[0] is not None:
            # Type and frames only. Exception messages can echo user content (a validation
            # error quoting a transcript, for example), so the message is never logged.
            payload["exc_type"] = record.exc_info[0].__name__
            # Frame locations only, no source lines: source text can contain literals.
            payload["stack"] = [
                f"{frame.filename}:{frame.lineno} in {frame.name}"
                for frame in traceback.extract_tb(record.exc_info[2])
            ]
        return json.dumps(payload, default=str)


def _install(logger: logging.Logger, level: str) -> None:
    logger.setLevel(level)
    logger.handlers = [h for h in logger.handlers if h.get_name() != _HANDLER_NAME]
    handler = logging.StreamHandler()
    handler.set_name(_HANDLER_NAME)
    handler.setFormatter(JsonFormatter())
    logger.addHandler(handler)
    logger.propagate = False


def configure_logging(level: str) -> logging.Logger:
    """Route app and server logs through the redacting formatter. Idempotent.

    Uvicorn's access log is disabled: it records full query strings, and
    `RequestContextMiddleware` already writes a redacted access line per request. Uvicorn's
    error logger (for example "Exception in ASGI application") is re-routed so exception
    messages are dropped there too.
    """
    app_logger = logging.getLogger("app")
    _install(app_logger, level)
    uvicorn_error = logging.getLogger("uvicorn.error")
    _install(uvicorn_error, level)
    uvicorn_error.handlers = [h for h in uvicorn_error.handlers if h.get_name() == _HANDLER_NAME]
    logging.getLogger("uvicorn.access").disabled = True
    return app_logger
