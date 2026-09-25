import json
import uuid
from collections.abc import Iterator
from datetime import UTC, date, datetime

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine, text

from app.main import create_app
from tests.conftest import make_account, settings_for

pytestmark = pytest.mark.anyio


@pytest.fixture
def client(db: Engine, database_url: str) -> Iterator[TestClient]:
    with TestClient(create_app(settings_for(database_url))) as c:
        yield c


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
    report = {"schema_version": 1, "memory_day": "2026-09-25", "status": "complete"}
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
def test_reports_require_full_cloud_storage_mode(
    db: Engine, client: TestClient, storage_mode: str
) -> None:
    account = make_account(db, storage_mode=storage_mode)
    memory_day = date(2026, 9, 25)
    with db.begin() as connection:
        connection.execute(
            text("UPDATE users SET storage_mode = 'full_cloud' WHERE id = :id"),
            {"id": account.user_id},
        )
        # Report data could only ever exist for a full_cloud account, so simulate one lingering
        # after a later switch away from full_cloud — it must still be refused, not returned.
        insert_report(db, account.user_id, memory_day, {"status": "complete"})
        connection.execute(
            text("UPDATE users SET storage_mode = :m WHERE id = :id"),
            {"m": storage_mode, "id": account.user_id},
        )

    response = client.get(
        f"/v1/reports/{memory_day.isoformat()}",
        headers={"Authorization": f"Bearer {account.token}"},
    )

    assert response.status_code == 403
    assert response.json()["error"]["code"] == "cloud_storage_required"


def test_a_report_never_leaks_across_accounts(db: Engine, client: TestClient) -> None:
    owner = make_account(db, storage_mode="full_cloud")
    other = make_account(db, storage_mode="full_cloud")
    memory_day = date(2026, 9, 25)
    insert_report(db, owner.user_id, memory_day, {"status": "complete"})

    response = client.get(
        f"/v1/reports/{memory_day.isoformat()}",
        headers={"Authorization": f"Bearer {other.token}"},
    )

    assert response.status_code == 404
