import json
import uuid
from collections.abc import Iterator
from datetime import UTC, date, datetime

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.main import create_app
from app.reports.daily import Coverage, DailyReport
from tests.conftest import FakeObjectStore, make_account, settings_for
from tests.seed import row_counts, seed_user_data

pytestmark = pytest.mark.anyio


@pytest.fixture
def client(db: Engine, database_url: str) -> Iterator[TestClient]:
    with TestClient(create_app(settings_for(database_url))) as test_client:
        yield test_client


def grant_transcript_read(db: Engine, user_id: uuid.UUID) -> None:
    with db.begin() as connection:
        connection.execute(
            text(
                "UPDATE access_tokens SET scopes = scopes || ARRAY['transcript:read'] "
                "WHERE user_id = :user"
            ),
            {"user": user_id},
        )


def sync_payload(*, include_transcripts: bool, with_transcript: bool) -> dict[str, object]:
    report: dict[str, object] = {
        "memoryDay": date(2026, 10, 12).isoformat(),
        "zoneId": "UTC",
        "headline": "A saved day",
        "summary": "A short report.",
        "items": [],
        "coverage": {
            "recordedMs": 60_000,
            "speechMs": 30_000,
            "transcribedMs": 30_000,
            "sessions": 1,
        },
        "generatedAtEpochMs": 1_797_120_000_000,
        "extractionModel": "test-model",
        "extractionVersion": 1,
    }
    if with_transcript:
        report["transcriptLines"] = [
            {
                "sessionId": "session-1",
                "startEpochMs": 1_797_120_000_000,
                "speaker": "Speaker 1",
                "text": "A transcript line that was explicitly opted in.",
            },
        ]
    return {
        "device": "test-device",
        "generatedAtEpochMs": 1_797_120_000_000,
        "includeTranscripts": include_transcripts,
        "reports": [report],
    }


def test_opted_in_transcript_is_available_to_authenticated_report_clients(
    client: TestClient,
    db: Engine,
) -> None:
    account = make_account(db)
    response = client.post(
        "/v1/sync",
        headers=account.headers,
        json=sync_payload(include_transcripts=True, with_transcript=True),
    )
    assert response.status_code == 200

    default_scope_report = client.get(
        "/v1/reports/2026-10-12",
        headers=account.headers,
    )
    assert default_scope_report.status_code == 200
    assert "transcriptLines" not in default_scope_report.json()

    grant_transcript_read(db, account.user_id)
    transcript_scope_report = client.get(
        "/v1/reports/2026-10-12",
        headers=account.headers,
    )

    assert transcript_scope_report.status_code == 200
    assert transcript_scope_report.json()["transcriptLines"] == [
        {
            "sessionId": "session-1",
            "startEpochMs": 1_797_120_000_000,
            "speaker": "Speaker 1",
            "text": "A transcript line that was explicitly opted in.",
        },
    ]


def test_sync_without_transcript_opt_in_removes_previously_synced_transcript(
    client: TestClient,
    db: Engine,
) -> None:
    account = make_account(db)
    client.post(
        "/v1/sync",
        headers=account.headers,
        json=sync_payload(include_transcripts=True, with_transcript=True),
    )

    response = client.post(
        "/v1/sync",
        headers=account.headers,
        json=sync_payload(include_transcripts=False, with_transcript=False),
    )
    assert response.status_code == 200

    grant_transcript_read(db, account.user_id)
    report = client.get(
        "/v1/reports/2026-10-12",
        headers=account.headers,
    )

    assert report.status_code == 200
    assert "transcriptLines" not in report.json()


def test_transcript_lines_are_bounded_and_invalid_payload_is_not_saved(
    client: TestClient,
    db: Engine,
) -> None:
    account = make_account(db)
    payload = sync_payload(include_transcripts=True, with_transcript=True)
    reports = payload["reports"]
    assert isinstance(reports, list)
    reports[0]["transcriptLines"][0]["text"] = "x" * 5_001

    response = client.post("/v1/sync", headers=account.headers, json=payload)

    assert response.status_code == 422
    missing = client.get("/v1/reports/2026-10-12", headers=account.headers)
    assert missing.status_code == 404


SESSION_1 = "018f3c2e-8a11-7000-8000-000000000001"
SESSION_2 = "018f3c2e-8a11-7000-8000-000000000002"


def deletion_payload(*session_ids: str) -> dict[str, object]:
    return {
        "device": "test-device",
        "generatedAtEpochMs": 1_797_120_000_000,
        "includeTranscripts": False,
        "reports": [],
        "deletedSessions": list(session_ids),
    }


