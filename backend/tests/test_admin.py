import io
import uuid

import pytest
from sqlalchemy import Engine, text

from app.admin import run
from app.config import Settings
from tests.conftest import settings_for

pytestmark = pytest.mark.anyio


async def test_create_account_prints_a_token_once_and_stores_only_its_hash(
    db: Engine,
    database_url: str,
) -> None:
    out = io.StringIO()

    code = await run(["create-account", "--device-name", "Pixel"], settings_for(database_url), out)

    assert code == 0
    lines = out.getvalue().splitlines()
    user_id = uuid.UUID(lines[0].removeprefix("account: "))
    uuid.UUID(lines[1].removeprefix("token id: "))
    token = lines[2].removeprefix("device token (shown once): ")
    assert token.startswith("adm_")
    with db.connect() as connection:
        row = connection.execute(
            text("SELECT kind, scopes, token_hash FROM access_tokens WHERE user_id = :u"),
            {"u": user_id},
        ).one()
    assert row.kind == "device"
    assert row.scopes == ["upload:write", "memory:read"]
    assert token.encode() not in bytes(row.token_hash)


async def test_issue_and_revoke_for_an_existing_account(db: Engine, database_url: str) -> None:
    settings = settings_for(database_url)
    created = io.StringIO()
    await run(["create-account", "--device-name", "Pixel"], settings, created)
    user_id = created.getvalue().splitlines()[0].removeprefix("account: ")

    issued = io.StringIO()
    assert (
        await run(
            ["issue-device-token", "--user-id", user_id, "--device-name", "Watch"],
            settings,
            issued,
        )
        == 0
    )
    token_id = issued.getvalue().splitlines()[1].removeprefix("token id: ")
    listed = io.StringIO()
    assert await run(["list-tokens", "--user-id", user_id], settings, listed) == 0
    assert token_id in listed.getvalue()
    assert "active" in listed.getvalue()
    assert "adm_" not in listed.getvalue()  # never the secret
    revoke = ["revoke-token", "--user-id", user_id, "--token-id", token_id]
    first, second = io.StringIO(), io.StringIO()

    assert await run(revoke, settings, first) == 0
    assert await run(revoke, settings, second) == 1
    assert "Not found" in second.getvalue()
    relisted = io.StringIO()
    await run(["list-tokens", "--user-id", user_id], settings, relisted)
    assert f"{token_id}  device  revoked" in relisted.getvalue()


async def test_unknown_account_and_missing_database_are_reported(
    db: Engine,
    database_url: str,
) -> None:
    out = io.StringIO()
    missing = ["issue-device-token", "--user-id", str(uuid.uuid4()), "--device-name", "x"]

    assert await run(missing, settings_for(database_url), out) == 1
    assert "No such account" in out.getvalue()
    assert await run(missing, Settings(app_env="test"), io.StringIO()) == 2
