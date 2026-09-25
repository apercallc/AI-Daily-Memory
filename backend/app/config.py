from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Process configuration. Values come from the environment only; see `.env.example`."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore", frozen=True)

    app_env: Literal["development", "test", "staging", "production"] = "development"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    # JSON API bodies only. Audio goes directly to object storage via signed URLs, so a large
    # body here is a bug or abuse, not a use case.
    max_request_body_bytes: int = Field(default=1_048_576, gt=0, le=16_777_216)
    # Whole-request budget; a slow database or store must not pin workers indefinitely.
    request_timeout_seconds: float = Field(default=30.0, gt=0, le=300)

    # postgresql+psycopg://user:password@host:5432/db. Unset means no database (health only).
    database_url: SecretStr | None = None

    # S3-compatible object storage for temporary audio (R2 in production, MinIO locally).
    s3_endpoint_url: str | None = None
    s3_region: str = "auto"
    s3_bucket: str | None = None
    s3_access_key_id: SecretStr | None = None
    s3_secret_access_key: SecretStr | None = None
    upload_url_ttl_seconds: int = Field(default=900, ge=60, le=3_600)

    # Upload limits; plan-based entitlements replace the daily quota in Phase 10.
    max_upload_bytes: int = Field(default=20 * 1_048_576, gt=0)
    max_upload_duration_ms: int = Field(default=30 * 60_000, gt=0)
    daily_upload_bytes_quota: int = Field(default=512 * 1_048_576, gt=0)

    # Gate B: no audio reaches a provider until one is chosen and reviewed (TODO Phase 1).
    transcription_provider: Literal["disabled"] = "disabled"

    @property
    def expose_api_docs(self) -> bool:
        # Interactive docs describe the private API surface; keep them off in deployed envs.
        return self.app_env in ("development", "test")


@lru_cache
def get_settings() -> Settings:
    return Settings()
