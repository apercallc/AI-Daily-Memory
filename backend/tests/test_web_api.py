"""The web app's API: browser sessions, memory browsing and correction, Ask, keys, export."""

import json
import uuid
from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.main import create_app
from app.recall import Answer, AnswerUnavailableError, Evidence
from tests.conftest import make_account, settings_for

PASSWORD = "correct horse battery"  # noqa: S105 - test credential


class FakeAnswers:
    name = "fake"

    def __init__(self) -> None:
        self.calls: list[list[Evidence]] = []
        self.fail = False

    async def answer(self, question: str, items: list[Evidence]) -> Answer:
        self.calls.append(items)
        if self.fail:
            raise AnswerUnavailableError
        return Answer(
            f"Model says: {items[0].text}", (items[0].item_id, "not-given"), False, "model"
        )


@pytest.fixture
def answers() -> FakeAnswers:
    return FakeAnswers()


@pytest.fixture
def client(db: Engine, database_url: str, answers: FakeAnswers) -> Iterator[TestClient]:
    app = create_app(settings_for(database_url, daily_ask_limit=3), answer_provider=answers)
    with TestClient(app) as test_client:
        yield test_client


def web_session(client: TestClient, email: str = "me@example.com") -> dict[str, str]:
    response = client.post(
        "/v1/signup",
        json={"email": email, "password": PASSWORD, "device_name": "Web", "client": "web"},
    )
    assert response.status_code == 201
    return {"Authorization": f"Bearer {response.json()['token']}"}


def user_of(db: Engine, email: str) -> uuid.UUID:
    with db.connect() as c:
        found = c.scalar(text("SELECT user_id FROM credentials WHERE email = :e"), {"e": email})
    assert isinstance(found, uuid.UUID)
    return found


def add_item(
    db: Engine,
    user: uuid.UUID,
    body: str,
    *,
    kind: str = "idea",
    day: datetime | None = None,
    owner: str | None = None,
    counterpart: str | None = None,
    direction: str | None = None,
) -> uuid.UUID:
    item = uuid.uuid4()
    with db.begin() as c:
        c.execute(
            text(
                "INSERT INTO memory_items (id, user_id, session_id, memory_day, kind, text, "
                "confidence, owner, counterpart, direction, extraction_model, extraction_version)"
                " VALUES (:id, :u, :s, :d, :k, :t, 'likely', :o, :c, :dir, 'm', 1)"
            ),
            {
                "id": item,
                "u": user,
                "s": uuid.uuid4(),
                "d": (day or datetime.now(UTC)).date(),
                "k": kind,
                "t": body,
                "o": owner,
                "c": counterpart,
                "dir": direction,
            },
        )
    return item


# --- browser sessions -------------------------------------------------------------------------


def test_a_browser_session_has_web_scopes_an_expiry_and_cannot_upload(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)

    me = client.get("/v1/me", headers=headers).json()

    assert me["email"] == "me@example.com"
    assert set(me["scopes"]) == {"memory:read", "memory:write", "transcript:read", "account:manage"}
    assert client.post("/v1/uploads", json={}, headers=headers).status_code == 403
    with db.connect() as c:
        expires = c.scalar(text("SELECT expires_at FROM access_tokens WHERE name = 'Web'"))
    assert timedelta(days=29) < expires - datetime.now(UTC) <= timedelta(days=30)


def test_login_can_ask_for_a_browser_session_too(client: TestClient) -> None:
    web_session(client)
    response = client.post(
        "/v1/login",
        json={
            "email": "me@example.com",
            "password": PASSWORD,
            "device_name": "W2",
            "client": "web",
        },
    )
    headers = {"Authorization": f"Bearer {response.json()['token']}"}
    assert "account:manage" in client.get("/v1/me", headers=headers).json()["scopes"]


def test_signing_out_revokes_the_session(client: TestClient) -> None:
    headers = web_session(client)
    assert client.delete("/v1/tokens/current", headers=headers).status_code == 204
    assert client.get("/v1/me", headers=headers).status_code == 401


# --- tokens and API keys ----------------------------------------------------------------------


