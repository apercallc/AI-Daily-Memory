import json
import re
from collections.abc import Iterator
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.mail import ResendMailer
from app.main import create_app
from tests.conftest import make_account, settings_for
from tests.test_web_api import PASSWORD, web_session


class Outbox:
    def __init__(self) -> None:
        self.sent: list[dict[str, str]] = []
        self.ok = True

    async def send(self, *, to: str, subject: str, text: str) -> bool:
        self.sent.append({"to": to, "subject": subject, "text": text})
        return self.ok

    def token(self, index: int = -1) -> str:
        found = re.search(r"token=([\w-]+)", self.sent[index]["text"])
        assert found
        return found.group(1)


@pytest.fixture
def outbox() -> Outbox:
    return Outbox()


@pytest.fixture
def client(db: Engine, database_url: str, outbox: Outbox) -> Iterator[TestClient]:
    with TestClient(create_app(settings_for(database_url), mailer=outbox)) as c:
        yield c


# --- email: reset -----------------------------------------------------------------------------


def test_signup_sends_a_verification_link_and_it_confirms_the_address(
    client: TestClient, outbox: Outbox
) -> None:
    headers = web_session(client)
    assert outbox.sent[0]["subject"].startswith("Confirm your email")
    assert client.get("/v1/me", headers=headers).json()["email_verified"] is False

    assert client.post("/v1/verify", json={"token": outbox.token()}).status_code == 204

    assert client.get("/v1/me", headers=headers).json()["email_verified"] is True
    assert client.post("/v1/verify", json={"token": outbox.token()}).status_code == 400


def test_a_reset_link_sets_a_new_password_once_and_ends_browser_sessions_and_keys(
    client: TestClient, outbox: Outbox, db: Engine
) -> None:
    mine = web_session(client)
    key = client.post("/v1/api-keys", json={"name": "k"}, headers=mine).json()["token"]
    phone = client.post(
        "/v1/login", json={"email": "me@example.com", "password": PASSWORD, "device_name": "Pixel"}
    ).json()["token"]

    forgot = client.post("/v1/password/forgot", json={"email": "ME@example.com"})
    assert forgot.status_code == 202
    token = outbox.token()
    reset = client.post(
        "/v1/password/reset", json={"token": token, "new_password": "brand new passphrase"}
    )

    assert reset.status_code == 204
    assert client.get("/v1/me", headers=mine).status_code == 401
    assert (
        client.get("/v1/memory/items", headers={"Authorization": f"Bearer {key}"}).status_code
        == 401
    )
    assert client.get("/v1/me", headers={"Authorization": f"Bearer {phone}"}).status_code == 200
    login = client.post(
        "/v1/login",
        json={"email": "me@example.com", "password": "brand new passphrase", "device_name": "x"},
    )
    assert login.status_code == 200
    again = client.post(
        "/v1/password/reset", json={"token": token, "new_password": "another new passphrase"}
    )
    assert again.status_code == 400
    assert again.json()["error"]["code"] == "invalid_link"


def test_forgot_never_reveals_who_has_an_account(client: TestClient, outbox: Outbox) -> None:
    web_session(client)
    outbox.sent.clear()

    known = client.post("/v1/password/forgot", json={"email": "me@example.com"})
    unknown = client.post("/v1/password/forgot", json={"email": "nobody@example.com"})

    assert known.status_code == unknown.status_code == 202
    assert known.json() == unknown.json()
    assert [m["to"] for m in outbox.sent] == ["me@example.com"]


def test_a_weak_new_password_does_not_use_up_the_link(client: TestClient, outbox: Outbox) -> None:
    web_session(client)
    client.post("/v1/password/forgot", json={"email": "me@example.com"})
    token = outbox.token()

    weak = client.post("/v1/password/reset", json={"token": token, "new_password": "short"})
    ok = client.post(
        "/v1/password/reset", json={"token": token, "new_password": "a good long passphrase"}
    )

    assert (weak.status_code, ok.status_code) == (400, 204)


def test_an_older_reset_link_stops_working_when_a_newer_one_is_issued(
    client: TestClient, outbox: Outbox
) -> None:
    web_session(client)
    client.post("/v1/password/forgot", json={"email": "me@example.com"})
    first = outbox.token()
    client.post("/v1/password/forgot", json={"email": "me@example.com"})

    stale = client.post(
        "/v1/password/reset", json={"token": first, "new_password": "a good long passphrase"}
    )
    fresh = client.post(
        "/v1/password/reset",
        json={"token": outbox.token(), "new_password": "a good long passphrase"},
    )

    assert (stale.status_code, fresh.status_code) == (400, 204)


def test_an_expired_or_wrong_purpose_or_garbage_token_is_refused(
    client: TestClient, outbox: Outbox, db: Engine
) -> None:
    web_session(client)
    verify_token = outbox.token()
    assert (
        client.post(
            "/v1/password/reset",
            json={"token": verify_token, "new_password": "a good long passphrase"},
        ).status_code
        == 400
    )
    with db.begin() as c:
        c.execute(text("UPDATE email_tokens SET expires_at = now() - interval '1 minute'"))
    assert client.post("/v1/verify", json={"token": verify_token}).status_code == 400
    assert client.post("/v1/verify", json={"token": "x"}).status_code == 400
    assert client.post("/v1/verify", json={"token": "x" * 99}).status_code == 400


def test_resending_verification_needs_a_session_and_an_address(
    client: TestClient, outbox: Outbox, db: Engine
) -> None:
    headers = web_session(client)
    outbox.sent.clear()
    assert client.post("/v1/me/verify", headers=headers).status_code == 202
    assert len(outbox.sent) == 1
    assert client.post("/v1/me/verify").status_code == 401
    operator = make_account(db)
    assert client.post("/v1/me/verify", headers=operator.headers).status_code == 400


def test_mail_failure_does_not_fail_sign_up_and_without_a_provider_flows_say_so(
    db: Engine, database_url: str, outbox: Outbox
) -> None:
    outbox.ok = False
    with TestClient(create_app(settings_for(database_url), mailer=outbox)) as flaky:
        assert (
            flaky.post(
                "/v1/signup",
                json={"email": "a@example.com", "password": PASSWORD, "device_name": "W"},
            ).status_code
            == 201
        )
    with TestClient(create_app(settings_for(database_url))) as plain:
        assert plain.get("/v1/capabilities").json() == {"email": False}
        refused = plain.post("/v1/password/forgot", json={"email": "a@example.com"})
        assert refused.status_code == 503
        assert refused.json()["error"]["code"] == "email_unavailable"


def test_capabilities_report_what_is_configured(client: TestClient) -> None:
    assert client.get("/v1/capabilities").json() == {"email": True}


@pytest.mark.anyio
async def test_the_resend_mailer_reports_success_and_failure_without_raising() -> None:
    seen: list[dict[str, Any]] = []

    def respond(request: httpx.Request) -> httpx.Response:
        seen.append(json.loads(request.content))
        return httpx.Response(200 if len(seen) == 1 else 422, json={})

    mailer = ResendMailer("k", "me <a@b.c>", transport=httpx.MockTransport(respond))
    assert await mailer.send(to="x@y.z", subject="s", text="t") is True
    assert seen[0]["to"] == ["x@y.z"]
    assert await mailer.send(to="x@y.z", subject="s", text="t") is False

    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down")

    assert (
        await ResendMailer("k", "s", transport=httpx.MockTransport(boom)).send(
            to="a", subject="s", text="t"
        )
        is False
    )
