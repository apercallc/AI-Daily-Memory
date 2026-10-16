"""Consolidated authorization audit (TODO.md Phase 8 Security; `docs/security/THREAT_MODEL.md`
"Open items": "list every endpoint, its required scope(s), and confirm a test exists proving a
token lacking that scope gets 401/403 and a token for a different user gets 404"). Per-endpoint
tests already exist scattered through this directory; this file is the single place that states
the whole matrix and checks both generic invariants against every one of them, so a newly added
endpoint that forgets a scope check is caught here even if its own test suite doesn't think to.

`app.auth.require()` resolves as a FastAPI sub-dependency before the route's own body parameter
is validated, so a missing/wrong-scope token is rejected before any body content matters — every
scope-matrix call below sends an empty body and that is deliberate, not an oversight.
"""

import uuid
from collections.abc import Iterator
from datetime import UTC, datetime
from typing import cast

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.auth import DEVICE_SCOPES
from app.main import create_app
from tests.conftest import Account, FakeObjectStore, make_account, settings_for

PASSWORD = "correct horse battery"  # noqa: S105 - test credential

# Every scope that exists; an account missing exactly the scopes an endpoint needs should always
# be rejected with 403, never let through by accident because it happened to carry a wider set.
FULL_SCOPES = ("upload:write", "memory:read", "memory:write", "transcript:read", "account:manage")

# One row per authenticated endpoint: (method, path, scopes accepted — require() is "any of").
# A path with a UUID/date placeholder uses a syntactically valid but nonexistent value for the
# scope checks: the scope dependency must reject before the route ever looks the resource up, so
# what the placeholder resolves to does not matter for this half of the audit.
_U = uuid.uuid4()
PROTECTED_ENDPOINTS: list[tuple[str, str, tuple[str, ...]]] = [
    ("POST", "/v1/uploads", ("upload:write",)),
    ("GET", f"/v1/uploads/{_U}", ("upload:write",)),
    ("POST", f"/v1/uploads/{_U}/complete", ("upload:write",)),
    ("GET", "/v1/me", ("upload:write", "account:manage")),
    ("PUT", "/v1/me", ("upload:write", "account:manage")),
    ("POST", "/v1/me/password", ("upload:write", "account:manage")),
    ("DELETE", "/v1/me", ("upload:write", "account:manage")),
    ("DELETE", "/v1/tokens/current", ("upload:write", "account:manage")),
    ("POST", "/v1/me/verify", ("upload:write", "account:manage")),
    ("POST", "/v1/sync", ("upload:write",)),
    ("GET", "/v1/notes", ("memory:read",)),
    ("GET", f"/v1/notes/{_U}", ("memory:read",)),
    ("PATCH", f"/v1/notes/{_U}", ("memory:write",)),
    ("DELETE", f"/v1/notes/{_U}", ("memory:write",)),
    ("POST", "/v1/notes/bulk", ("memory:write",)),
    ("GET", "/v1/folders", ("memory:read",)),
    ("POST", "/v1/folders", ("memory:write",)),
    ("PATCH", f"/v1/folders/{_U}", ("memory:write",)),
    ("DELETE", f"/v1/folders/{_U}", ("memory:write",)),
    ("GET", "/v1/memory/days", ("memory:read",)),
    ("GET", "/v1/memory/items", ("memory:read",)),
    ("PATCH", f"/v1/memory/items/{_U}", ("memory:write",)),
    ("GET", f"/v1/memory/items/{_U}/evidence", ("transcript:read",)),
    ("POST", "/v1/ask", ("memory:read",)),
    ("PUT", "/v1/device-key", ("upload:write",)),
    ("GET", "/v1/deliveries", ("upload:write",)),
    ("POST", "/v1/deliveries/ack", ("upload:write",)),
    ("GET", "/v1/export", ("upload:write", "account:manage")),
    ("GET", "/v1/tokens", ("upload:write", "account:manage")),
    ("DELETE", f"/v1/tokens/{_U}", ("upload:write", "account:manage")),
    ("GET", "/v1/api-keys", ("upload:write", "account:manage")),
    ("POST", "/v1/api-keys", ("upload:write", "account:manage")),
    ("GET", "/v1/reports/2026-01-01", ("memory:read",)),
]

