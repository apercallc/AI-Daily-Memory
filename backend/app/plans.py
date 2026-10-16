"""What the server allows per person per day (ADR-0028: no plans, no entitlements — these are
abuse/availability limits, not plan metering). The server decides; no client state is
consulted (AGENTS.md rule 3)."""

from dataclasses import dataclass

from app.config import Settings


@dataclass(frozen=True)
class Limits:
    ask_per_day: int
    upload_bytes_per_day: int


def limits_for(settings: Settings) -> Limits:
    return Limits(
        ask_per_day=settings.daily_ask_limit,
        upload_bytes_per_day=settings.daily_upload_bytes_quota,
    )