def test_listing_marks_the_current_session_and_revoking_ends_another(
    client: TestClient, db: Engine
) -> None:
    mine = web_session(client)
    other = client.post(
        "/v1/login",
        json={
            "email": "me@example.com",
            "password": PASSWORD,
            "device_name": "Old",
            "client": "web",
        },
    ).json()["token"]

    listed = client.get("/v1/tokens", headers=mine).json()

    assert sorted(t["name"] for t in listed) == ["Old", "Web"]
    assert [t["name"] for t in listed if t["current"]] == ["Web"]
    old_id = next(t["id"] for t in listed if t["name"] == "Old")
    assert client.delete(f"/v1/tokens/{old_id}", headers=mine).status_code == 204
    assert client.get("/v1/me", headers={"Authorization": f"Bearer {other}"}).status_code == 401
    assert client.delete(f"/v1/tokens/{old_id}", headers=mine).status_code == 404


def test_nobody_can_revoke_another_persons_token(client: TestClient, db: Engine) -> None:
    mine = web_session(client)
    stranger = make_account(db)
    with db.connect() as c:
        stranger_token = c.scalar(
            text("SELECT id FROM access_tokens WHERE user_id = :u"), {"u": stranger.user_id}
        )
    assert client.delete(f"/v1/tokens/{stranger_token}", headers=mine).status_code == 404


def test_api_keys_are_read_only_shown_once_and_revocable(client: TestClient) -> None:
    headers = web_session(client)

    created = client.post("/v1/api-keys", json={"name": "my script"}, headers=headers).json()

    assert created["token"].startswith("adm_")
    keys = client.get("/v1/api-keys", headers=headers).json()
    assert [(k["name"], k["scopes"]) for k in keys] == [("my script", ["memory:read"])]
    assert "token" not in keys[0]
    key_headers = {"Authorization": f"Bearer {created['token']}"}
    assert client.get("/v1/memory/items", headers=key_headers).status_code == 200
    assert (
        client.patch(
            f"/v1/memory/items/{uuid.uuid4()}", json={"dismissed": True}, headers=key_headers
        ).status_code
        == 403
    )
    assert client.post("/v1/api-keys", json={"name": "x"}, headers=key_headers).status_code == 403
    assert client.get("/v1/export", headers=key_headers).status_code == 403
    assert client.delete(f"/v1/tokens/{created['id']}", headers=headers).status_code == 204
    assert client.get("/v1/memory/items", headers=key_headers).status_code == 401


def test_the_number_of_active_keys_is_bounded(client: TestClient) -> None:
    headers = web_session(client)
    for n in range(10):
        assert (
            client.post("/v1/api-keys", json={"name": f"k{n}"}, headers=headers).status_code == 201
        )
    response = client.post("/v1/api-keys", json={"name": "one more"}, headers=headers)
    assert response.status_code == 409
    assert response.json()["error"]["code"] == "too_many_keys"


# --- memory -----------------------------------------------------------------------------------


def test_days_and_items_are_listed_newest_first_and_only_for_the_owner(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)
    user = user_of(db, "me@example.com")
    now = datetime.now(UTC)
    add_item(db, user, "Today idea")
    add_item(db, user, "Yesterday decision", kind="decision", day=now - timedelta(days=1))
    add_item(db, make_account(db).user_id, "Someone else's item")

    days = client.get("/v1/memory/days", headers=headers).json()
    page = client.get("/v1/memory/items", headers=headers).json()

    assert [d["items"] for d in days] == [1, 1]
    assert [i["text"] for i in page["items"]] == ["Today idea", "Yesterday decision"]
    assert page["total"] == 2
    only = client.get("/v1/memory/items", params={"kind": "decision"}, headers=headers).json()
    assert [i["text"] for i in only["items"]] == ["Yesterday decision"]