# Routes with no Principal dependency at all: unauthenticated by design (signup/login issue the
# very first token; health/ready/capabilities are infrastructure checks; the password-reset and
# email-verify flows authenticate via their own single-use token, not a bearer token).
PUBLIC_ENDPOINTS: list[tuple[str, str, dict[str, object]]] = [
    ("GET", "/v1/health", {}),
    ("GET", "/v1/ready", {}),
    ("GET", "/v1/capabilities", {}),
    ("POST", "/v1/password/forgot", {"email": "nobody@example.com"}),
    ("POST", "/v1/password/reset", {"token": "not-a-real-token", "new_password": "x"}),
    ("POST", "/v1/verify", {"token": "not-a-real-token"}),
    (
        "POST",
        "/v1/signup",
        {"email": f"{uuid.uuid4()}@example.com", "password": PASSWORD, "device_name": "d"},
    ),
]


@pytest.fixture
def store() -> FakeObjectStore:
    return FakeObjectStore()


@pytest.fixture
def client(db: Engine, database_url: str, store: FakeObjectStore) -> Iterator[TestClient]:
    app = create_app(
        settings_for(database_url, daily_ask_limit=3), object_store=store, processing_enabled=True
    )
    with TestClient(app) as test_client:
        yield test_client


def _call(
    client: TestClient, method: str, path: str, headers: dict[str, str] | None = None
) -> httpx.Response:
    return cast(httpx.Response, client.request(method, path, json={}, headers=headers or {}))


def _account_missing(db: Engine, *missing: str) -> Account:
    scopes = tuple(s for s in FULL_SCOPES if s not in missing)
    return make_account(db, scopes=scopes)


@pytest.mark.parametrize(("method", "path", "scopes"), PROTECTED_ENDPOINTS)
def test_every_protected_endpoint_rejects_an_unauthenticated_caller(
    client: TestClient, method: str, path: str, scopes: tuple[str, ...]
) -> None:
    response = _call(client, method, path)
    assert response.status_code == 401, f"{method} {path} did not 401 with no token"


@pytest.mark.parametrize(("method", "path", "scopes"), PROTECTED_ENDPOINTS)
def test_every_protected_endpoint_rejects_a_token_missing_its_scope(
    client: TestClient, db: Engine, method: str, path: str, scopes: tuple[str, ...]
) -> None:
    account = _account_missing(db, *scopes)
    response = _call(client, method, path, account.headers)
    assert response.status_code == 403, (
        f"{method} {path} did not 403 a token with none of {scopes} (got {response.status_code})"
    )


@pytest.mark.parametrize(("method", "path", "payload"), PUBLIC_ENDPOINTS)
def test_every_public_endpoint_accepts_no_bearer_token(
    client: TestClient, method: str, path: str, payload: dict[str, object]
) -> None:
    response = client.request(method, path, json=payload or None)
    assert response.status_code not in (401, 403), f"{method} {path} unexpectedly requires auth"


def test_login_needs_no_bearer_token_either(client: TestClient) -> None:
    # /v1/login's 401 on a bad password is a credentials check, not a missing-bearer-token
    # check -- a real account must exist and be used correctly to tell those apart.
    email = f"{uuid.uuid4()}@example.com"
    signup = client.post(
        "/v1/signup", json={"email": email, "password": PASSWORD, "device_name": "d"}
    )
    assert signup.status_code == 201

    response = client.post(
        "/v1/login", json={"email": email, "password": PASSWORD, "device_name": "d2"}
    )

    assert response.status_code == 200


# --- cross-user ownership: every endpoint that takes a resource id in its path ------------------


def _add_note(db: Engine, user: uuid.UUID) -> uuid.UUID:
    session_id = uuid.uuid4()
    with db.begin() as c:
        c.execute(
            text(
                "INSERT INTO notes (id, user_id, session_id, memory_day, auto_title) "
                "VALUES (:id, :u, :s, :d, 'Untitled')"
            ),
            {"id": uuid.uuid4(), "u": user, "s": session_id, "d": datetime.now(UTC).date()},
        )
    return session_id


def _add_item(db: Engine, user: uuid.UUID) -> uuid.UUID:
    item_id = uuid.uuid4()
    with db.begin() as c:
        c.execute(
            text(
                "INSERT INTO memory_items (id, user_id, session_id, memory_day, kind, text, "
                "confidence, extraction_model, extraction_version) "
                "VALUES (:id, :u, :s, :d, 'idea', 'x', 'likely', 'm', 1)"
            ),
            {"id": item_id, "u": user, "s": uuid.uuid4(), "d": datetime.now(UTC).date()},
        )
    return item_id


