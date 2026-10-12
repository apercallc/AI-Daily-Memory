"""Configuration for the MCP server. Values come from real environment variables only —
no .env file is read, because an MCP server's working directory is whatever the launching
client set it to; a stray .env there would silently override the client's own env config.
A client's MCP launcher config (env vars in its own settings) is the only place a secret
needs to live.
"""

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ADM_", extra="ignore")

    # The backend to call. https:// only in practice; loopback exists for local development
    # against the backend's own dev server.
    base_url: str = Field(default="https://api.example.invalid")
    # The calling user's OWN token (TODO.md "MCP transport auth: authenticate the calling user,
    # never a shared service key"). One MCP server process serves exactly one account.
    api_key: SecretStr
    request_timeout_seconds: float = Field(default=30.0, gt=0, le=120)
