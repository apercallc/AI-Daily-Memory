"""Notes: the library of recorded sessions with folders, stars, archive, sort and search."""

import json
import uuid
from collections.abc import Iterator
from datetime import UTC, date, datetime, timedelta
from typing import Any

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text
from sqlalchemy.ext.asyncio import AsyncSession

import app.api.notes as notes_api
import app.notes as notes_module
from app.db.models import DailyReportRow, MemoryItem
from app.main import create_app
from app.notes import SessionContent, build_fields, clip
from tests.conftest import Account, FakeObjectStore, make_account, settings_for

pytestmark = pytest.mark.anyio

ALL = ("upload:write", "memory:read", "memory:write", "transcript:read", "account:manage")
DAY1 = date(2026, 10, 10)
DAY2 = date(2026, 10, 12)
T1 = 1_791_000_000_000
T2 = T1 + 2 * 86_400_000


@pytest.fixture
def store() -> FakeObjectStore:
    return FakeObjectStore()


@pytest.fixture
def client(db: Engine, database_url: str, store: FakeObjectStore) -> Iterator[TestClient]:
    with TestClient(create_app(settings_for(database_url), object_store=store)) as test_client:
        yield test_client


def item(
    session: uuid.UUID, kind: str, body: str, *, owner: str | None = None
) -> dict[str, object]:
    return {
        "kind": kind,
        "text": body,
        "confidence": "likely",
        "sessionId": str(session),
        "owner": owner,
        "evidenceEpochMs": None,
    }


def line(session: uuid.UUID, at: int, body: str, speaker: str | None = None) -> dict[str, object]:
    return {"sessionId": str(session), "startEpochMs": at, "speaker": speaker, "text": body}


