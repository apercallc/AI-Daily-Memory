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


@pytest.mark.anyio
async def test_dead_jobs_are_listed_and_can_be_requeued_once(db: Engine, database_url: str) -> None:
    user, job = uuid.uuid4(), uuid.uuid4()
    with db.begin() as c:
        c.execute(text("INSERT INTO users (id) VALUES (:u)"), {"u": user})
        c.execute(
            text(
                "INSERT INTO processing_jobs (id, user_id, kind, idempotency_key, subject_id, "
                "state, attempts, last_error_code) VALUES (:j, :u, 'extract', 'k1', :s, 'dead', 5, "
                "'provider_auth_failed')"
            ),
            {"j": job, "u": user, "s": uuid.uuid4()},
        )
    settings = settings_for(database_url)

    listing = io.StringIO()
    assert await run(["dead-jobs"], settings, listing) == 0
    assert f"{job}  extract  provider_auth_failed  attempts 5/5" in listing.getvalue()

    first = io.StringIO()
    assert await run(["requeue-job", "--job-id", str(job)], settings, first) == 0
    assert "Requeued." in first.getvalue()
    with db.connect() as c:
        row = c.execute(text("SELECT state, attempts, last_error_code FROM processing_jobs")).one()
    assert tuple(row) == ("queued", 0, None)

    again = io.StringIO()
    assert await run(["requeue-job", "--job-id", str(job)], settings, again) == 1
    empty = io.StringIO()
    assert await run(["dead-jobs"], settings, empty) == 0
    assert empty.getvalue() == ""


@pytest.mark.anyio
async def test_dead_account_purges_are_listed_and_can_be_requeued_once(
    db: Engine, database_url: str
) -> None:
    account = uuid.uuid4()
    with db.begin() as c:
        c.execute(
            text(
                "INSERT INTO pending_account_object_deletions "
                "(account_id, purge_after, attempts, last_error_code, dead_at) "
                "VALUES (:a, now(), 10, 'RuntimeError', now())"
            ),
            {"a": account},
        )
    settings = settings_for(database_url)

    listing = io.StringIO()
    assert await run(["dead-account-purges"], settings, listing) == 0
    assert f"{account}  RuntimeError  attempts 10/10" in listing.getvalue()

    first = io.StringIO()
    assert await run(["requeue-account-purge", "--account-id", str(account)], settings, first) == 0
    assert "Requeued." in first.getvalue()
    with db.connect() as c:
        row = c.execute(
            text("SELECT attempts, last_error_code, dead_at FROM pending_account_object_deletions")
        ).one()
    assert tuple(row) == (0, None, None)

    again = io.StringIO()
    assert await run(["requeue-account-purge", "--account-id", str(account)], settings, again) == 1
    empty = io.StringIO()
    assert await run(["dead-account-purges"], settings, empty) == 0
    assert empty.getvalue() == ""
