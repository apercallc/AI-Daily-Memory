"""Settings parsing: managed-platform database URLs map onto the installed driver."""

from pydantic import SecretStr

from app.config import Settings


def test_railway_style_postgres_urls_map_to_the_psycopg_dialect() -> None:
    for raw in ("postgres://u:p@h:5432/db", "postgresql://u:p@h:5432/db"):
        settings = Settings.model_validate({"database_url": raw})
        assert settings.database_url == SecretStr("postgresql+psycopg://u:p@h:5432/db")


def test_explicit_dialect_urls_pass_through_unchanged() -> None:
    raw = "postgresql+psycopg://u:p@h:5432/db"
    assert Settings.model_validate({"database_url": raw}).database_url == SecretStr(raw)


def test_unset_database_url_stays_none() -> None:
    assert Settings(app_env="test").database_url is None
