from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, field_validator
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

    # Gate B (ADR-0018): the integration exists, but stays "disabled" until an operator turns
    # it on deliberately — the cost model, provider-terms, and consent reviews TODO.md lists
    # are a human decision this setting does not make for them.
    transcription_provider: Literal["disabled", "deepgram"] = "disabled"
    deepgram_api_key: SecretStr | None = None
    deepgram_model: str = "nova-2"

    # Phase 2 structured extraction (ADR-0019): same posture as transcription_provider — the
    # integration exists, but no gold-dataset evaluation exists yet (TODO.md "AI evaluation"),
    # so this stays "disabled" until an operator turns it on deliberately.
    extraction_provider: Literal["disabled", "claude"] = "disabled"
    claude_api_key: SecretStr | None = None
    claude_model: str = "claude-sonnet-5"

    @field_validator("database_url", mode="before")
    @classmethod
    def _normalize_database_url(cls, value: object) -> object:
        # Managed platforms (Railway, Heroku) inject "postgres://" or "postgresql://"
        # URLs. psycopg3 is the only Postgres driver installed (ADR-0009), so map the
        # bare schemes onto its dialect instead of failing at connect time.
        if isinstance(value, str) and value.startswith(("postgres://", "postgresql://")):
            return "postgresql+psycopg://" + value.split("://", 1)[1]
        return value

    @property
    def expose_api_docs(self) -> bool:
        # Interactive docs describe the private API surface; keep them off in deployed envs.
        return self.app_env in ("development", "test")


@lru_cache
def get_settings() -> Settings:
    return Settings()
