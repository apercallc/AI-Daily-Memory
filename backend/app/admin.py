"""Operator commands until account sign-in exists (TODO Phase 1 "Add auth").

    python -m app.admin create-account --device-name "Pixel 9"
    python -m app.admin issue-device-token --user-id <uuid> --device-name "Pixel 9"
    python -m app.admin list-tokens --user-id <uuid>
    python -m app.admin revoke-token --user-id <uuid> --token-id <uuid>

The token is printed once and never stored in plaintext; paste it into the app's
"Connect account" screen. Output goes to stdout only, never to the logs.
"""

import argparse
import asyncio
import sys
import uuid
from dataclasses import dataclass
from typing import TextIO

from sqlalchemy import select

from app.auth import issue_token, revoke_token
from app.config import Settings
from app.db import Database
from app.db.models import AccessToken, User


@dataclass(frozen=True)
class Issued:
    user_id: uuid.UUID
    token_id: uuid.UUID
    token: str


async def create_account(database: Database, device_name: str) -> Issued:
    async with database.sessions() as session:
        user = User(id=uuid.uuid4())
        session.add(user)
        await session.flush()
        issued = await issue_token(session, user.id, kind="device", name=device_name)
        await session.commit()
    return Issued(user.id, issued.token_id, issued.token)


async def issue_device_token(database: Database, user_id: uuid.UUID, device_name: str) -> Issued:
    async with database.sessions() as session:
        if await session.get(User, user_id) is None:
            raise LookupError("no such account")
        issued = await issue_token(session, user_id, kind="device", name=device_name)
        await session.commit()
    return Issued(user_id, issued.token_id, issued.token)


async def list_tokens(database: Database, user_id: uuid.UUID) -> list[AccessToken]:
    async with database.sessions() as session:
        rows = await session.scalars(
            select(AccessToken)
            .where(AccessToken.user_id == user_id)
            .order_by(AccessToken.created_at),
        )
        return list(rows.all())


async def revoke(database: Database, user_id: uuid.UUID, token_id: uuid.UUID) -> bool:
    async with database.sessions() as session:
        revoked = await revoke_token(session, user_id, token_id)
        await session.commit()
    return revoked


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="python -m app.admin")
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create-account")
    create.add_argument("--device-name", required=True)
    issue = commands.add_parser("issue-device-token")
    issue.add_argument("--user-id", type=uuid.UUID, required=True)
    issue.add_argument("--device-name", required=True)
    listing = commands.add_parser("list-tokens")
    listing.add_argument("--user-id", type=uuid.UUID, required=True)
    rev = commands.add_parser("revoke-token")
    rev.add_argument("--user-id", type=uuid.UUID, required=True)
    rev.add_argument("--token-id", type=uuid.UUID, required=True)
    return parser


async def run(argv: list[str], settings: Settings, out: TextIO) -> int:
    args = _parser().parse_args(argv)
    if settings.database_url is None:
        print("DATABASE_URL is not set", file=out)
        return 2
    database = Database(settings.database_url.get_secret_value(), pool_size=1)
    try:
        if args.command == "create-account":
            issued = await create_account(database, args.device_name)
        elif args.command == "issue-device-token":
            try:
                issued = await issue_device_token(database, args.user_id, args.device_name)
            except LookupError:
                print("No such account.", file=out)
                return 1
        elif args.command == "list-tokens":
            for row in await list_tokens(database, args.user_id):
                status = "revoked" if row.revoked_at else "active"
                used = row.last_used_at.isoformat() if row.last_used_at else "never"
                print(f"{row.id}  {row.kind}  {status}  last used {used}  {row.name}", file=out)
            return 0
        else:
            done = await revoke(database, args.user_id, args.token_id)
            print("Revoked." if done else "Not found or already revoked.", file=out)
            return 0 if done else 1
    finally:
        await database.dispose()
    print(f"account: {issued.user_id}", file=out)
    print(f"token id: {issued.token_id}", file=out)
    print(f"device token (shown once): {issued.token}", file=out)
    return 0


if __name__ == "__main__":  # pragma: no cover
    sys.exit(asyncio.run(run(sys.argv[1:], Settings(), sys.stdout)))
