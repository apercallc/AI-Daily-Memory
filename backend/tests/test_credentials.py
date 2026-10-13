import asyncio
from collections.abc import AsyncIterator
from typing import Any

import pytest
from sqlalchemy import Engine, text

from app.credentials import _BACKSTOP_FAILURES as BACKSTOP_FAILURES
from app.credentials import _MAX_FAILED_ATTEMPTS as MAX_FAILED_ATTEMPTS
from app.credentials import LoggedIn, LoginError, SignedUp, SignupError, log_in, sign_up
from app.db import Database

pytestmark = pytest.mark.anyio


@pytest.fixture
async def database(db: Engine, database_url: str) -> AsyncIterator[Database]:
    database = Database(database_url)
    yield database
    await database.dispose()


async def _sign_up(database: Database, **kwargs: Any) -> SignedUp:
    async with database.sessions() as session:
        try:
            signed_up = await sign_up(session, **kwargs)
        except SignupError:
            await session.rollback()
            raise
        await session.commit()
        return signed_up


async def _log_in(database: Database, **kwargs: Any) -> LoggedIn:
    kwargs.setdefault("source", "source-a")
    """Mirrors app/api/signup.py: a failed attempt still commits (it bumps the lockout state)."""
    async with database.sessions() as session:
        try:
            logged_in = await log_in(session, **kwargs)
        except LoginError:
            await session.commit()
            raise
        await session.commit()
        return logged_in


async def test_signing_up_creates_an_account_and_issues_a_device_token(
    database: Database,
) -> None:
    signed_up = await _sign_up(
        database,
        email="Alice@Example.com",
        password="correct horse battery",  # noqa: S106 - fake credential for the test
        device_name="Pixel",
    )

    assert signed_up.token.token.startswith("adm_")
    assert signed_up.user_id is not None


async def test_email_is_normalized_and_case_insensitively_unique(database: Database) -> None:
    await _sign_up(database, email="Bob@Example.com", password="x" * 12, device_name="a")

    with pytest.raises(SignupError) as caught:
        await _sign_up(database, email="bob@example.com", password="y" * 12, device_name="b")
    assert caught.value.code == "email_taken"

    # The failed attempt left nothing half-created: signing up under a third email still works.
    third = await _sign_up(
        database, email="not-bob@example.com", password="z" * 12, device_name="c"
    )
    assert third.token.token.startswith("adm_")


@pytest.mark.parametrize(
    ("email", "password", "code"),
    [
        ("not-an-email", "x" * 12, "invalid_email"),
        ("", "x" * 12, "invalid_email"),
        ("a@b.com", "short", "weak_password"),
        ("a@b.com", "x" * 300, "weak_password"),
    ],
)
async def test_invalid_input_is_rejected_before_anything_is_created(
    database: Database, email: str, password: str, code: str
) -> None:
    with pytest.raises(SignupError) as caught:
        await _sign_up(database, email=email, password=password, device_name="a")
    assert caught.value.code == code


async def test_logging_in_issues_a_new_token_each_time(database: Database) -> None:
    await _sign_up(database, email="carol@example.com", password="x" * 12, device_name="phone-1")

    first = await _log_in(
        database, email="carol@example.com", password="x" * 12, device_name="phone-2"
    )
    second = await _log_in(
        database, email="CAROL@example.com", password="x" * 12, device_name="phone-3"
    )

    assert first.token.token != second.token.token
    assert first.user_id == second.user_id


async def test_a_wrong_password_and_an_unknown_email_report_the_same_error(
    database: Database,
) -> None:
    await _sign_up(database, email="dave@example.com", password="x" * 12, device_name="a")

    with pytest.raises(LoginError) as wrong_password:
        await _log_in(database, email="dave@example.com", password="y" * 12, device_name="b")
    with pytest.raises(LoginError) as unknown_email:
        await _log_in(database, email="nobody@example.com", password="y" * 12, device_name="b")

    assert wrong_password.value.code == unknown_email.value.code == "invalid_credentials"


async def test_repeated_wrong_passwords_lock_the_account(database: Database) -> None:
    await _sign_up(database, email="erin@example.com", password="x" * 12, device_name="a")

    for _ in range(MAX_FAILED_ATTEMPTS):
        with pytest.raises(LoginError) as caught:
            await _log_in(
                database,
                email="erin@example.com",
                password="wrong-pw12",  # noqa: S106 - fake credential for the test
                device_name="b",
            )
        assert caught.value.code == "invalid_credentials"

    with pytest.raises(LoginError) as locked:
        await _log_in(database, email="erin@example.com", password="x" * 12, device_name="b")
    assert locked.value.code == "account_locked"


async def test_concurrent_wrong_passwords_cannot_bypass_lockout(database: Database) -> None:
    await _sign_up(database, email="concurrent@example.com", password="x" * 12, device_name="a")

    async def wrong_attempt() -> str:
        try:
            await _log_in(
                database,
                email="concurrent@example.com",
                password="wrong-pw12",  # noqa: S106 - fake credential for the test
                device_name="b",
            )
        except LoginError as error:
            return error.code
        return "unexpected_success"

    results = await asyncio.gather(*(wrong_attempt() for _ in range(MAX_FAILED_ATTEMPTS)))
    assert results == ["invalid_credentials"] * MAX_FAILED_ATTEMPTS

    with pytest.raises(LoginError) as locked:
        await _log_in(
            database,
            email="concurrent@example.com",
            password="x" * 12,
            device_name="b",
        )
    assert locked.value.code == "account_locked"