def test_items_filter_by_day_range_and_text_with_no_pattern_injection(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)
    user = user_of(db, "me@example.com")
    now = datetime.now(UTC)
    add_item(db, user, "Plan 100% launch", owner="Sam")
    add_item(db, user, "Old thing", day=now - timedelta(days=10))

    recent = client.get(
        "/v1/memory/items",
        params={"from": (now - timedelta(days=2)).date().isoformat()},
        headers=headers,
    ).json()
    assert [i["text"] for i in recent["items"]] == ["Plan 100% launch"]
    percent = client.get("/v1/memory/items", params={"q": "100%"}, headers=headers).json()
    assert percent["total"] == 1
    wildcard = client.get("/v1/memory/items", params={"q": "%"}, headers=headers).json()
    assert wildcard["total"] == 1  # a literal percent sign, not "match everything"
    by_owner = client.get("/v1/memory/items", params={"q": "sam"}, headers=headers).json()
    assert by_owner["total"] == 1
    paged = client.get("/v1/memory/items", params={"limit": 1, "offset": 1}, headers=headers).json()
    assert len(paged["items"]) == 1
    assert paged["total"] == 2


def test_dismissing_and_restoring_hides_without_deleting(client: TestClient, db: Engine) -> None:
    headers = web_session(client)
    item = add_item(db, user_of(db, "me@example.com"), "Not a task")

    dismissed = client.patch(f"/v1/memory/items/{item}", json={"dismissed": True}, headers=headers)

    assert dismissed.json()["dismissed"] is True
    assert client.get("/v1/memory/items", headers=headers).json()["total"] == 0
    shown = client.get("/v1/memory/items", params={"include_dismissed": True}, headers=headers)
    assert shown.json()["total"] == 1
    assert client.get("/v1/memory/days", headers=headers).json()[0]["items"] == 0
    restored = client.patch(f"/v1/memory/items/{item}", json={"dismissed": False}, headers=headers)
    assert restored.json()["dismissed"] is False


def test_a_correction_sits_beside_the_original_and_can_be_removed(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)
    item = add_item(db, user_of(db, "me@example.com"), "Send the deck Friday")

    edited = client.patch(
        f"/v1/memory/items/{item}", json={"text": "Send the deck Thursday"}, headers=headers
    ).json()

    assert (edited["text"], edited["original_text"], edited["corrected"]) == (
        "Send the deck Thursday",
        "Send the deck Friday",
        True,
    )
    with db.connect() as c:
        stored = c.scalar(text("SELECT text FROM memory_items WHERE id = :i"), {"i": item})
    assert stored == "Send the deck Friday"
    again = client.patch(
        f"/v1/memory/items/{item}", json={"text": "Thursday, noon"}, headers=headers
    )
    assert again.json()["text"] == "Thursday, noon"
    assert (
        client.get("/v1/memory/items", params={"q": "noon"}, headers=headers).json()["total"] == 1
    )
    cleared = client.patch(f"/v1/memory/items/{item}", json={"clear_text": True}, headers=headers)
    assert (cleared.json()["text"], cleared.json()["corrected"]) == ("Send the deck Friday", False)


def test_patching_another_persons_item_or_with_bad_input_is_refused(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)
    theirs = add_item(db, make_account(db).user_id, "Private")
    mine = add_item(db, user_of(db, "me@example.com"), "Mine")

    assert (
        client.patch(
            f"/v1/memory/items/{theirs}", json={"dismissed": True}, headers=headers
        ).status_code
        == 404
    )
    assert (
        client.patch(f"/v1/memory/items/{mine}", json={"text": ""}, headers=headers).status_code
        == 422
    )
    assert (
        client.patch(
            f"/v1/memory/items/{mine}", json={"text": "x" * 281}, headers=headers
        ).status_code
        == 422
    )
    assert (
        client.patch(f"/v1/memory/items/{mine}", json={"other": 1}, headers=headers).status_code
        == 422
    )