def two_session_payload() -> dict[str, object]:
    """One day, two sessions: an item and a transcript line each."""

    def item(session_id: str) -> dict[str, object]:
        return {
            "kind": "action",
            "text": f"Follow up from {session_id}",
            "confidence": "likely",
            "sessionId": session_id,
        }

    def line(session_id: str) -> dict[str, object]:
        return {
            "sessionId": session_id,
            "startEpochMs": 1_797_120_000_000,
            "speaker": "Speaker 1",
            "text": f"A line from {session_id}",
        }

    return {
        "device": "test-device",
        "generatedAtEpochMs": 1_797_120_000_000,
        "includeTranscripts": True,
        "reports": [
            {
                "memoryDay": date(2026, 10, 12).isoformat(),
                "zoneId": "UTC",
                "headline": "A day with two sessions",
                "summary": "A short report.",
                "items": [item(SESSION_1), item(SESSION_2)],
                "coverage": {
                    "recordedMs": 60_000,
                    "speechMs": 30_000,
                    "transcribedMs": 30_000,
                    "sessions": 2,
                },
                "generatedAtEpochMs": 1_797_120_000_000,
                "extractionModel": "test-model",
                "extractionVersion": 1,
                "transcriptLines": [line(SESSION_1), line(SESSION_2)],
            },
        ],
    }


def memory_item_count(db: Engine, session_id: str) -> int:
    with db.begin() as connection:
        return int(
            connection.scalar(
                text("SELECT COUNT(*) FROM memory_items WHERE session_id = :s"),
                {"s": session_id},
            )
            or 0
        )


def test_deleting_a_synced_session_removes_only_its_derived_data(
    client: TestClient,
    db: Engine,
) -> None:
    account = make_account(db)
    assert (
        client.post("/v1/sync", headers=account.headers, json=two_session_payload()).status_code
        == 200
    )

    response = client.post("/v1/sync", headers=account.headers, json=deletion_payload(SESSION_1))

    assert response.status_code == 200
    assert response.json() == {"synced": 0, "items": 0, "deletedSessions": 1}
    assert memory_item_count(db, SESSION_1) == 0
    assert memory_item_count(db, SESSION_2) == 1

    grant_transcript_read(db, account.user_id)
    report = client.get("/v1/reports/2026-10-12", headers=account.headers).json()
    assert [i["evidence"][0]["session_id"] for i in report["action_items"]] == [SESSION_2]
    assert [line["sessionId"] for line in report["transcriptLines"]] == [SESSION_2]


def test_deleting_every_session_of_a_day_removes_the_report(
    client: TestClient,
    db: Engine,
) -> None:
    account = make_account(db)
    client.post("/v1/sync", headers=account.headers, json=two_session_payload())

    response = client.post(
        "/v1/sync",
        headers=account.headers,
        json=deletion_payload(SESSION_1, SESSION_2),
    )

    assert response.status_code == 200
    assert client.get("/v1/reports/2026-10-12", headers=account.headers).status_code == 404
    assert memory_item_count(db, SESSION_1) == 0
    assert memory_item_count(db, SESSION_2) == 0


def test_deletion_is_idempotent_and_scoped_to_its_owner(
    client: TestClient,
    db: Engine,
) -> None:
    owner = make_account(db)
    other = make_account(db)
    client.post("/v1/sync", headers=owner.headers, json=two_session_payload())
    client.post("/v1/sync", headers=other.headers, json=two_session_payload())

    for _ in range(2):
        assert (
            client.post(
                "/v1/sync", headers=owner.headers, json=deletion_payload(SESSION_1)
            ).status_code
            == 200
        )

    assert client.get("/v1/reports/2026-10-12", headers=owner.headers).status_code == 200
    other_report = client.get("/v1/reports/2026-10-12", headers=other.headers).json()
    assert len(other_report["action_items"]) == 2
    assert memory_item_count(db, SESSION_1) == 1  # the other user's copy survives


def test_out_of_contract_items_are_rejected_with_field_errors(
    client: TestClient,
    db: Engine,
) -> None:
    account = make_account(db)

    def payload_with(item: dict[str, object]) -> dict[str, object]:
        body = two_session_payload()
        reports = body["reports"]
        assert isinstance(reports, list)
        reports[0]["items"] = [item]
        return body

    unknown_confidence = payload_with(
        {"kind": "action", "text": "t", "confidence": "high", "sessionId": SESSION_1}
    )
    commitment_without_direction = payload_with(
        {"kind": "commitment", "text": "t", "confidence": "likely", "sessionId": SESSION_1}
    )
    non_uuid_session = payload_with(
        {"kind": "action", "text": "t", "confidence": "likely", "sessionId": "session-1"}
    )

    for bad in (unknown_confidence, commitment_without_direction, non_uuid_session):
        response = client.post("/v1/sync", headers=account.headers, json=bad)
        assert response.status_code == 422
        assert client.get("/v1/reports/2026-10-12", headers=account.headers).status_code == 404


