from functools import lru_cache
from typing import Literal

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Process configuration. Values come from the environment only; see `.env.example`."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore", frozen=True)

    app_env: Literal["development", "test", "staging", "production"] = "development"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"
    # JSON API bodies only. Audio goes directly to object storage via signed URLs, so a large
    # body here is a bug or abuse, not a use case.
    max_request_body_bytes: int = Field(default=1_048_576, gt=0, le=16_777_216)

    @property
    def expose_api_docs(self) -> bool:
        # Interactive docs describe the private API surface; keep them off in deployed envs.
        return self.app_env in ("development", "test")


@lru_cache
def get_settings() -> Settings:
    return Settings()