def test_evidence_is_returned_only_for_the_owner(client: TestClient, db: Engine) -> None:
    headers = web_session(client)
    user = user_of(db, "me@example.com")
    item = add_item(db, user, "Grounded")
    upload, transcript, segment = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    with db.begin() as c:
        c.execute(
            text(
                "INSERT INTO audio_uploads (id, user_id, session_id, segment_id, offset_ms, "
                "duration_ms, byte_size, sha256, codec, sample_rate_hz, channel_count, "
                "captured_at, state, object_key) VALUES (:i, :u, :s, :sg, 0, 1000, 10, :h, "
                "'aac', 16000, 1, now(), 'transcribed', :k)"
            ),
            {
                "i": upload,
                "u": user,
                "s": uuid.uuid4(),
                "sg": uuid.uuid4(),
                "h": "a" * 64,
                "k": "k",
            },
        )
        c.execute(
            text(
                "INSERT INTO transcripts (id, user_id, upload_id, revision, provider, model)"
                " VALUES (:t, :u, :up, 1, 'p', 'm')"
            ),
            {"t": transcript, "u": user, "up": upload},
        )
        c.execute(
            text(
                "INSERT INTO transcript_segments (id, user_id, transcript_id, start_ms, end_ms,"
                " text, speaker) VALUES (:s, :u, :t, 5, 9, 'I will send it', 'A')"
            ),
            {"s": segment, "u": user, "t": transcript},
        )
        c.execute(
            text(
                "INSERT INTO memory_item_evidence (id, user_id, memory_item_id,"
                " transcript_segment_id, quote) VALUES (gen_random_uuid(), :u, :i, :s, 'send it')"
            ),
            {"u": user, "i": item, "s": segment},
        )

    evidence = client.get(f"/v1/memory/items/{item}/evidence", headers=headers).json()

    assert evidence == [
        {"quote": "send it", "text": "I will send it", "speaker": "A", "start_ms": 5}
    ]
    other = web_session(client, "other@example.com")
    assert client.get(f"/v1/memory/items/{item}/evidence", headers=other).status_code == 404
    assert (
        client.get(f"/v1/memory/items/{uuid.uuid4()}/evidence", headers=headers).status_code == 404
    )


# --- ask --------------------------------------------------------------------------------------


def test_ask_finds_items_by_words_and_cites_only_what_it_retrieved(
    client: TestClient, db: Engine, answers: FakeAnswers
) -> None:
    headers = web_session(client)
    user = user_of(db, "me@example.com")
    add_item(
        db,
        user,
        "Send Sam the pricing deck",
        kind="commitment",
        counterpart="Sam",
        direction="i_owe",
    )
    add_item(db, user, "Book the venue")

    result = client.post(
        "/v1/ask", json={"question": "What did I promise Sam about pricing?"}, headers=headers
    ).json()

    assert result["abstained"] is False
    assert result["source"] == "model"
    assert [i["text"] for i in result["items"]] == ["Send Sam the pricing deck"]
    assert [e.text for e in answers.calls[0]] == ["Send Sam the pricing deck"]


def test_ask_by_time_and_kind_needs_no_keywords(client: TestClient, db: Engine) -> None:
    headers = web_session(client)
    user = user_of(db, "me@example.com")
    now = datetime.now(UTC)
    add_item(db, user, "Choose Postgres", kind="decision", day=now - timedelta(days=1))
    add_item(db, user, "Choose blue", kind="idea", day=now - timedelta(days=1))
    add_item(db, user, "Choose a venue", kind="decision", day=now - timedelta(days=9))

    result = client.post(
        "/v1/ask", json={"question": "What did I decide yesterday?"}, headers=headers
    ).json()

    assert result["range_label"] == "yesterday"
    assert [i["text"] for i in result["items"]] == ["Choose Postgres"]


def test_ask_abstains_and_never_calls_the_model_without_evidence(
    client: TestClient, db: Engine, answers: FakeAnswers
) -> None:
    headers = web_session(client)
    add_item(db, user_of(db, "me@example.com"), "Book the venue")

    result = client.post(
        "/v1/ask", json={"question": "Tell me about quantum computing"}, headers=headers
    ).json()

    assert result["abstained"] is True
    assert result["items"] == []
    assert answers.calls == []


def test_ask_never_reaches_another_persons_memory_or_dismissed_items(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)
    user = user_of(db, "me@example.com")
    add_item(db, make_account(db).user_id, "Secret merger plan")
    hidden = add_item(db, user, "Hidden merger note")
    client.patch(f"/v1/memory/items/{hidden}", json={"dismissed": True}, headers=headers)

    result = client.post("/v1/ask", json={"question": "merger plan"}, headers=headers).json()

    assert result["abstained"] is True