def test_deletion_payload_is_validated(client: TestClient, db: Engine) -> None:
    account = make_account(db)
    too_long = client.post(
        "/v1/sync",
        headers=account.headers,
        json=deletion_payload("s" * 129),
    )
    too_many = client.post(
        "/v1/sync",
        headers=account.headers,
        json=deletion_payload(*[f"018f3c2e-8a11-7000-8000-{i:012d}" for i in range(501)]),
    )

    assert too_long.status_code == 422
    assert too_many.status_code == 422


def post_day(client: TestClient, account_headers: dict[str, str], body: dict[str, object]) -> int:
    return int(client.post("/v1/sync", headers=account_headers, json=body).status_code)


def single_session_payload(
    session_id: str, text_: str, day: str = "2026-10-12"
) -> dict[str, object]:
    body = two_session_payload()
    reports = body["reports"]
    assert isinstance(reports, list)
    report = reports[0]
    report["memoryDay"] = day
    report["items"] = [
        {"kind": "action", "text": text_, "confidence": "likely", "sessionId": session_id}
    ]
    report["transcriptLines"] = [
        {"sessionId": session_id, "startEpochMs": 1, "speaker": None, "text": text_}
    ]
    return body


def test_syncing_one_session_keeps_other_sessions_for_the_day(
    client: TestClient, db: Engine
) -> None:
    account = make_account(db)
    assert (
        post_day(client, account.headers, single_session_payload(SESSION_1, "from device A")) == 200
    )
    assert (
        post_day(client, account.headers, single_session_payload(SESSION_2, "from device B")) == 200
    )
    assert post_day(client, account.headers, single_session_payload(SESSION_2, "B again")) == 200

    grant_transcript_read(db, account.user_id)
    report = client.get("/v1/reports/2026-10-12", headers=account.headers).json()
    assert sorted(i["text"] for i in report["action_items"]) == ["B again", "from device A"]
    assert sorted(line["text"] for line in report["transcriptLines"]) == [
        "B again",
        "from device A",
    ]
    assert memory_item_count(db, SESSION_1) == 1
    assert memory_item_count(db, SESSION_2) == 1


def test_report_shape_is_the_same_whichever_writer_ran_last(client: TestClient, db: Engine) -> None:
    account = make_account(db)
    day = date(2026, 10, 12)
    assert post_day(client, account.headers, single_session_payload(SESSION_1, "synced")) == 200
    synced = client.get("/v1/reports/2026-10-12", headers=account.headers).json()

    server_built = DailyReport(
        memory_day=day,
        timezone="UTC",
        generated_at=datetime(2026, 10, 12, tzinfo=UTC),
        status="complete",
        coverage=Coverage(recorded_minutes=1),
    ).model_dump(mode="json")
    with db.begin() as connection:
        connection.execute(
            text("UPDATE daily_reports SET report = CAST(:r AS jsonb) WHERE user_id = :u"),
            {"r": json.dumps(server_built), "u": account.user_id},
        )
    rebuilt = client.get("/v1/reports/2026-10-12", headers=account.headers).json()

    assert set(synced) - {"transcriptLines"} == set(rebuilt)
    assert synced["schema_version"] == 1
    assert synced["action_items"][0]["evidence"][0]["session_id"] == SESSION_1

    # A sync after server-side extraction keeps that extraction's items for other sessions.
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO memory_items (id, user_id, session_id, memory_day, kind, text, "
                "confidence, extraction_model, extraction_version) VALUES (:id, :u, :s, :d, "
                "'idea', 'server idea', 'likely', 'm', 1)"
            ),
            {"id": uuid.uuid4(), "u": account.user_id, "s": SESSION_1, "d": day},
        )
    assert post_day(client, account.headers, single_session_payload(SESSION_2, "later")) == 200
    merged = client.get("/v1/reports/2026-10-12", headers=account.headers).json()
    assert [i["text"] for i in merged["ideas"]] == ["server idea"]
    assert [i["text"] for i in merged["action_items"]] == ["synced", "later"]


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("memoryDay", "not-a-date"),
        ("memoryDay", "2026-13-45"),
        ("zoneId", "Nowhere/Land"),
        ("headline", "h" * 281),
        ("summary", "s" * 1_681),
        ("extractionModel", "m" * 81),
    ],
)
def test_report_fields_are_validated_with_field_locations(
    client: TestClient, db: Engine, field: str, value: str
) -> None:
    account = make_account(db)
    body = two_session_payload()
    reports = body["reports"]
    assert isinstance(reports, list)
    reports[0][field] = value

    response = client.post("/v1/sync", headers=account.headers, json=body)

    assert response.status_code == 422
    assert field in json.dumps(response.json())


