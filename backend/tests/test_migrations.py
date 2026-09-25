"""Migrations are the schema's source of truth; the models must match them exactly, and every
migration must be reversible (TODO quality bar: forward + rollback)."""

from alembic.autogenerate import compare_metadata
from alembic.runtime.migration import MigrationContext
from sqlalchemy import Engine, create_engine, inspect

from app.db.models import Base
from tests.conftest import downgrade_migrations, reset_schema, run_migrations


def test_models_match_the_migrated_schema(migrated: Engine) -> None:
    with migrated.connect() as connection:
        diff = compare_metadata(MigrationContext.configure(connection), Base.metadata)

    assert diff == []


def test_migrations_downgrade_to_empty_and_upgrade_again(database_url: str) -> None:
    engine = create_engine(database_url)
    try:
        reset_schema(engine)
        run_migrations(database_url)
        downgrade_migrations(database_url)
        tables = set(inspect(engine).get_table_names()) - {"alembic_version"}
        assert tables == set()
        run_migrations(database_url)
        assert "audio_uploads" in inspect(engine).get_table_names()
    finally:
        engine.dispose()


def test_every_user_owned_table_cascades_from_users(migrated: Engine) -> None:
    inspector = inspect(migrated)
    for table in Base.metadata.sorted_tables:
        if table.name == "users":
            continue
        assert "user_id" in table.columns, table.name
        user_fks = [
            fk for fk in inspector.get_foreign_keys(table.name) if fk["referred_table"] == "users"
        ]
        assert user_fks, table.name
        assert all(fk["options"].get("ondelete") == "CASCADE" for fk in user_fks), table.name
