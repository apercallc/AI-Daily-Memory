from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

MAX_UPLOAD_URL_TTL_SECONDS = 3_600


class Settings(BaseSettings):
    """Process configuration. Values come from the environment only; see `.env.example`."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore", frozen=True)

    app_env: Literal["development", "test", "staging", "production"] = "development"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    # Railway public HTTP ingress overwrites X-Real-IP with the connected client address.
    trust_railway_x_real_ip: bool = False
    # JSON API bodies only. Audio goes directly to object storage via signed URLs, so a large
    # body here is a bug or abuse, not a use case.
    max_request_body_bytes: int = Field(default=1_048_576, gt=0, le=16_777_216)
    # Whole-request budget; a slow database or store must not pin workers indefinitely.
    request_timeout_seconds: float = Field(default=30.0, gt=0, le=300)

    # Optional error reporting. Event payloads are scrubbed before leaving this process.
    sentry_dsn: SecretStr | None = None

    # postgresql+psycopg://user:password@host:5432/db. Unset means no database (health only).
    database_url: SecretStr | None = None

    # Key for the HMAC that turns a client address into the opaque per-source login-lockout key
    # (app.credentials). Unset falls back to the database URL secret, which every deploy already
    # has; set it to rotate independently. Rotating only resets in-flight lockout counters.
    login_source_key: SecretStr | None = None

    # S3-compatible object storage for temporary audio (R2 in production, MinIO locally).
    s3_endpoint_url: str | None = None
    s3_region: str = "auto"
    s3_bucket: str | None = None
    s3_access_key_id: SecretStr | None = None
    s3_secret_access_key: SecretStr | None = None
    upload_url_ttl_seconds: int = Field(
        default=900,
        ge=60,
        le=MAX_UPLOAD_URL_TTL_SECONDS,
    )

    # Upload limits; plan-based entitlements replace the daily quota in Phase 10.
    max_upload_bytes: int = Field(default=20 * 1_048_576, gt=0)
    max_upload_duration_ms: int = Field(default=30 * 60_000, gt=0)
    daily_upload_bytes_quota: int = Field(default=512 * 1_048_576, gt=0)

    # Retention sweep (ADR-0003): set to run the sweep inside the API process when no
    # separate worker is deployed. None (unset) keeps sweeping out of this process.
    retention_sweep_interval_seconds: float | None = Field(default=None, ge=60.0, le=86_400.0)

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

    # Ask (recall) answers. "disabled" lists the matching memory items without any third party
    # seeing them; "claude" lets a model phrase the answer from those items only. Turning it on
    # sends a person's memory text to the provider, so it is an operator decision, like the
    # providers above.
    ask_provider: Literal["disabled", "claude"] = "disabled"
    ask_model: str = "claude-sonnet-5"
    # Ask calls per person per UTC day; plan-based entitlements replace this in Phase 10.
    daily_ask_limit: int = Field(default=50, ge=1, le=10_000)

    # Email (password reset, verification). Disabled until an operator configures a provider;
    # the web app then hides the flows rather than promising mail that cannot be sent.
    mail_provider: Literal["disabled", "resend"] = "disabled"
    resend_api_key: SecretStr | None = None
    mail_from: str = "AI Daily Memory <no-reply@aidailymem.apercallc.com>"
    # Where links in emails point (the web app's origin).
    public_base_url: str = "https://aidailymem.apercallc.com"

    # Billing (Stripe Checkout for web subscriptions, ADR-0025). Disabled until configured.
    stripe_secret_key: SecretStr | None = None
    stripe_webhook_secret: SecretStr | None = None
    stripe_price_id: str | None = None
    # Plan limits per person per UTC day. "free" keeps `daily_ask_limit` and the upload quota.
    pro_daily_ask_limit: int = Field(default=500, ge=1, le=100_000)
    pro_upload_quota_multiplier: int = Field(default=4, ge=1, le=100)

    @property
    def billing_enabled(self) -> bool:
        return bool(self.stripe_secret_key and self.stripe_webhook_secret and self.stripe_price_id)

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
