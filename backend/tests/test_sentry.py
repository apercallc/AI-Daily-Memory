from typing import Any, cast

from pydantic import SecretStr
from sentry_sdk.types import Event

from app.config import Settings
from app.sentry import initialize_sentry, scrub_sentry_event


def test_scrub_sentry_event_keeps_stack_but_removes_content_and_identifiers() -> None:
    event: dict[str, Any] = {
        "message": "private text",
        "request": {"url": "/search?q=private", "data": {"transcript": "private"}},
        "user": {"id": "private-user", "email": "private@example.com"},
        "contexts": {"device": {"model": "private-device"}},
        "breadcrumbs": [{"message": "private action"}],
        "extra": {"memory": "private"},
        "tags": {"request_id": "private-id"},
        "transaction": "/users/private-user",
        "threads": [{"stacktrace": {"frames": [{"vars": {"private": "content"}}]}}],
        "culprit": "/users/private-user/search?q=private",
        "server_name": "private-host.example",
        "debug_meta": {"images": [{"code_file": "/private/build/source.py"}]},
        "release": "backend@abc123",
        "exception": {
            "values": [
                {
                    "type": "RuntimeError",
                    "value": "private transcript text",
                    "stacktrace": {
                        "frames": [
                            {
                                "filename": "/private/home/user/app/api.py",
                                "abs_path": "/private/home/user/app/api.py",
                                "lineno": 42,
                                "vars": {"transcript": "private"},
                                "context_line": "private source content",
                            }
                        ]
                    },
                }
            ]
        },
    }

    scrubbed = scrub_sentry_event(cast(Event, event), {})

    assert scrubbed is not None
    assert scrubbed["release"] == "backend@abc123"
    assert scrubbed["exception"]["values"][0]["type"] == "RuntimeError"
    assert scrubbed["exception"]["values"][0]["stacktrace"]["frames"][0]["filename"] == "api.py"
    assert scrubbed["exception"]["values"][0]["stacktrace"]["frames"][0]["lineno"] == 42
    assert "message" not in scrubbed
    assert "request" not in scrubbed
    assert "user" not in scrubbed
    assert "contexts" not in scrubbed
    assert "breadcrumbs" not in scrubbed
    assert "extra" not in scrubbed
    assert "tags" not in scrubbed
    assert "transaction" not in scrubbed
    assert "threads" not in scrubbed
    assert "culprit" not in scrubbed
    assert "server_name" not in scrubbed
    assert "debug_meta" not in scrubbed
    assert "value" not in scrubbed["exception"]["values"][0]
    frame = scrubbed["exception"]["values"][0]["stacktrace"]["frames"][0]
    assert "abs_path" not in frame
    assert "vars" not in frame
    assert "context_line" not in frame


def test_sentry_initialization_is_disabled_without_a_dsn(monkeypatch: Any) -> None:
    def fail_if_initialized(**kwargs: Any) -> None:
        raise AssertionError("Sentry must stay disabled without a DSN")

    monkeypatch.setattr("app.sentry.sentry_sdk.init", fail_if_initialized)

    assert initialize_sentry(Settings(app_env="test")) is False


def test_sentry_initialization_uses_privacy_safe_error_only_options(monkeypatch: Any) -> None:
    received: dict[str, Any] = {}

    def record_init(**kwargs: Any) -> None:
        received.update(kwargs)

    monkeypatch.setattr("app.sentry.sentry_sdk.init", record_init)

    initialized = initialize_sentry(
        Settings(
            app_env="production",
            sentry_dsn=SecretStr("https://public@example.ingest.sentry.io/1"),
        )
    )

    assert initialized is True
    assert received["dsn"] == "https://public@example.ingest.sentry.io/1"
    assert received["environment"] == "production"
    assert received["send_default_pii"] is False
    assert received["max_breadcrumbs"] == 0
    assert received["traces_sample_rate"] == 0.0
    assert received["before_send"] is scrub_sentry_event
