# ruff: noqa: S106
# Long literal request bodies and test-only secrets read better inline.

import hashlib
import hmac
import json
import re
import time
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import SecretStr
from sqlalchemy import Engine, text

from app.billing import StripeClient, StripeError, verify_signature
from app.config import Settings
from app.mail import ResendMailer
from app.main import create_app
from app.plans import limits_for, plan_from_subscription
from tests.conftest import make_account, settings_for
from tests.test_web_api import PASSWORD, user_of, web_session


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


class FakeStripe:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict[str, str]]] = []
        self.fail = False

    async def post(self, path: str, data: dict[str, str]) -> dict[str, Any]:
        self.calls.append((path, data))
        if self.fail:
            raise StripeError("down")
        return {"url": f"https://stripe.test{path}"}


SECRET = "whsec_test"  # noqa: S105 - test value


@pytest.fixture
def outbox() -> Outbox:
    return Outbox()


@pytest.fixture
def stripe() -> FakeStripe:
    return FakeStripe()


@pytest.fixture
def client(
    db: Engine, database_url: str, outbox: Outbox, stripe: FakeStripe
) -> Iterator[TestClient]:
    settings = settings_for(
        database_url,
        stripe_secret_key=SecretStr("sk_test"),
        stripe_webhook_secret=SecretStr(SECRET),
        stripe_price_id="price_1",
        daily_ask_limit=2,
        pro_daily_ask_limit=5,
    )
    with TestClient(create_app(settings, mailer=outbox, stripe=stripe)) as c:
        yield c


def signed(
    payload: dict[str, Any], *, secret: str = SECRET, at: float | None = None
) -> dict[str, Any]:
    body = json.dumps(payload).encode()
    stamp = str(int(at if at is not None else time.time()))
    sig = hmac.new(secret.encode(), f"{stamp}.".encode() + body, hashlib.sha256).hexdigest()
    return {"content": body, "headers": {"Stripe-Signature": f"t={stamp},v1={sig}"}}


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
        assert plain.get("/v1/capabilities").json() == {
            "email": False,
            "billing": False,
            "ask_model": False,
        }
        refused = plain.post("/v1/password/forgot", json={"email": "a@example.com"})
        assert refused.status_code == 503
        assert refused.json()["error"]["code"] == "email_unavailable"


def test_capabilities_report_what_is_configured(client: TestClient) -> None:
    assert client.get("/v1/capabilities").json() == {
        "email": True,
        "billing": True,
        "ask_model": False,
    }


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


# --- billing ----------------------------------------------------------------------------------


def test_signature_checks() -> None:
    body = b"{}"
    stamp = "1700000000"
    sig = hmac.new(b"s", f"{stamp}.".encode() + body, hashlib.sha256).hexdigest()
    assert verify_signature(body, f"t={stamp},v1={sig}", "s", now=1700000100)
    assert not verify_signature(body, f"t={stamp},v1={sig}", "s", now=1700001000)  # too old
    assert not verify_signature(body, f"t={stamp},v1={sig}", "other", now=1700000100)
    assert not verify_signature(b"{ }", f"t={stamp},v1={sig}", "s", now=1700000100)
    assert not verify_signature(body, "garbage", "s", now=1700000100)
    assert not verify_signature(body, f"t=abc,v1={sig}", "s", now=1700000100)


def test_plans_and_subscription_states() -> None:
    settings = Settings(
        app_env="test",
        daily_ask_limit=10,
        pro_daily_ask_limit=100,
        daily_upload_bytes_quota=1_000,
        pro_upload_quota_multiplier=3,
    )
    assert (
        limits_for("free", settings).ask_per_day,
        limits_for("free", settings).upload_bytes_per_day,
    ) == (10, 1_000)
    assert (
        limits_for("pro", settings).ask_per_day,
        limits_for("pro", settings).upload_bytes_per_day,
    ) == (100, 3_000)
    assert limits_for("anything-else", settings).ask_per_day == 10
    assert [
        plan_from_subscription(s)
        for s in ("active", "trialing", "past_due", "canceled", "unpaid", "incomplete", "")
    ] == ["pro", "pro", "pro", "free", "free", "free", "free"]


def test_billing_state_checkout_and_portal(
    client: TestClient, stripe: FakeStripe, db: Engine
) -> None:
    headers = web_session(client)

    state = client.get("/v1/billing", headers=headers).json()
    assert (state["plan"], state["ask_limit"], state["asks_used"], state["billing_enabled"]) == (
        "free",
        2,
        0,
        True,
    )
    assert client.post("/v1/billing/portal", headers=headers).status_code == 409

    checkout = client.post("/v1/billing/checkout", headers=headers)
    assert checkout.json() == {"url": "https://stripe.test/checkout/sessions"}
    _path, data = stripe.calls[-1]
    user = user_of(db, "me@example.com")
    assert data["client_reference_id"] == str(user)
    assert data["customer_email"] == "me@example.com"
    assert data["line_items[0][price]"] == "price_1"
    assert data["success_url"].endswith("/app/#/account?billing=success")

    stripe.fail = True
    assert client.post("/v1/billing/checkout", headers=headers).status_code == 502
    assert client.get("/v1/billing").status_code == 401


