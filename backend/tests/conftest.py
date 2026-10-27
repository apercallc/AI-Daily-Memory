"""Shared fixtures. Database tests run against a real PostgreSQL (the production engine): SQLite
would pass tests that Postgres fails (SKIP LOCKED, partial indexes, arrays, time zones).

Set TEST_DATABASE_URL to a throwaway database, e.g.
    postgresql+psycopg://aidm:aidm@localhost/aidm_test
Its `public` schema is dropped and rebuilt from the migrations at the start of the run.
"""

import os
import uuid
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from urllib.parse import quote

import pytest
from alembic import command
from alembic.config import Config
from pydantic import SecretStr
from sqlalchemy import Engine, create_engine, text

from app.auth import DEVICE_SCOPES, hash_token, new_token
from app.config import Settings
from app.storage import ObjectMissingError, PresignedPut, StoredObject

_TABLES = (
    "audit_events",
    "pending_account_object_deletions",
    "transcript_segments",
    "transcripts",
    "processing_jobs",
    "audio_uploads",
    "access_tokens",
    "users",
)


def _database_url() -> str:
    url = os.environ.get("TEST_DATABASE_URL")
    if not url:
        # Failing (not skipping) is deliberate: a skip would let CI go green without running
        # the database tests if the variable were ever dropped from the workflow.
        pytest.fail(
            "TEST_DATABASE_URL is not set. Database tests need a throwaway PostgreSQL 16: "
            "start one and run e.g. export "
            "TEST_DATABASE_URL=postgresql+psycopg://USER:PASSWORD@localhost:5432/aidm_test "
            "(its public schema is dropped and rebuilt). See backend/README.",
            pytrace=False,
        )
    return url


def run_migrations(url: str, revision: str = "head") -> None:
    config = Config("alembic.ini")
    config.attributes["url"] = url
    command.upgrade(config, revision)


def downgrade_migrations(url: str, revision: str = "base") -> None:
    config = Config("alembic.ini")
    config.attributes["url"] = url
    command.downgrade(config, revision)


def reset_schema(engine: Engine) -> None:
    with engine.begin() as connection:
        connection.execute(text("DROP SCHEMA public CASCADE"))
        connection.execute(text("CREATE SCHEMA public"))


@pytest.fixture(scope="session")
def database_url() -> str:
    return _database_url()


@pytest.fixture(scope="session")
def migrated(database_url: str) -> Iterator[Engine]:
    engine = create_engine(database_url)
    reset_schema(engine)
    run_migrations(database_url)
    yield engine
    engine.dispose()


@pytest.fixture
def db(migrated: Engine) -> Engine:
    """A migrated database with empty tables."""
    with migrated.begin() as connection:
        connection.execute(text(f"TRUNCATE {', '.join(_TABLES)} CASCADE"))
    return migrated


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@dataclass
class Account:
    user_id: uuid.UUID
    token: str

    @property
    def headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token}"}


def make_account(
    engine: Engine,
    *,
    scopes: tuple[str, ...] = DEVICE_SCOPES,
    revoked: bool = False,
    expires_at: datetime | None = None,
    last_used_at: datetime | None = None,
    storage_mode: str = "phone_only",
) -> Account:
    user_id = uuid.uuid4()
    token = new_token()
    with engine.begin() as connection:
        connection.execute(
            text("INSERT INTO users (id, storage_mode) VALUES (:id, :mode)"),
            {"id": user_id, "mode": storage_mode},
        )
        connection.execute(
            text(
                "INSERT INTO access_tokens (id, user_id, token_hash, kind, scopes, name, "
                "revoked_at, expires_at, last_used_at) VALUES (:id, :user, :hash, 'device', "
                ":scopes, 'test phone', :revoked, :expires, :used)",
            ),
            {
                "id": uuid.uuid4(),
                "user": user_id,
                "hash": hash_token(token),
                "scopes": list(scopes),
                "revoked": datetime.now(UTC) - timedelta(minutes=1) if revoked else None,
                "expires": expires_at,
                "used": last_used_at,
            },
        )
    return Account(user_id, token)


def settings_for(database_url: str, **overrides: object) -> Settings:
    return Settings(
        app_env="test",
        database_url=SecretStr(database_url),
        **overrides,  # type: ignore[arg-type]
    )


@dataclass
class FakeObjectStore:
    """In-memory stand-in for S3: records presigns, holds uploaded bytes."""

    objects: dict[str, tuple[bytes, str | None]] = field(default_factory=dict)
    deleted: list[str] = field(default_factory=list)
    report_checksum: bool = True

    def put(self, key: str, data: bytes, sha256: str | None = None) -> None:
        import hashlib

        self.objects[key] = (data, sha256 or hashlib.sha256(data).hexdigest())

    def presign_put(
        self,
        key: str,
        *,
        byte_size: int,
        sha256: str,
        expires_in: int,
    ) -> PresignedPut:
        return PresignedPut(
            url=f"https://store.test/{key}?sig=fake",
            headers={"Content-Length": str(byte_size), "x-amz-checksum-sha256": sha256},
            expires_at=datetime.now(UTC) + timedelta(seconds=expires_in),
        )

    def presign_get(self, key: str, *, expires_in: int, filename: str) -> str:
        return f"https://store.test/{key}?sig=fake&filename={quote(filename)}"

    async def write(self, key: str, data: bytes, *, content_type: str) -> None:
        self.put(key, data)

    async def stat(self, key: str) -> StoredObject | None:
        if key not in self.objects:
            return None
        data, sha = self.objects[key]
        return StoredObject(size=len(data), sha256=sha if self.report_checksum else None)

    async def read(self, key: str) -> bytes:
        if key not in self.objects:
            raise ObjectMissingError(key)
        return self.objects[key][0]

    async def delete(self, key: str) -> None:
        self.deleted.append(key)
        self.objects.pop(key, None)

    async def delete_prefix(self, prefix: str) -> int:
        keys = [k for k in self.objects if k.startswith(prefix)]
        for key in keys:
            await self.delete(key)
        return len(keys)