@pytest.mark.parametrize(
    ("field", "value"),
    [("text", "t" * 281), ("owner", "o" * 81), ("counterpart", "c" * 81), ("quote", "q" * 281)],
)
def test_item_text_is_rejected_not_truncated(
    client: TestClient, db: Engine, field: str, value: str
) -> None:
    account = make_account(db)
    body = two_session_payload()
    reports = body["reports"]
    assert isinstance(reports, list)
    reports[0]["items"] = [
        {
            "kind": "action",
            "text": "t",
            "confidence": "likely",
            "sessionId": SESSION_1,
            field: value,
        }
    ]

    assert client.post("/v1/sync", headers=account.headers, json=body).status_code == 422


def test_device_and_report_count_are_capped(client: TestClient, db: Engine) -> None:
    account = make_account(db)
    long_device = two_session_payload()
    long_device["device"] = "d" * 81
    many = two_session_payload()
    reports = many["reports"]
    assert isinstance(reports, list)
    many["reports"] = [reports[0]] * 32

    assert client.post("/v1/sync", headers=account.headers, json=long_device).status_code == 422
    assert client.post("/v1/sync", headers=account.headers, json=many).status_code == 422


def test_deleted_session_is_scrubbed_from_server_built_reports_and_server_rows(
    db: Engine, database_url: str
) -> None:
    store = FakeObjectStore()
    account = make_account(db)
    other = make_account(db)
    keep_session = uuid.UUID(SESSION_2)
    gone_session = uuid.UUID(SESSION_1)
    gone = seed_user_data(db, account.user_id, session_id=gone_session)
    seed_user_data(db, account.user_id, session_id=keep_session, with_credential=False)
    other_rows = seed_user_data(db, other.user_id, session_id=gone_session)
    store.put(f"users/{account.user_id}/audio/{gone['upload']}.aac", b"audio")
    store.put(f"users/{other.user_id}/audio/{other_rows['upload']}.aac", b"audio")

    def evidence(session: str, text_: str) -> dict[str, object]:
        return {
            "text": text_,
            "confidence": "likely",
            "evidence": [{"session_id": session, "at": "2026-10-12T00:00:00Z"}],
        }

    stored = DailyReport.model_validate(
        {
            "memory_day": "2026-10-12",
            "timezone": "UTC",
            "generated_at": "2026-10-12T00:00:00Z",
            "status": "complete",
            "coverage": {"recorded_minutes": 1},
            "decisions": [evidence(SESSION_1, "gone"), evidence(SESSION_2, "kept")],
            "ideas": [evidence(SESSION_1, "gone idea")],
        }
    ).model_dump(mode="json")
    with db.begin() as connection:
        connection.execute(
            text("UPDATE daily_reports SET report = CAST(:r AS jsonb) WHERE user_id = :u"),
            {"r": json.dumps(stored), "u": account.user_id},
        )

    with TestClient(create_app(settings_for(database_url), object_store=store)) as client:
        assert post_day(client, account.headers, deletion_payload(SESSION_1)) == 200
        report = client.get(
            f"/v1/reports/{datetime.now(UTC).date().isoformat()}", headers=account.headers
        )

    # Server-held data of the deleted session is gone for this user only.
    counts = row_counts(db, account.user_id)
    assert counts["audio_uploads"] == 1
    assert counts["transcripts"] == 1
    assert counts["transcript_segments"] == 1
    assert counts["sealed_deliveries"] == 1
    assert counts["memory_items"] == 1
    assert counts["memory_item_evidence"] == 1
    assert counts["processing_jobs"] == 2  # the kept session's transcribe + extract jobs
    assert f"users/{account.user_id}/audio/{gone['upload']}.aac" not in store.objects
    assert row_counts(db, other.user_id)["audio_uploads"] == 1
    assert f"users/{other.user_id}/audio/{other_rows['upload']}.aac" in store.objects
    assert report.status_code == 200
    body = report.json()
    assert [d["text"] for d in body["decisions"]] == ["kept"]
    assert body["ideas"] == []


def test_deletion_fails_closed_when_object_store_fails(db: Engine, database_url: str) -> None:
    class Failing(FakeObjectStore):
        async def delete(self, key: str) -> None:
            raise OSError("down")

    account = make_account(db)
    seed_user_data(db, account.user_id, session_id=uuid.UUID(SESSION_1))
    with TestClient(create_app(settings_for(database_url), object_store=Failing())) as client:
        assert post_day(client, account.headers, deletion_payload(SESSION_1)) == 503
    assert row_counts(db, account.user_id)["audio_uploads"] == 1
    assert row_counts(db, account.user_id)["memory_items"] == 1


def test_deletion_with_stored_audio_but_no_object_store_is_503(
    db: Engine, client: TestClient
) -> None:
    account = make_account(db)
    seed_user_data(db, account.user_id, session_id=uuid.UUID(SESSION_1))
    assert post_day(client, account.headers, deletion_payload(SESSION_1)) == 503
