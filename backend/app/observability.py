"""Request correlation and redacted structured logging.

Logs must never contain raw audio, transcript text, prompts, or credentials (SECURITY.md).
Two layers enforce that: callers pass structured fields via `extra=`, and any field whose
name suggests sensitive content is replaced before it is serialized.
"""

import json
import logging
import re
import traceback
from contextvars import ContextVar
from datetime import UTC, datetime
from typing import Any, Final

request_id_var: ContextVar[str | None] = ContextVar("request_id", default=None)

REDACTED: Final = "[REDACTED]"
# Matches whole snake_case words, so `transcript_text` and `access_token` are redacted but
# `context` is not.
_SENSITIVE_KEY = re.compile(
    r"(?:^|_)(?:authorization|cookie|password|secret|token|api_?key|transcript|prompt|audio"
    r"|text|body)s?(?:_|$)",
    re.IGNORECASE,
)
# Attributes every LogRecord has; anything else came from `extra=`.
_RESERVED = frozenset(logging.makeLogRecord({}).__dict__) | {"message", "asctime"}
_HANDLER_NAME: Final = "app-json"


class JsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, tz=UTC).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        request_id = request_id_var.get()
        if request_id is not None:
            payload["request_id"] = request_id
        for key, value in record.__dict__.items():
            if key in _RESERVED or key.startswith("_"):
                continue
            payload[key] = REDACTED if _SENSITIVE_KEY.search(key) else value
        if record.exc_info and record.exc_info[0] is not None:
            # Type and frames only. Exception messages can echo user content (a validation
            # error quoting a transcript, for example), so the message is never logged.
            payload["exc_type"] = record.exc_info[0].__name__
            payload["stack"] = "".join(traceback.format_tb(record.exc_info[2]))
        return json.dumps(payload, default=str)


def configure_logging(level: str) -> logging.Logger:
    """Configure the `app` logger tree. Idempotent, so repeated app creation in tests is safe."""
    logger = logging.getLogger("app")
    logger.setLevel(level)
    logger.handlers = [h for h in logger.handlers if h.get_name() != _HANDLER_NAME]
    handler = logging.StreamHandler()
    handler.set_name(_HANDLER_NAME)
    handler.setFormatter(JsonFormatter())
    logger.addHandler(handler)
    logger.propagate = False
    return logger
