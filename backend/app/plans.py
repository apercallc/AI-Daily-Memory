"""What each plan allows. The server decides; no client state is consulted (AGENTS.md rule 3)."""

from dataclasses import dataclass

from app.config import Settings


@dataclass(frozen=True)
class Limits:
    ask_per_day: int
    upload_bytes_per_day: int


def limits_for(plan: str, settings: Settings) -> Limits:
    if plan == "pro":
        return Limits(
            ask_per_day=settings.pro_daily_ask_limit,
            upload_bytes_per_day=settings.daily_upload_bytes_quota
            * settings.pro_upload_quota_multiplier,
        )
    return Limits(
        ask_per_day=settings.daily_ask_limit, upload_bytes_per_day=settings.daily_upload_bytes_quota
    )


def plan_from_subscription(status: str) -> str:
    """Stripe subscription status to a plan. `past_due` keeps the paid plan: Stripe is still
    retrying the card, and cutting someone off mid-retry for a failed charge is the harsher choice.
    Anything else that isn't paying and trialing/active drops to free."""
    return "pro" if status in ("active", "trialing", "past_due") else "free"