def test_the_webhook_moves_the_plan_and_raises_limits_server_side(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)
    user = user_of(db, "me@example.com")
    for _ in range(2):
        assert (
            client.post(
                "/v1/ask", json={"question": "anything at all"}, headers=headers
            ).status_code
            == 200
        )
    assert (
        client.post("/v1/ask", json={"question": "anything at all"}, headers=headers).status_code
        == 429
    )

    done = signed(
        {
            "type": "checkout.session.completed",
            "data": {"object": {"client_reference_id": str(user), "customer": "cus_1"}},
        }
    )
    assert client.post("/v1/billing/webhook", **done).status_code == 200
    renews = int((datetime.now(UTC) + timedelta(days=30)).timestamp())
    sub = signed(
        {
            "type": "customer.subscription.updated",
            "data": {
                "object": {
                    "customer": "cus_1",
                    "status": "active",
                    "current_period_end": renews,
                    "cancel_at_period_end": True,
                }
            },
        }
    )
    assert client.post("/v1/billing/webhook", **sub).status_code == 200

    state = client.get("/v1/billing", headers=headers).json()
    assert (state["plan"], state["ask_limit"], state["cancels_at_period_end"]) == ("pro", 5, True)
    assert state["renews_at"] is not None
    assert (
        client.post("/v1/ask", json={"question": "anything at all"}, headers=headers).status_code
        == 200
    )
    assert client.post("/v1/billing/checkout", headers=headers).status_code == 409
    assert client.post("/v1/billing/portal", headers=headers).status_code == 200

    gone = signed(
        {
            "type": "customer.subscription.deleted",
            "data": {"object": {"customer": "cus_1", "status": "active"}},
        }
    )
    assert client.post("/v1/billing/webhook", **gone).status_code == 200
    after = client.get("/v1/billing", headers=headers).json()
    assert (after["plan"], after["renews_at"], after["cancels_at_period_end"]) == (
        "free",
        None,
        False,
    )


def test_webhook_rejects_bad_signatures_stale_ones_and_garbage(client: TestClient) -> None:
    event = {
        "type": "customer.subscription.updated",
        "data": {"object": {"customer": "cus_1", "status": "active"}},
    }
    assert client.post("/v1/billing/webhook", **signed(event, secret="wrong")).status_code == 400
    assert (
        client.post("/v1/billing/webhook", **signed(event, at=time.time() - 3_600)).status_code
        == 400
    )
    assert (
        client.post(
            "/v1/billing/webhook", content=b"{}", headers={"Stripe-Signature": "x"}
        ).status_code
        == 400
    )
    assert client.post("/v1/billing/webhook", **signed({"nope": 1})).status_code == 400
    ignored = signed({"type": "invoice.paid", "data": {"object": {}}})
    assert client.post("/v1/billing/webhook", **ignored).status_code == 200
    bad_ref = signed(
        {
            "type": "checkout.session.completed",
            "data": {"object": {"client_reference_id": "not-a-uuid", "customer": "c"}},
        }
    )
    assert client.post("/v1/billing/webhook", **bad_ref).status_code == 200


def test_a_paying_person_gets_a_larger_upload_quota(db: Engine, database_url: str) -> None:
    settings = settings_for(
        database_url, daily_upload_bytes_quota=1_000, pro_upload_quota_multiplier=4
    )
    assert (
        limits_for("pro", settings).upload_bytes_per_day
        == 4 * limits_for("free", settings).upload_bytes_per_day
    )


def test_billing_is_unavailable_without_configuration(db: Engine, database_url: str) -> None:
    with TestClient(create_app(settings_for(database_url))) as plain:
        headers = web_session(plain)
        assert (
            plain.post("/v1/billing/checkout", headers=headers).json()["error"]["code"]
            == "billing_unavailable"
        )
        assert plain.post("/v1/billing/portal", headers=headers).status_code == 503
        assert plain.post("/v1/billing/webhook", content=b"{}").status_code == 503
        assert plain.get("/v1/billing", headers=headers).json()["billing_enabled"] is False


@pytest.mark.anyio
async def test_the_stripe_client_maps_failures() -> None:
    def respond(request: httpx.Request) -> httpx.Response:
        if request.url.path.endswith("/bad"):
            return httpx.Response(402, json={})
        if request.url.path.endswith("/list"):
            return httpx.Response(200, json=[1])
        return httpx.Response(200, json={"ok": True})

    stripe = StripeClient("sk", transport=httpx.MockTransport(respond))
    assert await stripe.post("/good", {"a": "b"}) == {"ok": True}
    for path in ("/bad", "/list"):
        with pytest.raises(StripeError):
            await stripe.post(path, {})

    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down")

    with pytest.raises(StripeError):
        await StripeClient("sk", transport=httpx.MockTransport(boom)).post("/x", {})
