import json
import uuid
from collections.abc import Iterator
from datetime import UTC, date, datetime

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.main import create_app
from app.reports.daily import Coverage, DailyReport
from tests.conftest import make_account, settings_for

pytestmark = pytest.mark.anyio


@pytest.fixture
def client(db: Engine, database_url: str) -> Iterator[TestClient]:
    with TestClient(create_app(settings_for(database_url))) as c:
        yield c


def valid_report(memory_day: date) -> dict[str, object]:
    return DailyReport(
        memory_day=memory_day,
        timezone="UTC",
        generated_at=datetime(2026, 9, 25, 12, tzinfo=UTC),
        status="complete",
        coverage=Coverage(recorded_minutes=1),
    ).model_dump(mode="json")


def insert_report(
    db: Engine, user_id: uuid.UUID, memory_day: date, report: dict[str, object]
) -> None:
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO daily_reports (id, user_id, memory_day, status, report, "
                "extraction_version, generated_at) VALUES (:id, :user, :day, :status, "
                "CAST(:report AS jsonb), 1, :now)",
            ),
            {
                "id": uuid.uuid4(),
                "user": user_id,
                "day": memory_day,
                "status": report["status"],
                "report": json.dumps(report),
                "now": datetime.now(UTC),
            },
        )


def test_a_report_that_exists_is_returned(db: Engine, client: TestClient) -> None:
    account = make_account(db, storage_mode="full_cloud")
    memory_day = date(2026, 9, 25)
    report = valid_report(memory_day)
    insert_report(db, account.user_id, memory_day, report)

    response = client.get(
        f"/v1/reports/{memory_day.isoformat()}",
        headers={"Authorization": f"Bearer {account.token}"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "complete"


def test_a_missing_report_is_404(db: Engine, client: TestClient) -> None:
    account = make_account(db, storage_mode="full_cloud")

    response = client.get(
        "/v1/reports/2026-01-01",
        headers={"Authorization": f"Bearer {account.token}"},
    )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "report_not_found"


def test_reports_require_authentication(client: TestClient) -> None:
    response = client.get("/v1/reports/2026-01-01")
    assert response.status_code == 401


@pytest.mark.parametrize("storage_mode", ["phone_only", "reports_cloud"])
def test_synced_reports_remain_accessible_after_optional_phone_sync(
    db: Engine, client: TestClient, storage_mode: str
) -> None:
    account = make_account(db, storage_mode=storage_mode)
    memory_day = date(2026, 9, 25)
    report = valid_report(memory_day)
    insert_report(db, account.user_id, memory_day, report)

    response = client.get(
        f"/v1/reports/{memory_day.isoformat()}",
        headers={"Authorization": f"Bearer {account.token}"},
    )

    assert response.status_code == 200
    assert response.json()["status"] == "complete"


def test_a_report_never_leaks_across_accounts(db: Engine, client: TestClient) -> None:
    owner = make_account(db, storage_mode="full_cloud")
    other = make_account(db, storage_mode="full_cloud")
    memory_day = date(2026, 9, 25)
    insert_report(db, owner.user_id, memory_day, valid_report(memory_day))

    response = client.get(
        f"/v1/reports/{memory_day.isoformat()}",
        headers={"Authorization": f"Bearer {other.token}"},
    )

    assert response.status_code == 404


def phone_document(memory_day: date, items: list[object]) -> dict[str, object]:
    return {
        "memoryDay": memory_day.isoformat(),
        "zoneId": "UTC",
        "headline": "A day",
        "summary": "Decided to ship the beta.",
        "items": items,
        "coverage": {"recordedMs": 60_000},
        "generatedAtEpochMs": 1_791_000_000_000,
    }


def insert_phone_document(
    db: Engine, user_id: uuid.UUID, memory_day: date, document: dict[str, object]
) -> None:
    with db.begin() as connection:
        connection.execute(
            text(
                "INSERT INTO daily_reports (id, user_id, memory_day, status, report, "
                "extraction_version) VALUES (:id, :user, :day, 'complete', "
                "CAST(:report AS jsonb), 1)",
            ),
            {
                "id": uuid.uuid4(),
                "user": user_id,
                "day": memory_day,
                "report": json.dumps(document),
            },
        )


def test_a_malformed_phone_document_item_is_skipped_not_a_500(
    db: Engine, client: TestClient
) -> None:
    """A stored phone-document item from a different contract version (a renamed or missing
    key) must degrade gracefully: the malformed item is skipped and every well-formed item
    still comes back, instead of an unhandled KeyError turning into a 500 for the whole day."""
    account = make_account(db, storage_mode="phone_only")
    memory_day = date(2026, 9, 25)
    good_session = uuid.uuid4()
    document = phone_document(
        memory_day,
        items=[
            {
                "kind": "decision",
                "text": "Ship the beta on Friday",
                "confidence": "likely",
                "sessionId": str(good_session),
            },
            # A future/past contract version renamed or dropped "text".
            {"kind": "decision", "confidence": "likely", "sessionId": str(uuid.uuid4())},
            # An unknown/future item kind.
            {"kind": "future_kind", "text": "whatever", "sessionId": str(uuid.uuid4())},
            # Not even a dict.
            "not-an-item",
        ],
    )
    insert_phone_document(db, account.user_id, memory_day, document)

    response = client.get(
        f"/v1/reports/{memory_day.isoformat()}",
        headers={"Authorization": f"Bearer {account.token}"},
    )

    assert response.status_code == 200
    body = response.json()
    assert [d["text"] for d in body["decisions"]] == ["Ship the beta on Friday"]
