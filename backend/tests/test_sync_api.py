import uuid
from collections.abc import Iterator
from datetime import date

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.main import create_app
from tests.conftest import make_account, settings_for

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
    assert [item["sessionId"] for item in report["items"]] == [SESSION_2]
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
    assert len(other_report["items"]) == 2
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