def test_ask_uses_the_persons_corrected_wording(client: TestClient, db: Engine) -> None:
    headers = web_session(client)
    item = add_item(db, user_of(db, "me@example.com"), "Call Dana")
    client.patch(
        f"/v1/memory/items/{item}", json={"text": "Call Priya about the lease"}, headers=headers
    )

    result = client.post(
        "/v1/ask", json={"question": "what about the lease?"}, headers=headers
    ).json()

    assert [i["text"] for i in result["items"]] == ["Call Priya about the lease"]


def test_a_failed_model_falls_back_to_listing_the_evidence(
    client: TestClient, db: Engine, answers: FakeAnswers
) -> None:
    headers = web_session(client)
    add_item(db, user_of(db, "me@example.com"), "Order the banners")
    answers.fail = True

    result = client.post(
        "/v1/ask", json={"question": "who ordered banners"}, headers=headers
    ).json()

    assert result["source"] == "evidence_only"
    assert not result["abstained"]
    assert "Order the banners" in result["answer"]


def test_ask_without_a_model_is_evidence_only(db: Engine, database_url: str) -> None:
    with TestClient(create_app(settings_for(database_url))) as plain:
        headers = web_session(plain)
        add_item(db, user_of(db, "me@example.com"), "Order the banners")
        result = plain.post("/v1/ask", json={"question": "banners"}, headers=headers).json()
    assert result["source"] == "evidence_only"


def test_ask_is_metered_per_person_per_day_on_the_server(client: TestClient, db: Engine) -> None:
    headers = web_session(client)
    other = web_session(client, "two@example.com")
    for _ in range(3):
        assert (
            client.post(
                "/v1/ask", json={"question": "anything at all"}, headers=headers
            ).status_code
            == 200
        )

    refused = client.post("/v1/ask", json={"question": "anything at all"}, headers=headers)

    assert refused.status_code == 429
    assert refused.json()["error"]["code"] == "quota_exceeded"
    assert int(refused.headers["Retry-After"]) > 0
    assert (
        client.post("/v1/ask", json={"question": "anything at all"}, headers=other).status_code
        == 200
    )
    with db.connect() as c:
        assert c.scalar(text("SELECT max(count) FROM usage_counters")) == 3


def test_ask_validates_its_input_and_ignores_a_far_off_today(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)
    assert client.post("/v1/ask", json={"question": "hi"}, headers=headers).status_code == 422
    assert client.post("/v1/ask", json={"question": "x" * 501}, headers=headers).status_code == 422
    assert client.post("/v1/ask", json={"question": "anything"}, headers={}).status_code == 401
    far = client.post(
        "/v1/ask", json={"question": "what did I do today", "today": "1999-01-01"}, headers=headers
    )
    assert far.status_code == 200


# --- account ----------------------------------------------------------------------------------


def test_changing_the_password_needs_the_current_one_and_ends_other_browsers(
    client: TestClient,
) -> None:
    mine = web_session(client)
    other = client.post(
        "/v1/login",
        json={
            "email": "me@example.com",
            "password": PASSWORD,
            "device_name": "Other",
            "client": "web",
        },
    ).json()["token"]
    path = "/v1/me/password"

    wrong = client.post(
        path, json={"current_password": "nope", "new_password": "a new long password"}, headers=mine
    )
    weak = client.post(
        path, json={"current_password": PASSWORD, "new_password": "short"}, headers=mine
    )
    ok = client.post(
        path,
        json={"current_password": PASSWORD, "new_password": "a new long password"},
        headers=mine,
    )

    assert (wrong.status_code, weak.status_code, ok.status_code) == (401, 400, 204)
    assert client.get("/v1/me", headers=mine).status_code == 200
    assert client.get("/v1/me", headers={"Authorization": f"Bearer {other}"}).status_code == 401
    old = client.post(
        "/v1/login", json={"email": "me@example.com", "password": PASSWORD, "device_name": "x"}
    )
    new = client.post(
        "/v1/login",
        json={"email": "me@example.com", "password": "a new long password", "device_name": "x"},
    )
    assert (old.status_code, new.status_code) == (401, 200)


