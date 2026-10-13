"""Migrations are the schema's source of truth; the models must match them exactly, and every
migration must be reversible (TODO quality bar: forward + rollback)."""

import re

from alembic.autogenerate import compare_metadata
from alembic.runtime.migration import MigrationContext
from sqlalchemy import CheckConstraint, Engine, create_engine, inspect

from app.db.models import Base
from tests.conftest import downgrade_migrations, reset_schema, run_migrations


def test_models_match_the_migrated_schema(migrated: Engine) -> None:
    with migrated.connect() as connection:
        context = MigrationContext.configure(
            connection,
            opts={"compare_server_default": True, "compare_type": True},
        )
        diff = compare_metadata(context, Base.metadata)

    assert diff == []


def test_check_constraints_match_the_models(migrated: Engine) -> None:
    """compare_metadata ignores CHECK constraints: a changed state list without a migration
    would pass it and then fail inserts in production."""
    inspector = inspect(migrated)
    for table in Base.metadata.sorted_tables:
        expected = {
            str(c.name): " ".join(str(c.sqltext).split())
            for c in table.constraints
            if isinstance(c, CheckConstraint)
        }
        actual = {str(c["name"]): c["sqltext"] for c in inspector.get_check_constraints(table.name)}
        assert set(actual) == set(expected), table.name
        for name, sql in expected.items():
            normalized = actual[name]
            for literal in re.findall(r"'([^']*)'", sql):
                assert f"'{literal}'" in normalized, (table.name, name, literal)


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
        if table.name in {"users", "pending_account_object_deletions"}:
            # This outbox must outlive the user row so delayed storage cleanup can finish.
            continue
        assert "user_id" in table.columns, table.name
        user_fks = [
            fk for fk in inspector.get_foreign_keys(table.name) if fk["referred_table"] == "users"
        ]
        assert user_fks, table.name
        assert all(fk["options"].get("ondelete") == "CASCADE" for fk in user_fks), table.name


def test_query_path_indexes_exist(migrated: Engine) -> None:
    inspector = inspect(migrated)
    expected = {
        "sealed_deliveries": ["expires_at"],
        "processing_jobs": ["subject_id"],
        "audio_uploads": ["user_id", "session_id"],
        "memory_items": ["user_id", "session_id"],
    }
    for table, columns in expected.items():
        indexed = [ix["column_names"] for ix in inspector.get_indexes(table)]
        assert columns in indexed, (table, columns)
    assert ["user_id", "captured_at"] in [
        ix["column_names"] for ix in inspector.get_indexes("audio_uploads")
    ]