def _add_upload(client: TestClient, headers: dict[str, str]) -> uuid.UUID:
    upload_id = uuid.uuid4()
    body = {
        "upload_id": str(upload_id),
        "session_id": str(uuid.uuid4()),
        "segment_id": str(uuid.uuid4()),
        "offset_ms": 0,
        "duration_ms": 1_000,
        "byte_size": 100,
        "sha256": "0" * 64,
        "codec": "aac-lc-adts",
        "sample_rate_hz": 16_000,
        "channel_count": 1,
        "captured_at": datetime.now(UTC).isoformat(),
    }
    response = client.post("/v1/uploads", json=body, headers=headers)
    assert response.status_code in (201, 200), response.text
    return upload_id


def test_an_uploads_resource_404s_for_a_different_user(client: TestClient, db: Engine) -> None:
    # full_cloud: the protocol test path that needs no device key (test_uploads.py's convention).
    owner = make_account(db, scopes=DEVICE_SCOPES, storage_mode="full_cloud")
    stranger = make_account(db, scopes=DEVICE_SCOPES, storage_mode="full_cloud")
    upload_id = _add_upload(client, owner.headers)

    get_response = client.get(f"/v1/uploads/{upload_id}", headers=stranger.headers)
    complete_response = client.post(f"/v1/uploads/{upload_id}/complete", headers=stranger.headers)

    assert get_response.status_code == 404
    assert complete_response.status_code == 404


def test_a_notes_resource_404s_for_a_different_user(client: TestClient, db: Engine) -> None:
    owner = make_account(db, scopes=FULL_SCOPES)
    stranger = make_account(db, scopes=FULL_SCOPES)
    session_id = _add_note(db, owner.user_id)

    get_response = client.get(f"/v1/notes/{session_id}", headers=stranger.headers)
    patch_response = client.patch(
        f"/v1/notes/{session_id}", json={"starred": True}, headers=stranger.headers
    )
    delete_response = client.delete(f"/v1/notes/{session_id}", headers=stranger.headers)

    assert get_response.status_code == 404
    assert patch_response.status_code == 404
    assert delete_response.status_code == 404


def test_a_folder_404s_for_a_different_user(client: TestClient, db: Engine) -> None:
    owner = make_account(db, scopes=FULL_SCOPES)
    stranger = make_account(db, scopes=FULL_SCOPES)
    created = client.post("/v1/folders", json={"name": "Work"}, headers=owner.headers)
    assert created.status_code == 201
    folder_id = created.json()["id"]

    patch_response = client.patch(
        f"/v1/folders/{folder_id}", json={"name": "Renamed"}, headers=stranger.headers
    )
    delete_response = client.delete(f"/v1/folders/{folder_id}", headers=stranger.headers)

    assert patch_response.status_code == 404
    assert delete_response.status_code == 404


def test_a_memory_item_404s_for_a_different_user(client: TestClient, db: Engine) -> None:
    owner = make_account(db, scopes=FULL_SCOPES)
    stranger = make_account(db, scopes=FULL_SCOPES)
    item_id = _add_item(db, owner.user_id)

    patch_response = client.patch(
        f"/v1/memory/items/{item_id}", json={"dismissed": True}, headers=stranger.headers
    )
    evidence_response = client.get(f"/v1/memory/items/{item_id}/evidence", headers=stranger.headers)

    assert patch_response.status_code == 404
    assert evidence_response.status_code == 404


def test_a_token_404s_when_revoked_by_a_different_user(client: TestClient, db: Engine) -> None:
    owner = make_account(db, scopes=FULL_SCOPES)
    stranger = make_account(db, scopes=FULL_SCOPES)
    # The owner's own device-token row id is what a stranger must not be able to revoke.
    owner_token_id: uuid.UUID
    with db.connect() as c:
        found = c.scalar(
            text("SELECT id FROM access_tokens WHERE user_id = :u"), {"u": owner.user_id}
        )
    assert isinstance(found, uuid.UUID)
    owner_token_id = found

    response = client.delete(f"/v1/tokens/{owner_token_id}", headers=stranger.headers)

    assert response.status_code == 404