async def test_a_correct_password_clears_the_failed_attempt_counter(database: Database) -> None:
    await _sign_up(database, email="frank@example.com", password="x" * 12, device_name="a")

    for _ in range(MAX_FAILED_ATTEMPTS - 1):
        with pytest.raises(LoginError):
            await _log_in(
                database,
                email="frank@example.com",
                password="nope-nope-1",  # noqa: S106 - fake credential for the test
                device_name="b",
            )

    await _log_in(database, email="frank@example.com", password="x" * 12, device_name="b")

    for _ in range(MAX_FAILED_ATTEMPTS - 1):
        with pytest.raises(LoginError) as caught:
            await _log_in(
                database,
                email="frank@example.com",
                password="nope-nope-1",  # noqa: S106 - fake credential for the test
                device_name="b",
            )
        assert caught.value.code == "invalid_credentials"


async def test_a_locked_account_still_pays_the_hashing_cost(
    database: Database, monkeypatch: pytest.MonkeyPatch
) -> None:
    import app.credentials as credentials_module

    await _sign_up(database, email="timing@example.com", password="x" * 12, device_name="a")
    for _ in range(MAX_FAILED_ATTEMPTS):
        with pytest.raises(LoginError):
            await _log_in(
                database,
                email="timing@example.com",
                password="wrong-pw12",  # noqa: S106
                device_name="b",
            )

    verified: list[str] = []
    real = credentials_module._hasher

    class CountingHasher:
        def verify(self, hash_: str, password: str) -> bool:
            verified.append(password)
            return real.verify(hash_, password)

    monkeypatch.setattr(credentials_module, "_hasher", CountingHasher())
    with pytest.raises(LoginError) as locked:
        await _log_in(database, email="timing@example.com", password="x" * 12, device_name="b")

    # The right password is rejected, but only after the same verify work as an open account.
    assert locked.value.code == "account_locked"
    assert verified == ["x" * 12]


async def _fail(database: Database, email: str, source: str) -> str:
    try:
        await _log_in(
            database,
            email=email,
            password="wrong-pw12",  # noqa: S106 - fake credential for the test
            device_name="b",
            source=source,
        )
    except LoginError as error:
        return error.code
    return "unexpected_success"


async def test_an_attacker_source_is_locked_but_the_legitimate_source_still_logs_in(
    database: Database,
) -> None:
    email = "victim@example.com"
    await _sign_up(database, email=email, password="x" * 12, device_name="a")

    codes = [await _fail(database, email, "attacker") for _ in range(MAX_FAILED_ATTEMPTS)]
    assert codes == ["invalid_credentials"] * MAX_FAILED_ATTEMPTS

    # Locked even with the right password...
    with pytest.raises(LoginError) as locked:
        await _log_in(database, email=email, password="x" * 12, device_name="b", source="attacker")
    assert locked.value.code == "account_locked"
    # ...while the real owner, from another source, is unaffected.
    logged_in = await _log_in(
        database, email=email, password="x" * 12, device_name="b", source="owner"
    )
    assert logged_in.token.token.startswith("adm_")


async def test_a_source_lock_expires(database: Database, db: Engine) -> None:
    email = "expiry@example.com"
    await _sign_up(database, email=email, password="x" * 12, device_name="a")
    for _ in range(MAX_FAILED_ATTEMPTS):
        await _fail(database, email, "s")
    with db.begin() as c:
        c.execute(text("UPDATE login_failures SET locked_until = now() - interval '1 minute'"))

    await _log_in(database, email=email, password="x" * 12, device_name="b", source="s")


async def test_distributed_failures_trip_a_credential_wide_backstop(database: Database) -> None:
    email = "botnet@example.com"
    await _sign_up(database, email=email, password="x" * 12, device_name="a")

    # Two failures each from many sources: no single source ever locks.
    for i in range(BACKSTOP_FAILURES // 2):
        for _ in range(2):
            assert await _fail(database, email, f"bot-{i}") == "invalid_credentials"

    with pytest.raises(LoginError) as locked:
        await _log_in(database, email=email, password="x" * 12, device_name="b", source="owner")
    assert locked.value.code == "account_locked"


async def test_success_clears_only_that_sources_counter(database: Database, db: Engine) -> None:
    email = "clear@example.com"
    await _sign_up(database, email=email, password="x" * 12, device_name="a")
    for _ in range(MAX_FAILED_ATTEMPTS - 1):
        await _fail(database, email, "owner")
        await _fail(database, email, "attacker")

    await _log_in(database, email=email, password="x" * 12, device_name="b", source="owner")

    with db.connect() as c:
        rows: dict[str, int] = dict(
            c.execute(text("SELECT source_hash, failed_attempts FROM login_failures")).all()
        )
    assert rows == {"attacker": MAX_FAILED_ATTEMPTS - 1}


async def test_idle_failure_rows_are_pruned_on_write(database: Database, db: Engine) -> None:
    email = "prune@example.com"
    await _sign_up(database, email=email, password="x" * 12, device_name="a")
    await _fail(database, email, "old")
    with db.begin() as c:
        c.execute(text("UPDATE login_failures SET updated_at = now() - interval '25 hours'"))

    await _fail(database, email, "new")

    with db.connect() as c:
        assert c.scalars(text("SELECT source_hash FROM login_failures")).all() == ["new"]
