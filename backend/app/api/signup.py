"""Self-service sign-in (TODO Phase 1 "Account sign-in flow"): a real user's first device
token no longer requires `python -m app.admin`.

    POST /v1/signup   email + password + device name -> account + device token (shown once)
    POST /v1/login     email + password + device name -> a new device token for that account

Signing in issues a device token exactly like the admin CLI does: the "Connect account" screen
that already exists for a pasted admin-issued token now also has a self-service path to get one.
No session cookie or separate "account token" exists; every authenticated call after this is the
same bearer-token flow as any other device (`app/auth.py`).
"""

import logging
from typing import Annotated

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field
from sqlalchemy.ext.asyncio import AsyncSession

from app.credentials import LoginError, SignupError, log_in, sign_up
from app.db import get_session
from app.errors import ErrorResponse, error_response

logger = logging.getLogger("app.signup")
router = APIRouter(tags=["signup"])

Session = Annotated[AsyncSession, Depends(get_session)]
_ERRORS: dict[int | str, dict[str, object]] = {
    status: {"model": ErrorResponse} for status in (400, 401, 409, 423)
}

# Codes from app.credentials mapped to HTTP status; anything else is a bug, not a client error.
_SIGNUP_STATUS = {"invalid_email": 400, "weak_password": 400, "email_taken": 409}
_LOGIN_STATUS = {"invalid_credentials": 401, "account_locked": 423}


class SignUpRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    email: str = Field(min_length=1, max_length=320)
    password: str = Field(min_length=1, max_length=256)
    device_name: str = Field(min_length=1, max_length=80)


class LogInRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    email: str = Field(min_length=1, max_length=320)
    password: str = Field(min_length=1, max_length=256)
    device_name: str = Field(min_length=1, max_length=80)


class IssuedDeviceToken(BaseModel):
    # Shown once, like the admin CLI's output. The client stores it exactly like an
    # admin-issued token (net.LinkStore on the phone).
    token: str


@router.post("/signup", response_model=IssuedDeviceToken, status_code=201, responses=_ERRORS)
async def signup(body: SignUpRequest, session: Session) -> IssuedDeviceToken:
    try:
        signed_up = await sign_up(
            session, email=body.email, password=body.password, device_name=body.device_name
        )
    except SignupError as e:
        await session.rollback()
        status = _SIGNUP_STATUS.get(e.code, 400)
        return error_response(status, code=e.code)  # type: ignore[return-value]
    await session.commit()
    return IssuedDeviceToken(token=signed_up.token.token)


@router.post("/login", response_model=IssuedDeviceToken, responses=_ERRORS)
async def login(body: LogInRequest, session: Session) -> IssuedDeviceToken:
    try:
        logged_in = await log_in(
            session, email=body.email, password=body.password, device_name=body.device_name
        )
    except LoginError as e:
        # Commit even on failure: a wrong-password attempt still bumps failed_attempts (and
        # may set locked_until), and that must survive past this request to matter.
        await session.commit()
        status = _LOGIN_STATUS.get(e.code, 401)
        return error_response(status, code=e.code)  # type: ignore[return-value]
    await session.commit()
    return IssuedDeviceToken(token=logged_in.token.token)
