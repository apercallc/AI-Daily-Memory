"""Seeds one row in every user-derived table, so deletion tests can assert nothing survives."""

import uuid
from datetime import UTC, datetime, timedelta

from sqlalchemy import Engine, text

USER_TABLES = (
    "credentials",
    "login_failures",
    "access_tokens",
    "audio_uploads",
    "processing_jobs",
    "transcripts",
    "transcript_segments",
    "device_keys",
    "sealed_deliveries",
    "memory_items",
    "memory_item_evidence",
    "daily_reports",
    "memory_item_corrections",
    "usage_counters",
    "note_folders",
    "notes",
    "deleted_notes",
)


def seed_user_data(
    db: Engine,
    user_id: uuid.UUID,
    *,
    session_id: uuid.UUID | None = None,
    with_credential: bool = True,
) -> dict[str, uuid.UUID]:
    """One clip with its transcript, segment, sealed delivery, jobs, memory item (with
    evidence), report and device key, all for `session_id`. Returns the generated ids."""
    session_id = session_id or uuid.uuid4()
    ids = {name: uuid.uuid4() for name in ("upload", "transcript", "segment", "item")}
    ids["session"] = session_id
    now = datetime.now(UTC)
    with db.begin() as c:
        token_id = uuid.uuid4()
        c.execute(
            text(
                "INSERT INTO access_tokens (id, user_id, token_hash, kind, scopes, name) "
                "VALUES (:id, :u, :h, 'device', ARRAY['upload:write'], 'seeded')"
            ),
            {"id": token_id, "u": user_id, "h": uuid.uuid4().bytes * 2},
        )
        key_id = uuid.uuid4()
        c.execute(
            text(
                "INSERT INTO device_keys (id, user_id, token_id, public_key) "
                "VALUES (:id, :u, :t, :k)"
            ),
            {"id": key_id, "u": user_id, "t": token_id, "k": b"k" * 32},
        )
        c.execute(
            text(
                "INSERT INTO audio_uploads (id, user_id, session_id, segment_id, offset_ms, "
                "duration_ms, byte_size, sha256, codec, sample_rate_hz, channel_count, "
                "captured_at, state, object_key, device_key_id) VALUES (:id, :u, :s, :seg, 0, "
                "1000, 10, :sha, 'aac', 16000, 1, :now, 'transcribed', :key, :dk)"
            ),
            {
                "id": ids["upload"],
                "u": user_id,
                "s": session_id,
                "seg": uuid.uuid4(),
                "sha": "a" * 64,
                "now": now,
                "key": f"users/{user_id}/audio/{ids['upload']}.aac",
                "dk": key_id,
            },
        )
        c.execute(
            text(
                "INSERT INTO transcripts (id, user_id, upload_id, revision, provider, model) "
                "VALUES (:id, :u, :up, 1, 'p', 'm')"
            ),
            {"id": ids["transcript"], "u": user_id, "up": ids["upload"]},
        )
        c.execute(
            text(
                "INSERT INTO transcript_segments (id, user_id, transcript_id, start_ms, end_ms, "
                "text) VALUES (:id, :u, :t, 0, 10, 'hello')"
            ),
            {"id": ids["segment"], "u": user_id, "t": ids["transcript"]},
        )
        c.execute(
            text(
                "INSERT INTO sealed_deliveries (id, user_id, upload_id, device_key_id, "
                "ciphertext, expires_at) VALUES (:id, :u, :up, :dk, :ct, :exp)"
            ),
            {
                "id": uuid.uuid4(),
                "u": user_id,
                "up": ids["upload"],
                "dk": key_id,
                "ct": b"sealed",
                "exp": now + timedelta(days=7),
            },
        )
        for kind, subject in (("transcribe", ids["upload"]), ("extract", session_id)):
            c.execute(
                text(
                    "INSERT INTO processing_jobs (id, user_id, kind, idempotency_key, subject_id) "
                    "VALUES (:id, :u, :k, :key, :sub)"
                ),
                {
                    "id": uuid.uuid4(),
                    "u": user_id,
                    "k": kind,
                    "key": f"{kind}:{subject}:{user_id}",
                    "sub": subject,
                },
            )
        c.execute(
            text(
                "INSERT INTO memory_items (id, user_id, session_id, memory_day, kind, text, "
                "confidence, extraction_model, extraction_version) VALUES (:id, :u, :s, :d, "
                "'idea', 'x', 'likely', 'm', 1)"
            ),
            {"id": ids["item"], "u": user_id, "s": session_id, "d": now.date()},
        )
        c.execute(
            text(
                "INSERT INTO memory_item_evidence (id, user_id, memory_item_id, "
                "transcript_segment_id) VALUES (:id, :u, :i, :seg)"
            ),
            {"id": uuid.uuid4(), "u": user_id, "i": ids["item"], "seg": ids["segment"]},
        )
        c.execute(
            text(
                "INSERT INTO memory_item_corrections (id, user_id, memory_item_id, text) "
                "VALUES (:id, :u, :i, 'mine')"
            ),
            {"id": uuid.uuid4(), "u": user_id, "i": ids["item"]},
        )
        folder = uuid.uuid4()
        c.execute(
            text("INSERT INTO note_folders (id, user_id, name) VALUES (:id, :u, :n)"),
            {"id": folder, "u": user_id, "n": f"Seeded {folder.hex[:8]}"},
        )
        c.execute(
            text(
                "INSERT INTO notes (id, user_id, session_id, memory_day, auto_title, folder_id) "
                "VALUES (:id, :u, :s, :d, 'Seeded note', :f)"
            ),
            {"id": uuid.uuid4(), "u": user_id, "s": session_id, "d": now.date(), "f": folder},
        )
        c.execute(
            text("INSERT INTO deleted_notes (user_id, session_id) VALUES (:u, :s)"),
            {"u": user_id, "s": uuid.uuid4()},
        )
        c.execute(
            text(
                "INSERT INTO usage_counters (user_id, day, kind, count) VALUES (:u, :d, 'ask', 1) "
                "ON CONFLICT DO NOTHING"
            ),
            {"u": user_id, "d": now.date()},
        )
        c.execute(
            text(
                "INSERT INTO daily_reports (id, user_id, memory_day, status, report, "
                "extraction_version) VALUES (:id, :u, :d, 'complete', CAST(:r AS jsonb), 1) "
                "ON CONFLICT DO NOTHING"
            ),
            {"id": uuid.uuid4(), "u": user_id, "d": now.date(), "r": "{}"},
        )
        if with_credential:
            credential_id = uuid.uuid4()
            c.execute(
                text(
                    "INSERT INTO credentials (id, user_id, email, password_hash) "
                    "VALUES (:id, :u, :e, 'hash')"
                ),
                {"id": credential_id, "u": user_id, "e": f"{user_id}@example.test"},
            )
            c.execute(
                text(
                    "INSERT INTO login_failures (credential_id, source_hash, failed_attempts) "
                    "VALUES (:c, 'source', 2)"
                ),
                {"c": credential_id},
            )
    return ids


# login_failures hangs off credentials rather than carrying its own user_id.
_OWNER_FILTER = {
    "login_failures": "credential_id IN (SELECT id FROM credentials WHERE user_id = :u)",
}


def row_counts(db: Engine, user_id: uuid.UUID) -> dict[str, int]:
    with db.connect() as c:
        return {
            table: int(
                c.scalar(
                    text(
                        f"SELECT count(*) FROM {table} "  # noqa: S608
                        f"WHERE {_OWNER_FILTER.get(table, 'user_id = :u')}"
                    ),
                    {"u": user_id},
                )
                or 0
            )
            for table in USER_TABLES
        }
