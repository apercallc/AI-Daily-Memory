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
    account = make_account(db)
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
    account = make_account(db)

    response = client.get(
        "/v1/reports/2026-01-01",
        headers={"Authorization": f"Bearer {account.token}"},
    )

    assert response.status_code == 404
    assert response.json()["error"]["code"] == "report_not_found"


def test_reports_require_authentication(client: TestClient) -> None:
    response = client.get("/v1/reports/2026-01-01")
    assert response.status_code == 401


def test_a_report_never_leaks_across_accounts(db: Engine, client: TestClient) -> None:
    owner = make_account(db)
    other = make_account(db)
    memory_day = date(2026, 9, 25)
    insert_report(db, owner.user_id, memory_day, {"status": "complete"})

    response = client.get(
        f"/v1/reports/{memory_day.isoformat()}",
        headers={"Authorization": f"Bearer {other.token}"},
    )

    assert response.status_code == 404
