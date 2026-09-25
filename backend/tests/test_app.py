from typing import Literal

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.config import Settings
from app.main import create_app

AppEnv = Literal["development", "test", "staging", "production"]


@pytest.mark.parametrize("env", ["development", "test"])
def test_api_docs_exposed_only_in_local_environments(env: AppEnv) -> None:
    client = TestClient(create_app(Settings(app_env=env)))

    assert client.get("/docs").status_code == 200
    assert client.get("/openapi.json").status_code == 200


@pytest.mark.parametrize("env", ["staging", "production"])
def test_api_docs_hidden_in_deployed_environments(env: AppEnv) -> None:
    client = TestClient(create_app(Settings(app_env=env)))

    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404
    assert client.get("/v1/health").status_code == 200


def test_unknown_environment_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("APP_ENV", "prod")

    with pytest.raises(ValidationError):
        Settings()
