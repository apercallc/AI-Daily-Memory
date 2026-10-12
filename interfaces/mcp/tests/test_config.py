"""Settings must come from real environment variables only (app/config.py): an MCP server's
working directory is whatever the launching client set, so a stray .env there must never
silently override the client's own environment configuration."""

from pathlib import Path

import pytest
from pydantic import ValidationError

from app.config import Settings


def test_a_dotenv_file_in_the_working_directory_is_not_read(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / ".env").write_text(
        "ADM_API_KEY=adm_from_dotenv\nADM_BASE_URL=https://from-dotenv\n"
    )
    monkeypatch.delenv("ADM_API_KEY", raising=False)
    monkeypatch.delenv("ADM_BASE_URL", raising=False)

    with pytest.raises(ValidationError):
        Settings()  # type: ignore[call-arg]


def test_settings_come_from_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ADM_API_KEY", "adm_from_env")
    monkeypatch.setenv("ADM_BASE_URL", "https://api.example")

    settings = Settings()  # type: ignore[call-arg]

    assert settings.api_key.get_secret_value() == "adm_from_env"
    assert settings.base_url == "https://api.example"
