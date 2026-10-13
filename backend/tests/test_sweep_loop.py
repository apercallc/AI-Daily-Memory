"""The in-process retention sweep (app.main.retention_sweep_loop): it runs when configured,
stops cleanly at shutdown, and survives individual sweep failures."""

import asyncio
import time
from types import SimpleNamespace
from typing import cast

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine

import app.main as main_module
from app.db import Database
from app.main import create_app, retention_sweep_loop
from app.storage import ObjectStore
from tests.conftest import FakeObjectStore, settings_for

pytestmark = pytest.mark.anyio


async def test_sweep_loop_sweeps_once_then_stops(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[int] = []
    stop = asyncio.Event()

    async def fake_sweep(
        sessions: object, store: object, *, url_ttl_seconds: int, now: object = None
    ) -> None:
        calls.append(url_ttl_seconds)
        stop.set()

    monkeypatch.setattr(main_module, "sweep", fake_sweep)
    database = SimpleNamespace(sessions=None)
    await retention_sweep_loop(
        cast(Database, database),
        cast(ObjectStore, object()),
        interval_seconds=60.0,
        url_ttl_seconds=900,
        stop=stop,
    )

    assert calls == [900]


async def test_sweep_loop_survives_a_failing_sweep(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[int] = []
    stop = asyncio.Event()

    async def flaky_sweep(
        sessions: object, store: object, *, url_ttl_seconds: int, now: object = None
    ) -> None:
        calls.append(url_ttl_seconds)
        if len(calls) == 1:
            raise RuntimeError("store unavailable")
        stop.set()

    monkeypatch.setattr(main_module, "sweep", flaky_sweep)
    database = SimpleNamespace(sessions=None)
    await retention_sweep_loop(
        cast(Database, database),
        cast(ObjectStore, object()),
        interval_seconds=0.05,
        url_ttl_seconds=900,
        stop=stop,
    )

    assert calls == [900, 900]


async def test_sweep_loop_reports_a_failing_sweep_to_sentry(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: list[BaseException] = []
    stop = asyncio.Event()

    async def failing_sweep(
        sessions: object, store: object, *, url_ttl_seconds: int, now: object = None
    ) -> None:
        stop.set()
        raise RuntimeError("store unavailable")

    monkeypatch.setattr(main_module, "sweep", failing_sweep)
    monkeypatch.setattr(main_module, "capture_unhandled_error", captured.append)
    await retention_sweep_loop(
        cast(Database, SimpleNamespace(sessions=None)),
        cast(ObjectStore, object()),
        interval_seconds=60.0,
        url_ttl_seconds=900,
        stop=stop,
    )

    assert [str(e) for e in captured] == ["store unavailable"]


def wait_for_sweep(calls: list[int], seconds: float = 5.0) -> None:
    deadline = time.monotonic() + seconds
    while not calls and time.monotonic() < deadline:
        time.sleep(0.02)


def test_app_sweeps_on_startup_when_configured(
    db: Engine,
    database_url: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[int] = []

    async def fake_sweep(
        sessions: object, store: object, *, url_ttl_seconds: int, now: object = None
    ) -> None:
        calls.append(url_ttl_seconds)

    monkeypatch.setattr(main_module, "sweep", fake_sweep)
    settings = settings_for(database_url, retention_sweep_interval_seconds=60.0)
    application = create_app(settings, object_store=FakeObjectStore())

    with TestClient(application) as client:
        assert client.get("/v1/health").status_code == 200
        wait_for_sweep(calls)

    assert calls == [900]


def test_app_does_not_sweep_without_the_setting(
    db: Engine,
    database_url: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[int] = []

    async def fake_sweep(
        sessions: object, store: object, *, url_ttl_seconds: int, now: object = None
    ) -> None:
        calls.append(url_ttl_seconds)

    monkeypatch.setattr(main_module, "sweep", fake_sweep)
    settings = settings_for(database_url)
    application = create_app(settings, object_store=FakeObjectStore())

    with TestClient(application) as client:
        assert client.get("/v1/health").status_code == 200
        time.sleep(0.2)

    assert calls == []
