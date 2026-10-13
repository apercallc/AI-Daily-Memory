"""Subscription (ADR-0025). Entitlements are server-authoritative: only Stripe's signed webhook
moves a person's plan; the browser never claims one.

    GET  /v1/billing            current plan, limits, and what was used today
    POST /v1/billing/checkout   start a subscription (returns a Stripe Checkout URL)
    POST /v1/billing/portal     manage or cancel it (returns a Stripe portal URL)
    POST /v1/billing/webhook    Stripe's events, signature-checked, idempotent
"""

import json
import logging
import uuid
from datetime import UTC, datetime
from typing import Annotated, Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require
from app.billing import Stripe, StripeError, verify_signature
from app.config import Settings
from app.credentials import email_of
from app.db import get_session
from app.db.models import UsageCounter, User
from app.errors import ErrorResponse, error_response
from app.plans import limits_for, plan_from_subscription

logger = logging.getLogger("app.billing")
router = APIRouter(tags=["billing"])

Session = Annotated[AsyncSession, Depends(get_session)]
Owner = Annotated[Principal, Depends(require("upload:write", "account:manage"))]
_ERRORS: dict[int | str, dict[str, object]] = {
    s: {"model": ErrorResponse} for s in (400, 401, 409, 502, 503)
}


class BillingOut(BaseModel):
    plan: str
    billing_enabled: bool
    renews_at: datetime | None
    cancels_at_period_end: bool
    ask_limit: int
    asks_used: int
    upload_limit_bytes: int


class UrlOut(BaseModel):
    url: str


def _stripe(request: Request) -> Stripe | None:
    return getattr(request.app.state, "stripe", None)


@router.get("/billing", response_model=BillingOut, responses=_ERRORS)
async def billing(request: Request, session: Session, owner: Owner) -> BillingOut:
    settings: Settings = request.app.state.settings
    user = await session.get(User, owner.user_id)
    plan = user.plan if user else "free"
    limits = limits_for(plan, settings)
    used = await session.scalar(
        select(UsageCounter.count).where(
            UsageCounter.user_id == owner.user_id,
            UsageCounter.day == datetime.now(UTC).date(),
            UsageCounter.kind == "ask",
        )
    )
    return BillingOut(
        plan=plan,
        billing_enabled=settings.billing_enabled,
        renews_at=user.plan_renews_at if user else None,
        cancels_at_period_end=bool(user and user.plan_cancels_at_period_end),
        ask_limit=limits.ask_per_day,
        asks_used=used or 0,
        upload_limit_bytes=limits.upload_bytes_per_day,
    )


def _provider_error() -> JSONResponse:
    return error_response(
        502, "Couldn't reach the payment provider.", code="billing_provider_error"
    )


def _unavailable() -> JSONResponse:
    return error_response(
        503, "Subscriptions aren't available right now.", code="billing_unavailable"
    )


@router.post("/billing/checkout", response_model=UrlOut, responses=_ERRORS)
async def checkout(request: Request, session: Session, owner: Owner) -> UrlOut | JSONResponse:
    settings: Settings = request.app.state.settings
    stripe = _stripe(request)
    if stripe is None or not settings.billing_enabled:
        return _unavailable()
    user = await session.get(User, owner.user_id)
    if user is None:
        return error_response(401, code="unauthorized")
    if user.plan == "pro":
        return error_response(409, "You already have a subscription.", code="already_subscribed")
    base = settings.public_base_url.rstrip("/")
    data = {
        "mode": "subscription",
        "line_items[0][price]": settings.stripe_price_id or "",
        "line_items[0][quantity]": "1",
        "client_reference_id": str(owner.user_id),
        "success_url": f"{base}/app/#/account?billing=success",
        "cancel_url": f"{base}/app/#/account?billing=cancelled",
    }
    if user.stripe_customer_id:
        data["customer"] = user.stripe_customer_id
    else:
        address = await email_of(session, owner.user_id)
        if address:
            data["customer_email"] = address
    try:
        created = await stripe.post("/checkout/sessions", data)
    except StripeError as error:
        logger.warning("stripe_checkout_failed", extra={"error": str(error)})
        return error_response(
            502, "Couldn't reach the payment provider.", code="billing_provider_error"
        )
    url = created.get("url")
    if not isinstance(url, str) or not url.startswith("https://"):
        return error_response(
            502, "Couldn't reach the payment provider.", code="billing_provider_error"
        )
    return UrlOut(url=url)