def report(
    day: date,
    *,
    items: list[dict[str, object]] | None = None,
    lines: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    body: dict[str, object] = {
        "memoryDay": day.isoformat(),
        "zoneId": "UTC",
        "headline": "A day",
        "summary": "",
        "items": items or [],
        "coverage": {"recordedMs": 1, "speechMs": 1, "transcribedMs": 1, "sessions": 1},
        "generatedAtEpochMs": 1_791_000_000_000,
        "extractionModel": "test",
        "extractionVersion": 1,
    }
    if lines is not None:
        body["transcriptLines"] = lines
    return body


def sync(
    client: TestClient,
    account: Account,
    *reports: dict[str, object],
    transcripts: bool = True,
    deleted: list[uuid.UUID] | None = None,
) -> None:
    response = client.post(
        "/v1/sync",
        headers=account.headers,
        json={
            "device": "phone",
            "generatedAtEpochMs": 1_791_000_000_000,
            "includeTranscripts": transcripts,
            "reports": list(reports),
            "deletedSessions": [str(s) for s in deleted or []],
        },
    )
    assert response.status_code == 200, response.text


def notes(client: TestClient, account: Account, **params: object) -> Any:
    response = client.get("/v1/notes", headers=account.headers, params=params)
    assert response.status_code == 200, response.text
    return response.json()


def titles(page: Any) -> list[str]:
    return [n["title"] for n in page["notes"]]


@pytest.fixture
def account(db: Engine) -> Account:
    return make_account(db, scopes=ALL)


@pytest.fixture
def library(client: TestClient, account: Account) -> dict[str, uuid.UUID]:
    """Three sessions on two days: planning (a decision), a call (an action), a walk (no items)."""
    planning, call, walk = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    sync(
        client,
        account,
        report(
            DAY1,
            items=[
                item(planning, "idea", "Try a standing desk"),
                item(planning, "decision", "Ship the beta on Friday"),
            ],
            lines=[
                line(planning, T1, "We should ship on friday if QA agrees", "A"),
                line(planning, T1 + 5_000, "Marta will check the invoices", "B"),
            ],
        ),
        report(
            DAY2,
            items=[item(call, "action", "Send the contract to Priya", owner="me")],
            lines=[
                line(call, T2, "Please send the contract today"),
                line(walk, T2 + 3_600_000, "Remember to buy oat milk and bread"),
            ],
        ),
    )
    return {"planning": planning, "call": call, "walk": walk}


# --- building notes -----------------------------------------------------------------------------


def test_syncing_creates_one_note_per_session_titled_from_what_matters(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    page = notes(client, account)

    assert page["total"] == 3
    by_id = {n["session_id"]: n for n in page["notes"]}
    planning = by_id[str(library["planning"])]
    assert planning["title"] == "Ship the beta on Friday"  # a decision outranks an idea
    assert planning["item_count"] == 2
    assert planning["line_count"] == 2
    assert planning["word_count"] == 13
    assert planning["memory_day"] == "2026-10-10"
    assert planning["started_at"].startswith("2026-10-")
    walk = by_id[str(library["walk"])]
    assert walk["title"] == "Remember to buy oat milk and bread"  # words only: first line
    assert walk["item_count"] == 0
    assert walk["preview"] == ""  # one spoken line: the title already says it
    assert planning["preview"] == "Try a standing desk"  # the title item is not repeated


def test_a_session_with_nothing_to_name_it_is_a_recording(
    client: TestClient, account: Account
) -> None:
    session = uuid.uuid4()
    sync(client, account, report(DAY1, lines=[line(session, T1, "   ")]))

    assert titles(notes(client, account)) == ["Recording"]


def test_syncing_again_refreshes_the_note_and_keeps_the_persons_organization(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    sid = library["planning"]
    folder = client.post("/v1/folders", headers=account.headers, json={"name": "Work"}).json()
    client.patch(
        f"/v1/notes/{sid}",
        headers=account.headers,
        json={"title": "Sprint planning", "folder_id": folder["id"], "starred": True},
    )

    sync(
        client,
        account,
        report(
            DAY1,
            items=[item(sid, "decision", "Ship the beta next Monday")],
            lines=[line(sid, T1, "Moving the date to Monday")],
        ),
    )

    note = client.get(f"/v1/notes/{sid}", headers=account.headers).json()["note"]
    assert note["title"] == "Sprint planning"
    assert note["folder_id"] == folder["id"]
    assert note["starred"] is True
    assert note["item_count"] == 1
    assert note["word_count"] == 5


def test_turning_transcript_sync_off_removes_the_transcript_from_the_server(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    sid = library["planning"]
    walk = library["walk"]
    # The next sync carries no transcripts (and not every session is listed).
    sync(
        client,
        account,
        report(DAY1, items=[item(sid, "decision", "Ship the beta on Friday")]),
        report(DAY2, items=[item(library["call"], "action", "Send the contract to Priya")]),
        transcripts=False,
    )

    detail = client.get(f"/v1/notes/{sid}", headers=account.headers).json()
    assert detail["note"]["line_count"] == 0
    assert detail["lines"] == []
    assert notes(client, account, q="invoices")["total"] == 0
    # A note whose only content was spoken words loses its spoken-word title with them.
    gone = client.get(f"/v1/notes/{walk}", headers=account.headers).json()["note"]
    assert gone["title"] == "Recording"
    assert gone["line_count"] == 0


def test_deleting_a_session_on_the_phone_removes_its_note(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    sync(client, account, deleted=[library["walk"]])

    assert notes(client, account)["total"] == 2
    assert client.get(f"/v1/notes/{library['walk']}", headers=account.headers).status_code == 404


def test_people_who_synced_before_notes_existed_get_them_built_on_first_use(
    client: TestClient, account: Account, db: Engine
) -> None:
    sid = uuid.uuid4()
    with db.begin() as c:
        c.execute(
            text(
                "INSERT INTO memory_items (id, user_id, session_id, memory_day, kind, text, "
                "confidence, extraction_model, extraction_version) "
                "VALUES (:id, :u, :s, :d, 'decision', 'Hire a designer', 'likely', 'm', 1)"
            ),
            {"id": uuid.uuid4(), "u": account.user_id, "s": sid, "d": DAY1},
        )
        c.execute(
            text(
                "INSERT INTO daily_reports (id, user_id, memory_day, status, report, "
                "extraction_version) VALUES (:id, :u, :d, 'complete', CAST(:r AS jsonb), 1)"
            ),
            {
                "id": uuid.uuid4(),
                "u": account.user_id,
                "d": DAY1,
                "r": json.dumps(report(DAY1, lines=[line(sid, T1, "We need a designer")])),
            },
        )

    page = notes(client, account, q="designer")

    assert titles(page) == ["Hire a designer"]
    assert page["notes"][0]["line_count"] == 1
    assert notes(client, account)["total"] == 1  # a second visit does not duplicate


def test_backfill_completes_correctly_across_more_than_one_batch(
    client: TestClient, account: Account, db: Engine, monkeypatch: pytest.MonkeyPatch
) -> None:
    """app/notes.py `backfill_notes` reads and writes in bounded batches so a single query
    never pulls an unbounded resultset into memory. A small patched batch size, with a
    fixture several multiples of it, forces the read loops over both `memory_items` and
    `daily_reports` (and the write loop) around more than once, proving the batching still
    produces a complete, correct result -- not just a single-batch happy path."""
    monkeypatch.setattr(notes_module, "BACKFILL_BATCH_SIZE", 4)
    item_sessions = [uuid.uuid4() for _ in range(11)]  # ~3x the patched batch size
    line_sessions = [uuid.uuid4() for _ in range(9)]  # ~2-3x the patched batch size

    with db.begin() as c:
        for sid in item_sessions:
            c.execute(
                text(
                    "INSERT INTO memory_items (id, user_id, session_id, memory_day, kind, "
                    "text, confidence, extraction_model, extraction_version) "
                    "VALUES (:id, :u, :s, :d, 'decision', :t, 'likely', 'm', 1)"
                ),
                {
                    "id": uuid.uuid4(),
                    "u": account.user_id,
                    "s": sid,
                    "d": DAY1,
                    "t": f"Decide {sid}",
                },
            )
        for n, sid in enumerate(line_sessions):
            day = DAY1 + timedelta(days=n + 1)
            c.execute(
                text(
                    "INSERT INTO daily_reports (id, user_id, memory_day, status, report, "
                    "extraction_version) VALUES (:id, :u, :d, 'complete', CAST(:r AS jsonb), 1)"
                ),
                {
                    "id": uuid.uuid4(),
                    "u": account.user_id,
                    "d": day,
                    "r": json.dumps(report(day, lines=[line(sid, T1, "Said something")])),
                },
            )

    batch_calls = {"items": 0, "rows": 0}
    original_scalars = AsyncSession.scalars

    async def counting_scalars(self: AsyncSession, statement: Any, *a: Any, **kw: Any) -> Any:
        descriptions = getattr(statement, "column_descriptions", None) or []
        entity = descriptions[0].get("type") if descriptions else None
        if entity is MemoryItem:
            batch_calls["items"] += 1
        elif entity is DailyReportRow:
            batch_calls["rows"] += 1
        return await original_scalars(self, statement, *a, **kw)

    monkeypatch.setattr(AsyncSession, "scalars", counting_scalars)

    page = notes(client, account)

    total = len(item_sessions) + len(line_sessions)
    assert page["total"] == total
    # 11 items at batch size 4 needs 3 read batches (4, 4, 3); 9 report rows needs 3 (4, 4, 1).
    assert batch_calls["items"] >= 3
    assert batch_calls["rows"] >= 3
    # A second visit does not duplicate, batched or not.
    assert notes(client, account)["total"] == total


# --- searching, filtering, sorting ----------------------------------------------------------------


def test_search_looks_in_titles_items_and_transcripts_and_needs_every_word(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    assert titles(notes(client, account, q="priya")) == ["Send the contract to Priya"]
    assert titles(notes(client, account, q="invoices")) == ["Ship the beta on Friday"]
    assert titles(notes(client, account, q="CONTRACT today")) == ["Send the contract to Priya"]
    assert notes(client, account, q="contract oat")["total"] == 0
    assert notes(client, account, q="zebra")["total"] == 0


def test_search_results_say_where_the_words_were_found(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    hit = notes(client, account, q="invoices")["notes"][0]
    assert "Marta will check the invoices" in hit["snippet"]
    assert notes(client, account)["notes"][0]["snippet"] is None


def test_search_treats_wildcards_as_plain_characters(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    assert notes(client, account, q="%")["total"] == 0
    assert notes(client, account, q="_")["total"] == 0


def test_a_persons_own_title_is_searchable_and_wins_over_the_automatic_one(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    client.patch(
        f"/v1/notes/{library['walk']}", headers=account.headers, json={"title": "Groceries"}
    )

    assert titles(notes(client, account, q="groceries")) == ["Groceries"]
    client.patch(f"/v1/notes/{library['walk']}", headers=account.headers, json={"title": "  "})
    assert "Groceries" not in titles(notes(client, account))


def test_sort_orders(client: TestClient, account: Account, library: dict[str, uuid.UUID]) -> None:
    assert titles(notes(client, account, sort="newest"))[0] == "Remember to buy oat milk and bread"
    assert titles(notes(client, account, sort="oldest"))[0] == "Ship the beta on Friday"
    assert titles(notes(client, account, sort="title")) == [
        "Remember to buy oat milk and bread",
        "Send the contract to Priya",
        "Ship the beta on Friday",
    ]
    assert titles(notes(client, account, sort="longest"))[0] == "Ship the beta on Friday"


def test_day_range_filters_and_paging(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    assert notes(client, account, day="2026-10-10")["total"] == 1
    assert notes(client, account, **{"from": "2026-10-11"})["total"] == 2
    assert notes(client, account, to="2026-10-09")["total"] == 0
    first = notes(client, account, limit=2)
    second = notes(client, account, limit=2, offset=2)
    assert first["total"] == 3
    assert len(first["notes"]) == 2
    assert len(second["notes"]) == 1
    assert not {n["session_id"] for n in first["notes"]} & {
        n["session_id"] for n in second["notes"]
    }


def test_bad_input_is_a_clear_error_not_a_crash(client: TestClient, account: Account) -> None:
    for params in ({"sort": "random"}, {"folder": "not-a-folder"}, {"limit": 0}, {"day": "x"}):
        assert client.get("/v1/notes", headers=account.headers, params=params).status_code == 422


# --- folders, stars, archive ----------------------------------------------------------------------


def make_folder(client: TestClient, account: Account, name: str) -> Any:
    response = client.post("/v1/folders", headers=account.headers, json={"name": name})
    assert response.status_code == 201, response.text
    return response.json()


def test_folders_hold_notes_and_report_their_counts(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    work = make_folder(client, account, "Work")
    home = make_folder(client, account, "Home")
    for key, folder in (("planning", work), ("call", work), ("walk", home)):
        response = client.patch(
            f"/v1/notes/{library[key]}", headers=account.headers, json={"folder_id": folder["id"]}
        )
        assert response.status_code == 200
    client.patch(f"/v1/notes/{library['call']}", headers=account.headers, json={"starred": True})

    listing = client.get("/v1/folders", headers=account.headers).json()

    assert {f["name"]: f["notes"] for f in listing["folders"]} == {"Home": 1, "Work": 2}
    assert listing["totals"] == {"all": 3, "starred": 1, "unfiled": 0, "archived": 0}
    assert sorted(titles(notes(client, account, folder=work["id"]))) == [
        "Send the contract to Priya",
        "Ship the beta on Friday",
    ]
    assert titles(notes(client, account, starred="true")) == ["Send the contract to Priya"]


def test_unfiled_means_in_no_folder(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    work = make_folder(client, account, "Work")
    client.patch(
        f"/v1/notes/{library['call']}", headers=account.headers, json={"folder_id": work["id"]}
    )

    assert notes(client, account, folder="none")["total"] == 2
    client.patch(f"/v1/notes/{library['call']}", headers=account.headers, json={"folder_id": None})
    assert notes(client, account, folder="none")["total"] == 3


def test_deleting_a_folder_unfiles_its_notes_and_deletes_nothing_else(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    work = make_folder(client, account, "Work")
    client.patch(
        f"/v1/notes/{library['call']}", headers=account.headers, json={"folder_id": work["id"]}
    )

    assert client.delete(f"/v1/folders/{work['id']}", headers=account.headers).status_code == 204

    assert notes(client, account)["total"] == 3
    assert notes(client, account, folder="none")["total"] == 3
    assert client.get("/v1/folders", headers=account.headers).json()["folders"] == []
    assert client.delete(f"/v1/folders/{work['id']}", headers=account.headers).status_code == 404


def test_folder_names_are_tidied_and_unique_ignoring_case(
    client: TestClient, account: Account
) -> None:
    created = make_folder(client, account, "  Side   projects ")
    assert created["name"] == "Side projects"

    clash = client.post("/v1/folders", headers=account.headers, json={"name": "SIDE PROJECTS"})
    assert clash.status_code == 409
    assert clash.json()["error"]["code"] == "folder_exists"
    for bad in ("", "   ", "x" * 61):
        assert (
            client.post("/v1/folders", headers=account.headers, json={"name": bad}).status_code
            == 422
        )


def test_renaming_a_folder_keeps_its_notes_and_refuses_a_taken_name(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    work = make_folder(client, account, "Work")
    other = make_folder(client, account, "Other")
    client.patch(
        f"/v1/notes/{library['call']}", headers=account.headers, json={"folder_id": work["id"]}
    )

    renamed = client.patch(
        f"/v1/folders/{work['id']}", headers=account.headers, json={"name": "Job"}
    )
    assert renamed.json()["name"] == "Job"
    assert renamed.json()["notes"] == 1
    assert (
        client.patch(
            f"/v1/folders/{other['id']}", headers=account.headers, json={"name": "job"}
        ).status_code
        == 409
    )
    # Keeping its own name (just changing case) is allowed.
    assert (
        client.patch(
            f"/v1/folders/{work['id']}", headers=account.headers, json={"name": "JOB"}
        ).status_code
        == 200
    )


def test_there_is_a_limit_on_folders(
    client: TestClient, account: Account, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(notes_api, "MAX_FOLDERS", 2)
    make_folder(client, account, "A")
    make_folder(client, account, "B")

    response = client.post("/v1/folders", headers=account.headers, json={"name": "C"})

    assert response.status_code == 409
    assert response.json()["error"]["code"] == "too_many_folders"


def test_archiving_hides_a_note_without_deleting_it_and_restoring_brings_it_back(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    sid = library["call"]
    client.patch(f"/v1/notes/{sid}", headers=account.headers, json={"archived": True})

    assert notes(client, account)["total"] == 2
    assert titles(notes(client, account, archived="true")) == ["Send the contract to Priya"]
    assert client.get("/v1/folders", headers=account.headers).json()["totals"]["archived"] == 1
    assert client.get(f"/v1/notes/{sid}", headers=account.headers).status_code == 200

    client.patch(f"/v1/notes/{sid}", headers=account.headers, json={"archived": False})
    assert notes(client, account)["total"] == 3


def test_bulk_changes_apply_to_every_note_listed(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    work = make_folder(client, account, "Work")
    ids = [str(library["planning"]), str(library["walk"]), str(uuid.uuid4())]

    moved = client.post(
        "/v1/notes/bulk",
        headers=account.headers,
        json={"session_ids": ids, "folder_id": work["id"]},
    )
    assert moved.json() == {"updated": 2}  # the unknown id is skipped
    assert notes(client, account, folder=work["id"])["total"] == 2
    client.post(
        "/v1/notes/bulk", headers=account.headers, json={"session_ids": ids, "archived": True}
    )
    assert notes(client, account)["total"] == 1
    # Nothing to change is not an error.
    assert client.post(
        "/v1/notes/bulk", headers=account.headers, json={"session_ids": ids}
    ).json() == {"updated": 0}
    assert (
        client.post(
            "/v1/notes/bulk", headers=account.headers, json={"session_ids": ids, "title": "x"}
        ).status_code
        == 422
    )
    assert (
        client.post("/v1/notes/bulk", headers=account.headers, json={"session_ids": []}).status_code
        == 422
    )


# --- one note -------------------------------------------------------------------------------------


def test_a_note_opens_with_its_items_and_transcript_in_order(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    detail = client.get(f"/v1/notes/{library['planning']}", headers=account.headers).json()

    assert [i["text"] for i in detail["items"]] == [
        "Ship the beta on Friday",
        "Try a standing desk",
    ]
    assert [ln["text"] for ln in detail["lines"]] == [
        "We should ship on friday if QA agrees",
        "Marta will check the invoices",
    ]
    assert detail["lines"][0]["speaker"] == "A"
    assert detail["note"]["item_count"] == 2


def test_a_dismissed_item_stops_counting_and_stops_matching_search(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    detail = client.get(f"/v1/notes/{library['planning']}", headers=account.headers).json()
    idea = next(i for i in detail["items"] if i["kind"] == "idea")
    assert notes(client, account, q="standing")["total"] == 1

    client.patch(
        f"/v1/memory/items/{idea['id']}", headers=account.headers, json={"dismissed": True}
    )

    after = client.get(f"/v1/notes/{library['planning']}", headers=account.headers).json()
    assert after["note"]["item_count"] == 1
    assert notes(client, account, q="standing")["total"] == 0


def test_an_unknown_note_or_folder_is_not_found(client: TestClient, account: Account) -> None:
    ghost = uuid.uuid4()
    assert client.get(f"/v1/notes/{ghost}", headers=account.headers).status_code == 404
    assert client.patch(f"/v1/notes/{ghost}", headers=account.headers, json={}).status_code == 404
    assert (
        client.patch(
            f"/v1/folders/{ghost}", headers=account.headers, json={"name": "x"}
        ).status_code
        == 404
    )


# --- who may see what -----------------------------------------------------------------------------


def test_nobody_can_see_or_change_someone_elses_notes_or_folders(
    client: TestClient, db: Engine, account: Account, library: dict[str, uuid.UUID]
) -> None:
    mine = make_folder(client, account, "Mine")
    stranger = make_account(db, scopes=ALL)

    assert notes(client, stranger)["total"] == 0
    assert client.get(f"/v1/notes/{library['call']}", headers=stranger.headers).status_code == 404
    assert (
        client.patch(
            f"/v1/notes/{library['call']}", headers=stranger.headers, json={"starred": True}
        ).status_code
        == 404
    )
    # Another person's folder is as good as missing.
    theirs = client.patch(
        f"/v1/notes/{library['call']}", headers=account.headers, json={"starred": False}
    )
    assert theirs.status_code == 200
    sync(client, stranger, report(DAY1, items=[item(uuid.uuid4(), "idea", "Mine alone")]))
    own = notes(client, stranger)["notes"][0]["session_id"]
    assert (
        client.patch(
            f"/v1/notes/{own}", headers=stranger.headers, json={"folder_id": mine["id"]}
        ).status_code
        == 404
    )
    assert client.delete(f"/v1/folders/{mine['id']}", headers=stranger.headers).status_code == 404
    assert client.get("/v1/folders", headers=stranger.headers).json()["folders"] == []
    assert titles(notes(client, stranger, q="oat")) == []
    assert {n["title"] for n in notes(client, account)["notes"]} == {
        "Ship the beta on Friday",
        "Send the contract to Priya",
        "Remember to buy oat milk and bread",
    }


def test_a_key_without_the_transcript_scope_never_sees_or_searches_spoken_words(
    client: TestClient, db: Engine, account: Account, library: dict[str, uuid.UUID]
) -> None:
    with db.begin() as c:
        c.execute(
            text("UPDATE access_tokens SET scopes = ARRAY['memory:read'] WHERE user_id = :u"),
            {"u": account.user_id},
        )

    walk = notes(client, account, q="oat")
    assert walk["total"] == 0  # words found only in the transcript are invisible
    page = notes(client, account)
    by_id = {n["session_id"]: n for n in page["notes"]}
    assert by_id[str(library["walk"])]["title"] == "Recording"
    assert by_id[str(library["walk"])]["preview"] == ""
    assert by_id[str(library["planning"])]["title"] == "Ship the beta on Friday"
    assert by_id[str(library["planning"])]["line_count"] == 0
    detail = client.get(f"/v1/notes/{library['planning']}", headers=account.headers).json()
    assert detail["lines"] is None
    # Item words still search.
    assert notes(client, account, q="priya")["total"] == 1
    # And changing organization needs write access.
    assert (
        client.patch(
            f"/v1/notes/{library['call']}", headers=account.headers, json={"starred": True}
        ).status_code
        == 403
    )
    assert (
        client.post("/v1/folders", headers=account.headers, json={"name": "x"}).status_code == 403
    )


# --- pure helpers ---------------------------------------------------------------------------------


def test_clip_cuts_at_a_word_and_flattens_whitespace() -> None:
    assert clip("  one   two\nthree ", 50) == "one two three"
    cut = clip("alpha beta gamma delta epsilon", 18)
    assert cut == "alpha beta gamma…"
    assert len(cut) <= 18


def test_build_fields_orders_lines_and_never_invents_times() -> None:
    sid = uuid.uuid4()
    fields = build_fields(
        SessionContent(sid, DAY1, items=[], lines=[(T1 + 10, "second"), (T1, "first")])
    )
    assert fields.transcript_text == "first\nsecond"
    assert fields.auto_title == "first"
    assert fields.title_from_transcript is True
    assert fields.started_at == datetime.fromtimestamp(T1 / 1000, UTC)
    assert build_fields(SessionContent(sid, DAY1)).started_at is None


def test_export_includes_folders_and_how_each_note_was_organized(
    client: TestClient, account: Account, library: dict[str, uuid.UUID], store: FakeObjectStore
) -> None:
    work = make_folder(client, account, "Work")
    client.patch(
        f"/v1/notes/{library['call']}",
        headers=account.headers,
        json={"title": "Contract call", "folder_id": work["id"], "starred": True},
    )

    assert client.get("/v1/export", headers=account.headers).status_code == 200
    document = json.loads(store.objects[f"users/{account.user_id}/export.json"][0])

    assert [f["name"] for f in document["note_folders"]] == ["Work"]
    organized = {n["session_id"]: n for n in document["notes"]}[str(library["call"])]
    assert organized["title"] == "Contract call"
    assert organized["renamed"] is True
    assert organized["folder_id"] == work["id"]
    assert organized["starred"] is True
    assert len(document["notes"]) == 3


def test_snippets_start_and_end_on_whole_words_and_keep_the_match() -> None:
    long_text = "alpha " * 40 + "needle in the middle " + "omega " * 40
    snippet = notes_api._snippet(long_text, ["NEEDLE"])

    assert snippet is not None
    assert "needle in the middle" in snippet
    assert snippet.startswith("…alpha")
    assert snippet.endswith("omega…")
    assert notes_api._snippet("short needle here", ["needle"]) == "short needle here"
    assert notes_api._snippet("nothing to see", ["needle"]) is None


def test_snippets_separate_transcript_lines_with_a_dot() -> None:
    assert notes_api._snippet("first line\nsecond needle line", ["needle"]) == (
        "first line · second needle line"
    )


# --- titles from the phone, and removing a note from the account ----------------------------


def declared(session: uuid.UUID, title: str | None, at: int | None = T1) -> dict[str, object]:
    return {"sessionId": str(session), "title": title, "startedAtEpochMs": at}


def with_sessions(base: dict[str, object], *sessions: dict[str, object]) -> dict[str, object]:
    return {**base, "sessions": list(sessions)}


def test_the_name_given_on_the_phone_titles_the_note_and_a_web_rename_beats_it(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    sid = library["planning"]
    sync(
        client,
        account,
        with_sessions(
            report(DAY1, items=[item(sid, "decision", "Ship the beta on Friday")]),
            declared(sid, "  Sprint   planning "),
        ),
    )
    note = client.get(f"/v1/notes/{sid}", headers=account.headers).json()["note"]
    assert note["title"] == "Sprint planning"
    assert note["renamed"] is False
    assert titles(notes(client, account, q="sprint")) == ["Sprint planning"]

    client.patch(f"/v1/notes/{sid}", headers=account.headers, json={"title": "Planning (web)"})
    sync(client, account, with_sessions(report(DAY1), declared(sid, "Planning v2")))
    assert client.get(f"/v1/notes/{sid}", headers=account.headers).json()["note"]["title"] == (
        "Planning (web)"
    )
    client.patch(f"/v1/notes/{sid}", headers=account.headers, json={"title": ""})
    assert client.get(f"/v1/notes/{sid}", headers=account.headers).json()["note"]["title"] == (
        "Planning v2"
    )


def test_clearing_the_name_on_the_phone_clears_it_here_but_an_older_phone_leaves_it(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    sid = library["planning"]
    sync(client, account, with_sessions(report(DAY1), declared(sid, "Named")))
    sync(client, account, report(DAY1, items=[item(sid, "decision", "Ship the beta on Friday")]))
    assert client.get(f"/v1/notes/{sid}", headers=account.headers).json()["note"]["title"] == (
        "Named"
    )

    sync(client, account, with_sessions(report(DAY1), declared(sid, None)))
    assert client.get(f"/v1/notes/{sid}", headers=account.headers).json()["note"]["title"] != (
        "Named"
    )


def test_a_session_listed_without_words_still_gets_a_note_with_its_start_time(
    client: TestClient, account: Account
) -> None:
    quiet = uuid.uuid4()
    sync(
        client,
        account,
        with_sessions(report(DAY1), declared(quiet, "Standup", T1)),
        transcripts=False,
    )

    page = notes(client, account)

    assert titles(page) == ["Standup"]
    assert page["notes"][0]["started_at"] == datetime.fromtimestamp(
        T1 / 1000, UTC
    ).isoformat().replace("+00:00", "Z")
    assert page["notes"][0]["line_count"] == 0


def test_removing_a_note_deletes_the_servers_copy_and_stops_the_phone_bringing_it_back(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    sid = library["planning"]

    assert client.delete(f"/v1/notes/{sid}", headers=account.headers).status_code == 204

    assert client.get(f"/v1/notes/{sid}", headers=account.headers).status_code == 404
    assert notes(client, account)["total"] == 2
    assert notes(client, account, q="invoices")["total"] == 0
    assert client.get("/v1/memory/items", headers=account.headers).json()["total"] == 1
    # The phone still has it and sends it again: it stays gone.
    sync(
        client,
        account,
        with_sessions(
            report(
                DAY1,
                items=[item(sid, "decision", "Ship the beta on Friday")],
                lines=[line(sid, T1, "We should ship on friday")],
            ),
            declared(sid, "Planning"),
        ),
    )
    assert notes(client, account)["total"] == 2
    assert client.delete(f"/v1/notes/{sid}", headers=account.headers).status_code == 404


def test_deleting_it_on_the_phone_too_clears_the_marker_so_a_new_session_could_reuse_nothing(
    client: TestClient, account: Account, db: Engine, library: dict[str, uuid.UUID]
) -> None:
    sid = library["planning"]
    client.delete(f"/v1/notes/{sid}", headers=account.headers)

    sync(client, account, deleted=[sid])

    with db.connect() as c:
        left = c.scalar(
            text("SELECT count(*) FROM deleted_notes WHERE session_id = :s"), {"s": sid}
        )
    assert left == 0


def test_nobody_can_remove_someone_elses_note_and_a_read_only_key_cannot_remove_any(
    client: TestClient, db: Engine, account: Account, library: dict[str, uuid.UUID]
) -> None:
    stranger = make_account(db, scopes=ALL)
    assert (
        client.delete(f"/v1/notes/{library['call']}", headers=stranger.headers).status_code == 404
    )
    reader = make_account(db, scopes=("memory:read",))
    assert client.delete(f"/v1/notes/{library['call']}", headers=reader.headers).status_code == 403
    assert notes(client, account)["total"] == 3


# --- Ask reaches transcripts ---------------------------------------------------------------


def ask(client: TestClient, account: Account, question: str) -> Any:
    response = client.post("/v1/ask", headers=account.headers, json={"question": question})
    assert response.status_code == 200, response.text
    return response.json()


def test_ask_points_to_notes_that_mention_the_question_when_no_highlight_matches(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    result = ask(client, account, "What did Marta say about the invoices?")

    assert result["items"] == []
    assert result["abstained"] is True
    assert "these notes mention it" in result["answer"]
    assert [n["session_id"] for n in result["notes"]] == [str(library["planning"])]
    assert "invoices" in result["notes"][0]["snippet"]


def test_ask_lists_matching_notes_next_to_matching_items_best_match_first(
    client: TestClient, account: Account, library: dict[str, uuid.UUID]
) -> None:
    result = ask(client, account, "contract Priya")

    assert [i["text"] for i in result["items"]] == ["Send the contract to Priya"]
    assert result["notes"][0]["session_id"] == str(library["call"])


def test_ask_finds_nothing_cleanly_and_never_reads_transcripts_without_the_scope(
    client: TestClient, db: Engine, account: Account, library: dict[str, uuid.UUID]
) -> None:
    assert ask(client, account, "zebra crossing")["notes"] == []
    with db.begin() as c:
        c.execute(
            text("UPDATE access_tokens SET scopes = ARRAY['memory:read'] WHERE user_id = :u"),
            {"u": account.user_id},
        )

    result = ask(client, account, "What did Marta say about the invoices?")

    assert result["notes"] == []
    assert result["abstained"] is True


def test_ask_skips_archived_notes_and_other_peoples(
    client: TestClient, db: Engine, account: Account, library: dict[str, uuid.UUID]
) -> None:
    client.patch(
        f"/v1/notes/{library['planning']}", headers=account.headers, json={"archived": True}
    )
    stranger = make_account(db, scopes=ALL)

    assert ask(client, account, "the invoices")["notes"] == []
    assert ask(client, stranger, "the invoices")["notes"] == []
