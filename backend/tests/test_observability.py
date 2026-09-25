import json
import logging
import sys

import pytest

from app.observability import (
    REDACTED,
    JsonFormatter,
    configure_logging,
    is_sensitive_key,
    redact,
    request_id_var,
)


def _record(**extra: object) -> logging.LogRecord:
    record = logging.makeLogRecord({"name": "app.test", "levelname": "INFO", "msg": "event"})
    record.__dict__.update(extra)
    return record


def _format(record: logging.LogRecord) -> dict[str, object]:
    parsed: dict[str, object] = json.loads(JsonFormatter().format(record))
    return parsed


def test_sensitive_fields_are_redacted_by_name() -> None:
    out = _format(
        _record(
            transcript_text="private words",
            access_token="abc",  # noqa: S106 - fake value for the redaction test
            api_key="k",
            authorization="Bearer x",
            prompt="system",
            request_body="{}",
            passwords=["p"],
        )
    )

    for key in (
        "transcript_text",
        "access_token",
        "api_key",
        "authorization",
        "prompt",
        "request_body",
        "passwords",
    ):
        assert out[key] == REDACTED, key


@pytest.mark.parametrize(
    "key",
    [
        "accessToken",
        "transcription",
        "credentials",
        "private_key",
        "privateKey",
        "bearer",
        "set-cookie",
        "query",
        "X-Api-Key",
        "apiKey",
        "userPassword",
        "TRANSCRIPT_TEXT",
    ],
)
def test_sensitive_key_variants_are_detected(key: str) -> None:
    assert is_sensitive_key(key)


def test_nested_values_under_safe_keys_are_redacted() -> None:
    out = _format(
        _record(payload={"transcript": "words", "meta": [{"prompt": "p", "n": 1}], "ok": 2})
    )

    assert out["payload"] == {
        "transcript": REDACTED,
        "meta": [{"prompt": REDACTED, "n": 1}],
        "ok": 2,
    }


def test_pathologically_deep_values_are_cut_off() -> None:
    deep: object = "leaf"
    for _ in range(10):
        deep = {"level": deep}

    assert "leaf" not in json.dumps(redact(deep))


def test_message_arguments_are_never_interpolated() -> None:
    record = logging.makeLogRecord(
        {"name": "app.test", "levelname": "INFO", "msg": "got %s", "args": ("private words",)}
    )

    out = _format(record)

    assert out["msg"] == "got %s"
    assert "private words" not in json.dumps(out)


def test_similar_but_safe_fields_are_kept() -> None:
    out = _format(_record(context="timeline", duration_ms=12.5, status=200, tokenizer="bpe"))

    assert out["context"] == "timeline"
    assert out["duration_ms"] == 12.5
    assert out["status"] == 200
    assert out["tokenizer"] == "bpe"


def test_request_id_is_attached_when_present() -> None:
    token = request_id_var.set("req-1")
    try:
        assert _format(_record())["request_id"] == "req-1"
    finally:
        request_id_var.reset(token)
    assert "request_id" not in _format(_record())


def test_exceptions_log_type_and_frames_but_never_the_message() -> None:
    private_speech = "the transcript said something private"
    try:
        raise ValueError(private_speech)
    except ValueError:
        record = _record(exc_info=sys.exc_info())

    out = _format(record)

    assert out["exc_type"] == "ValueError"
    assert "test_observability.py" in str(out["stack"])
    assert "said something" not in json.dumps(out)


def test_sets_become_lists_and_unknown_objects_are_stringified() -> None:
    out = _format(_record(ids={1}, when=object()))

    assert out["ids"] == [1]
    assert str(out["when"]).startswith("<object object")


def test_configure_logging_is_idempotent() -> None:
    logger = configure_logging("DEBUG")
    configure_logging("WARNING")

    ours = [h for h in logger.handlers if h.get_name() == "app-json"]
    assert len(ours) == 1
    assert logger.level == logging.WARNING
    assert logger.propagate is False
    uvicorn_error = logging.getLogger("uvicorn.error")
    assert [h.get_name() for h in uvicorn_error.handlers] == ["app-json"]
    assert logging.getLogger("uvicorn.access").disabled is True
