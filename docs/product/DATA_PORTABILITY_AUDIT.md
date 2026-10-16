# Data-portability and account-deletion completeness audit

TODO.md Phase 10 asks to "verify export completeness against the data inventory and account
deletion across derived data, embeddings, jobs, and retained objects" (SECURITY.md). This is
that verification pass, done by reading the real schema and the real endpoint code rather than
re-describing what was already claimed elsewhere.

## Method

1. Listed every table in `backend/app/db/models.py` (21 `__tablename__`s) and matched it against
   `docs/product/DATA_INVENTORY.md`'s table.
2. For each table, read its actual foreign key to confirm account deletion really cascades to
   it (not just that a doc claims it does).
3. For each table marked "exported" in the inventory, read `app/api/export.py`'s field list and
   confirmed the fields it yields actually match what the inventory claims is exported.

## Account deletion: every table, confirmed

`DELETE /v1/me` (`app/api/account.py::delete_me`) does `delete(User)...` and relies on `ON
DELETE CASCADE`. Every one of the 21 tables was checked directly in `models.py`:

- Eighteen tables use the shared `_user_fk()` helper (`ForeignKey("users.id",
  ondelete="CASCADE")`) directly: `credentials`, `access_tokens`, `audio_uploads`,
  `processing_jobs`, `transcripts`, `transcript_segments`, `device_keys`, `sealed_deliveries`,
  `memory_items`, `memory_item_evidence`, `daily_reports`, `memory_item_corrections`,
  `usage_counters`, `email_tokens`, `note_folders`, `notes`, `deleted_notes`,
  `account_exports`. One cascading `DELETE` removes every row in all eighteen.
- `login_failures` cascades two hops: `credential_id -> credentials.id -> users.id`, both
  `ondelete="CASCADE"` — matches its own docstring ("Rows die with their credential").
- `pending_account_object_deletions` (`DeletedAccountObjectPurge`) deliberately has **no**
  foreign key on `account_id` at all — by design, per its own docstring ("a minimal deletion
  tombstone that outlives the user's cascading database delete"). This is the one table that
  must *not* cascade, so its own worker can still find the account id after the user row is
  gone and finish purging any object storage the user's presigned URLs wrote after the main
  `delete_prefix` call. Confirmed intentional, not a gap.
- No embeddings table exists (Gate E — approximate vector indexes — has not been crossed; there
  is nothing to check there yet).
- `processing_jobs` (the job queue) was **missing from `DATA_INVENTORY.md`'s table entirely**
  before this pass — a real documentation completeness gap, now fixed (added as Internal,
  no export, same treatment as `usage_counters`).

Object storage itself: `delete_prefix(user_prefix(user_id))` runs before the database cascade
(if it fails, the whole delete fails closed — `account.py` raises 503 and the account row is
kept so the operation is retryable). `pending_account_object_deletions` catches the one case
`delete_prefix` cannot: an object written through a presigned URL issued before the delete but
landing in storage after it. See `docs/security/OBJECT_STORAGE_REVIEW.md` for that control in
full.

## Export completeness: every "Yes" in the inventory, checked against the real field list

Read `app/api/export.py`'s `_items`/`_folders`/`_notes`/`_reports`/`_transcripts`/`_clips`/
`_tokens` generators field-by-field:

- `memory_items` / corrections: exported with `your_text` (the correction) alongside `text`
  (the original) — matches "Yes, including corrections."
- `notes` / `note_folders`: exported as organizational metadata (title, folder, starred,
  archived). `notes.transcript_text` itself (the Highly-sensitive raw-spoken-words column) is
  **not** duplicated onto the exported note row — but the same content is not missing: it is
  the same data the `GET /v1/notes/{id}` endpoint itself serves as `lines`
  (`app/api/notes.py::_lines`), both built from `transcriptLines` inside that day's
  `daily_reports.report` JSON, which `_reports()` already exports in full. Clarified this exact
  relationship in `DATA_INVENTORY.md` so a future reader doesn't look for raw text on the note
  row and conclude (wrongly) that it is missing.
- `daily_reports`: exported as the full stored `report` JSON blob — includes `transcriptLines`
  when present.
- `transcripts` / `transcript_segments`: exported in full (the only account storage mode this
  table is actually populated for is `full_cloud`, the legacy/disabled-by-default path per
  `DATA_FLOW.md`).
- `access_tokens`: exported as `kind`/`name`/`scopes`/`created_at` only — confirmed by the field
  list and by `tests/test_web_api.py`'s existing `assert "adm_" not in raw.decode()` that the
  token secret itself never appears.
- `audio_uploads`: exported as clip metadata with `audio_still_held` — never the audio bytes
  (which are temporary and, per ADR-0003, never meant to be reconstructable from the export at
  all).
- Everything marked "No" in the inventory (`device_keys`, object storage inline, `users` beyond
  the account summary, `credentials` beyond email, `sealed_deliveries`, `usage_counters`,
  `processing_jobs`, `email_tokens`, `login_failures`, `pending_account_object_deletions`) was
  confirmed absent from every export generator by reading all seven of them — none references
  those tables.

## Outcome

No code gap found in either account deletion or export field-level completeness. One
documentation gap found and fixed: `processing_jobs` was missing from `DATA_INVENTORY.md`'s
table list. The `notes.transcript_text` relationship to the export was ambiguous enough to
misread as a gap on a first pass; clarified in the inventory rather than left to rediscover.
