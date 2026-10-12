"""Privacy-limited error reporting for the API and worker."""

import os
from typing import Any, cast

import sentry_sdk
from sentry_sdk.integrations.fastapi import FastApiIntegration
from sentry_sdk.integrations.logging import LoggingIntegration
from sentry_sdk.types import Event, Hint

from app.config import Settings

_REDACTED_EVENT_FIELDS = (
    "breadcrumbs",
    "contexts",
    "culprit",
    "debug_meta",
    "extra",
    "logentry",
    "message",
    "request",
    "server_name",
    "spans",
    "tags",
    "threads",
    "transaction",
    "user",
)


def scrub_sentry_event(event: Event, _hint: Hint) -> Event | None:
    """Keep exception type and stack locations; remove private content and request context."""
    event_data = cast(dict[str, Any], event)
    for field in _REDACTED_EVENT_FIELDS:
        event_data.pop(field, None)

    exception = event_data.get("exception")
    if isinstance(exception, dict):
        values = exception.get("values")
        if isinstance(values, list):
            for value in values:
                if not isinstance(value, dict):
                    continue
                value.pop("value", None)
                stacktrace = value.get("stacktrace")
                if not isinstance(stacktrace, dict):
                    continue
                frames = stacktrace.get("frames")
                if not isinstance(frames, list):
                    continue
                for frame in frames:
                    if isinstance(frame, dict):
                        filename = frame.get("filename")
                        if isinstance(filename, str):
                            normalized_path = filename.replace("\\", "/")
                            if normalized_path.startswith("/") or (
                                len(normalized_path) > 2 and normalized_path[1:3] == ":/"
                            ):
                                frame["filename"] = os.path.basename(normalized_path)
                        for field in (
                            "abs_path",
                            "vars",
                            "pre_context",
                            "context_line",
                            "post_context",
                        ):
                            frame.pop(field, None)

    return cast(Event, event_data)


def initialize_sentry(settings: Settings) -> bool:
    """Enable errors only when configured; never send private request or user data."""
    if settings.sentry_dsn is None:
        return False

    dsn = settings.sentry_dsn.get_secret_value().strip()
    if not dsn:
        return False

    sentry_sdk.init(
        dsn=dsn,
        environment=settings.app_env,
        release=(
            os.environ.get("SENTRY_RELEASE")
            or os.environ.get("RAILWAY_GIT_COMMIT_SHA")
            or "ai-daily-memory-backend@0.1.0"
        ),
        integrations=[FastApiIntegration()],
        disabled_integrations=[LoggingIntegration],
        send_default_pii=False,
        max_breadcrumbs=0,
        traces_sample_rate=0.0,
        profiles_sample_rate=0.0,
        enable_logs=False,
        before_send=scrub_sentry_event,
    )
    return True


def capture_unhandled_error(error: BaseException) -> None:
    """Capture only explicit unexpected failures; expected domain errors stay local."""
    sentry_sdk.capture_exception(error)
