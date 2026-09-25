"""Alembic environment. The URL comes from the environment (Settings), never from a file."""

from alembic import context
from sqlalchemy import create_engine, pool

from app.config import Settings
from app.db.models import Base

config = context.config


def _url() -> str:
    override = config.attributes.get("url")
    if isinstance(override, str):
        return override
    settings = Settings()
    if settings.database_url is None:
        raise RuntimeError("DATABASE_URL is not set")
    return settings.database_url.get_secret_value()


def run_migrations_online() -> None:
    engine = create_engine(_url(), poolclass=pool.NullPool)
    with engine.connect() as connection:
        context.configure(connection=connection, target_metadata=Base.metadata)
        with context.begin_transaction():
            context.run_migrations()
    engine.dispose()


run_migrations_online()