@router.post("/billing/portal", response_model=UrlOut, responses=_ERRORS)
async def portal(request: Request, session: Session, owner: Owner) -> UrlOut | JSONResponse:
    settings: Settings = request.app.state.settings
    stripe = _stripe(request)
    if stripe is None or not settings.billing_enabled:
        return _unavailable()
    user = await session.get(User, owner.user_id)
    if user is None or not user.stripe_customer_id:
        return error_response(409, "There's no subscription to manage.", code="no_subscription")
    try:
        created = await stripe.post(
            "/billing_portal/sessions",
            {
                "customer": user.stripe_customer_id,
                "return_url": f"{settings.public_base_url.rstrip('/')}/app/#/account",
            },
        )
    except StripeError as error:
        logger.warning("stripe_portal_failed", extra={"error": str(error)})
        return error_response(
            502, "Couldn't reach the payment provider.", code="billing_provider_error"
        )
    url = created.get("url")
    if not isinstance(url, str) or not url.startswith("https://"):
        return error_response(
            502, "Couldn't reach the payment provider.", code="billing_provider_error"
        )
    return UrlOut(url=url)


def _from_unix(value: object) -> datetime | None:
    return datetime.fromtimestamp(value, UTC) if isinstance(value, int) else None


async def _apply_subscription(session: AsyncSession, customer: str, sub: dict[str, Any]) -> None:
    """Sets the plan from the subscription's current state. Idempotent: replaying an event, or
    receiving events out of order, converges on whatever Stripe last said."""
    status = str(sub.get("status", ""))
    plan = plan_from_subscription(status)
    await session.execute(
        update(User)
        .where(User.stripe_customer_id == customer)
        .values(
            plan=plan,
            plan_renews_at=_from_unix(sub.get("current_period_end")) if plan == "pro" else None,
            plan_cancels_at_period_end=bool(sub.get("cancel_at_period_end")) and plan == "pro",
        )
    )


@router.post("/billing/webhook", responses=_ERRORS)
async def webhook(request: Request, session: Session) -> JSONResponse:
    settings: Settings = request.app.state.settings
    secret = settings.stripe_webhook_secret
    if secret is None:
        return error_response(503, code="billing_unavailable")
    payload = await request.body()
    if not verify_signature(
        payload, request.headers.get("stripe-signature", ""), secret.get_secret_value()
    ):
        return error_response(400, "Bad signature.", code="invalid_signature")
    try:
        event = json.loads(payload)
        kind = event["type"]
        obj = event["data"]["object"]
    except (ValueError, KeyError, TypeError):
        return error_response(400, "Unreadable event.", code="invalid_event")
    if kind == "checkout.session.completed":
        try:
            user_id = uuid.UUID(str(obj.get("client_reference_id")))
        except ValueError:
            return JSONResponse({"ok": True})
        customer = obj.get("customer")
        if isinstance(customer, str):
            await session.execute(
                update(User).where(User.id == user_id).values(stripe_customer_id=customer)
            )
    elif kind in (
        "customer.subscription.created",
        "customer.subscription.updated",
        "customer.subscription.deleted",
    ):
        customer = obj.get("customer")
        if isinstance(customer, str):
            if kind.endswith("deleted"):
                obj = {**obj, "status": "canceled"}
            await _apply_subscription(session, customer, obj)
    await session.commit()
    return JSONResponse({"ok": True})
