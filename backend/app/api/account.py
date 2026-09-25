"""The calling device's own account view.

GET    /v1/me              who am I (used by Connect to verify a token before storing it)
DELETE /v1/tokens/current  revoke the calling token (Disconnect; AGENTS rule 4)
"""

from typing import Annotated, Literal

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.auth import Principal, require, revoke_token
from app.db import get_session
from app.db.models import User
from app.errors import ErrorResponse

router = APIRouter(tags=["account"])

Session = Annotated[AsyncSession, Depends(get_session)]
Device = Annotated[Principal, Depends(require("upload:write"))]
_ERRORS: dict[int | str, dict[str, object]] = {s: {"model": ErrorResponse} for s in (401, 403)}


class Me(BaseModel):
    storage_mode: Literal["phone_only", "reports_cloud", "full_cloud"]
    scopes: list[str]


@router.get("/me", response_model=Me, responses=_ERRORS)
async def me(session: Session, device: Device) -> Me:
    mode = await session.scalar(select(User.storage_mode).where(User.id == device.user_id))
    return Me(storage_mode=mode, scopes=sorted(device.scopes))  # type: ignore[arg-type]


@router.delete("/tokens/current", status_code=204, responses=_ERRORS)
async def revoke_current(session: Session, device: Device) -> Response:
    await revoke_token(session, device.user_id, device.token_id)
    await session.commit()
    return Response(status_code=204)
