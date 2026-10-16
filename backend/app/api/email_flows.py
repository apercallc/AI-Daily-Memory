"""Forgotten password and address verification, by emailed single-use links.

    POST /v1/password/forgot   {email}                 always 202 (never says who has an account)
    POST /v1/password/reset    {token, new_password}   new password; ends browser sessions and keys
    POST /v1/me/verify                                 send the verification email again
    POST /v1/verify            {token}                 confirms the address
    GET  /v1/capabilities                              which optional features this server offers

Mail is sent after the response, so timing does not tell a caller whether an address exists. When
no mail provider is configured, the flows answer 503 `email_unavailable` and the web app hides
them instead of promising mail that cannot be sent.
"""

import logging
from datetime import UTC, datetime
from typing import Annotated

from fastapi import APIRouter, BackgroundTasks, Depends, Request, Response
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy import update
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require, revoke_web_and_api_sessions
from app.auth_rate_limit import auth_rate_limit
from app.config import Settings
from app.credentials import SignupError, email_of, set_password, user_for_email
from app.db import get_session
from app.db.models import Credential
from app.email_tokens import consume, issue
from app.errors import ErrorResponse, error_response
from app.mail import Mailer

logger = logging.getLogger("app.email")
router = APIRouter(tags=["email"])

Session = Annotated[AsyncSession, Depends(get_session)]
Owner = Annotated[Principal, Depends(require("upload:write", "account:manage"))]
_ERRORS: dict[int | str, dict[str, object]] = {
    s: {"model": ErrorResponse} for s in (400, 401, 429, 503)
}


class Forgot(BaseModel):
    model_config = ConfigDict(extra="forbid")

    email: str = Field(min_length=3, max_length=320)


class Reset(BaseModel):
    model_config = ConfigDict(extra="forbid")

    token: str = Field(min_length=1, max_length=200)
    new_password: str = Field(min_length=1, max_length=256)


class TokenOnly(BaseModel):
    model_config = ConfigDict(extra="forbid")

    token: str = Field(min_length=1, max_length=200)


class Capabilities(BaseModel):
    email: bool


def _mailer(request: Request) -> Mailer | None:
    return getattr(request.app.state, "mailer", None)


def _unavailable() -> JSONResponse:
    return error_response(503, "Email isn't set up on this server.", code="email_unavailable")


async def send_link(
    mailer: Mailer, settings: Settings, *, to: str, purpose: str, token: str
) -> None:
    base = settings.public_base_url.rstrip("/")
    if purpose == "reset":
        subject = "Reset your AI Daily Memory password"
        body = (
            f"Use this link within an hour to choose a new password:\n\n"
            f"{base}/app/#/reset?token={token}\n\n"
            "If you didn't ask for this, ignore this email; your password is unchanged."
        )
    else:
        subject = "Confirm your email for AI Daily Memory"
        body = (
            f"Confirm this address so you can recover your account:\n\n"
            f"{base}/app/#/verify?token={token}\n\n"
            "If you didn't create an account, ignore this email."
        )
    if not await mailer.send(to=to, subject=subject, text=body):
        logger.warning("email_not_sent", extra={"purpose": purpose})


@router.get("/capabilities", response_model=Capabilities)
async def capabilities(request: Request) -> Capabilities:
    return Capabilities(email=_mailer(request) is not None)


@router.post(
    "/password/forgot",
    status_code=202,
    response_model=None,
    responses=_ERRORS,
    dependencies=[Depends(auth_rate_limit("forgot", limit=5, window_seconds=3_600))],
)
async def forgot(
    body: Forgot, request: Request, session: Session, background: BackgroundTasks
) -> Response | JSONResponse:
    mailer = _mailer(request)
    if mailer is None:
        return _unavailable()
    user_id = await user_for_email(session, body.email)
    if user_id is not None:
        token = await issue(session, user_id, "reset")
        await session.commit()
        background.add_task(
            send_link,
            mailer,
            request.app.state.settings,
            to=body.email.strip().lower(),
            purpose="reset",
            token=token,
        )
    return JSONResponse({"ok": True}, status_code=202)


@router.post(
    "/password/reset",
    status_code=204,
    response_model=None,
    responses=_ERRORS,
    dependencies=[Depends(auth_rate_limit("reset", limit=10, window_seconds=3_600))],
)
async def reset(body: Reset, session: Session) -> Response | JSONResponse:
    # Check the password first so a typo doesn't burn the single-use link.
    if not 12 <= len(body.new_password) <= 256:
        return error_response(400, code="weak_password")
    user_id = await consume(session, body.token, "reset")
    if user_id is None:
        await session.rollback()
        return error_response(
            400, "That link has expired or was already used.", code="invalid_link"
        )
    try:
        await set_password(session, user_id, body.new_password)
    except SignupError as e:
        await session.rollback()
        return error_response(400, code=e.code)
    # A reset usually means trouble: end browser sessions and API keys. Phones stay connected (they
    # hold recordings' keys) and are listed for the person to review.
    await revoke_web_and_api_sessions(session, user_id)
    await session.commit()
    return Response(status_code=204)


@router.post(
    "/me/verify",
    status_code=202,
    response_model=None,
    responses=_ERRORS,
    dependencies=[Depends(auth_rate_limit("verify_send", limit=3, window_seconds=3_600))],
)
async def send_verification(
    request: Request, session: Session, owner: Owner, background: BackgroundTasks
) -> Response | JSONResponse:
    mailer = _mailer(request)
    if mailer is None:
        return _unavailable()
    address = await email_of(session, owner.user_id)
    if address is None:
        return error_response(400, "This account has no email address.", code="no_email")
    token = await issue(session, owner.user_id, "verify")
    await session.commit()
    background.add_task(
        send_link, mailer, request.app.state.settings, to=address, purpose="verify", token=token
    )
    return JSONResponse({"ok": True}, status_code=202)


@router.post(
    "/verify",
    status_code=204,
    response_model=None,
    responses=_ERRORS,
    dependencies=[Depends(auth_rate_limit("verify", limit=20, window_seconds=3_600))],
)
async def verify(body: TokenOnly, session: Session) -> Response | JSONResponse:
    user_id = await consume(session, body.token, "verify")
    if user_id is None:
        await session.rollback()
        return error_response(
            400, "That link has expired or was already used.", code="invalid_link"
        )
    await session.execute(
        update(Credential)
        .where(Credential.user_id == user_id, Credential.email_verified_at.is_(None))
        .values(email_verified_at=datetime.now(UTC))
    )
    await session.commit()
    return Response(status_code=204)
