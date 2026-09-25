from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Process configuration. Values come from the environment only; see `.env.example`."""

    model_config = SettingsConfigDict(env_file=".env", extra="ignore", frozen=True)

    app_env: Literal["development", "test", "staging", "production"] = "development"
    log_level: Literal["DEBUG", "INFO", "WARNING", "ERROR"] = "INFO"

    @property
    def expose_api_docs(self) -> bool:
        # Interactive docs describe the private API surface; keep them off in deployed envs.
        return self.app_env in ("development", "test")


@lru_cache
def get_settings() -> Settings:
    return Settings()
