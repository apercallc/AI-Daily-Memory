from collections.abc import Iterator

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
from pydantic import BaseModel

from app.config import Settings
from app.errors import error_code
from app.main import create_app


class Note(BaseModel):
    title: str
    minutes: int


@pytest.fixture
def client() -> Iterator[TestClient]:
    application: FastAPI = create_app(Settings(app_env="test"))

    @application.post("/notes")
    async def create_note(note: Note) -> Note:
        return note

    @application.get("/private")
    async def private() -> None:
        raise HTTPException(401, "Not authenticated", headers={"WWW-Authenticate": "Bearer"})

    with TestClient(application) as test_client:
        yield test_client


def test_not_found_uses_the_envelope(client: TestClient) -> None:
    response = client.get("/nope")

    assert response.status_code == 404
    error = response.json()["error"]
    assert error["code"] == "not_found"
    assert error["request_id"] == response.headers["x-request-id"]


def test_method_not_allowed_keeps_the_allow_header(client: TestClient) -> None:
    response = client.delete("/v1/health")

    assert response.status_code == 405
    assert response.json()["error"]["code"] == "method_not_allowed"
    assert response.headers["allow"] == "GET"


def test_http_exception_keeps_auth_challenge_header(client: TestClient) -> None:
    response = client.get("/private")

    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json()["error"]["message"] == "Not authenticated"


def test_validation_errors_locate_fields_without_echoing_input(client: TestClient) -> None:
    private_speech = "I told Sam the merger closes Friday"
    response = client.post("/notes", json={"title": private_speech, "minutes": private_speech})

    assert response.status_code == 422
    error = response.json()["error"]
    assert error["code"] == "validation_error"
    assert error["fields"] == [{"location": ["body", "minutes"], "type": "int_parsing"}]
    assert private_speech not in response.text


def test_malformed_json_is_a_validation_error(client: TestClient) -> None:
    response = client.post(
        "/notes", content=b"{not json", headers={"content-type": "application/json"}
    )

    assert response.status_code == 422
    assert response.json()["error"]["fields"][0]["type"] == "json_invalid"


@pytest.mark.parametrize(
    ("status", "code"),
    [
        (404, "not_found"),
        (413, "payload_too_large"),
        (422, "unprocessable_entity"),
        (599, "http_599"),
    ],
)
def test_error_codes_are_stable_snake_case(status: int, code: str) -> None:
    assert error_code(status) == code
