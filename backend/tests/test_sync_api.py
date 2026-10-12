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