def test_a_phone_keeps_working_after_a_password_change(client: TestClient, db: Engine) -> None:
    mine = web_session(client)
    phone = client.post(
        "/v1/login", json={"email": "me@example.com", "password": PASSWORD, "device_name": "Pixel"}
    ).json()["token"]
    client.post(
        "/v1/me/password",
        json={"current_password": PASSWORD, "new_password": "a new long password"},
        headers=mine,
    )
    assert client.get("/v1/me", headers={"Authorization": f"Bearer {phone}"}).status_code == 200


def test_a_browser_must_confirm_with_its_password_to_delete_the_account(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)
    add_item(db, user_of(db, "me@example.com"), "Mine")

    assert client.delete("/v1/me", headers=headers).status_code == 401
    wrong = client.request(
        "DELETE", "/v1/me", json={"password": "wrong password!"}, headers=headers
    )
    assert wrong.status_code == 401
    assert wrong.json()["error"]["code"] == "password_required"
    right = client.request("DELETE", "/v1/me", json={"password": PASSWORD}, headers=headers)

    assert right.status_code == 204
    with db.connect() as c:
        assert c.scalar(text("SELECT count(*) FROM memory_items")) == 0
        assert c.scalar(text("SELECT count(*) FROM credentials")) == 0


def test_a_phone_still_deletes_with_its_token_alone(client: TestClient, db: Engine) -> None:
    account = make_account(db)
    assert client.delete("/v1/me", headers=account.headers).status_code == 204


# --- export -----------------------------------------------------------------------------------


def test_export_contains_everything_with_corrections_and_no_secrets(
    client: TestClient, db: Engine
) -> None:
    headers = web_session(client)
    user = user_of(db, "me@example.com")
    item = add_item(db, user, "Send the deck")
    client.patch(
        f"/v1/memory/items/{item}", json={"text": "Send the deck Thursday"}, headers=headers
    )
    stranger = add_item(db, make_account(db).user_id, "Not yours")

    response = client.get("/v1/export", headers=headers)

    assert response.status_code == 200
    assert "attachment" in response.headers["content-disposition"]
    assert response.headers["cache-control"] == "no-store"
    document = json.loads(response.text)
    assert document["account"]["email"] == "me@example.com"
    assert [(i["text"], i["your_text"]) for i in document["memory_items"]] == [
        ("Send the deck", "Send the deck Thursday")
    ]
    assert str(stranger) not in response.text
    assert {s["name"] for s in document["sign_ins"]} == {"Web"}
    assert "adm_" not in response.text
    for section in ("daily_reports", "transcripts", "clips"):
        assert document[section] == []


def test_export_is_rate_limited_and_needs_a_session(client: TestClient) -> None:
    assert client.get("/v1/export").status_code == 401
    headers = web_session(client)
    statuses = [client.get("/v1/export", headers=headers).status_code for _ in range(4)]
    # The unauthenticated probe above counted too: the limit is per address, before auth.
    assert statuses == [200, 200, 429, 429]


def test_export_includes_reports_clips_and_the_transcripts_the_server_holds(
    client: TestClient, db: Engine
) -> None:
    from tests.seed import seed_user_data

    headers = web_session(client)
    seed_user_data(db, user_of(db, "me@example.com"), with_credential=False)

    document = json.loads(client.get("/v1/export", headers=headers).text)

    assert len(document["daily_reports"]) == 1
    assert len(document["clips"]) == 1
    assert document["transcripts"][0]["segments"][0]["text"] == "hello"
    assert len(document["memory_items"]) == 1


def test_items_can_be_limited_to_an_end_day(client: TestClient, db: Engine) -> None:
    headers = web_session(client)
    now = datetime.now(UTC)
    user = user_of(db, "me@example.com")
    add_item(db, user, "Recent")
    add_item(db, user, "Older", day=now - timedelta(days=5))

    page = client.get(
        "/v1/memory/items",
        params={
            "to": (now - timedelta(days=3)).date().isoformat(),
            "day": (now - timedelta(days=5)).date().isoformat(),
        },
        headers=headers,
    ).json()

    assert [i["text"] for i in page["items"]] == ["Older"]
    page = client.get(
        "/v1/memory/items",
        params={"to": (now - timedelta(days=3)).date().isoformat()},
        headers=headers,
    ).json()
    assert [i["text"] for i in page["items"]] == ["Older"]
