# Retention and deletion matrix

Every retention window and deletion trigger the product enforces, and what implements it.
This is the release gate `docs/product/PRIVACY.md` and `TODO.md` Phase 8 call the
"retention/deletion matrix." Companion to `DATA_INVENTORY.md` (what exists) — this is how long
it lives and what removes it.

## Phone (ADR-0013, ADR-0017)

| Data | Default retention | Configurable? | What removes it |
| --- | --- | --- | --- |
| Raw audio, transcribed | Deleted after the recovery window (24h past server/local acknowledgement) under the default `AudioRetention` setting | Yes — `AudioRetention`: transcripts-only (default) / 7 days / 30 days / until manually deleted | `SegmentLifecycle.RetentionDelete`; never fires before the recovery-window floor, whatever the setting |
| Raw audio, no speech detected (`NO_SPEECH`/`EMPTY`) | Same as transcribed audio, acknowledged immediately (ADR-0017) | Same `AudioRetention` setting | Same retention pass |
| Raw audio that failed VAD classification (`UNREADABLE`) | Kept, retried; never auto-deleted | N/A — exempt until classification succeeds | Retry eventually classifies it, or the user deletes the session |
| On-device transcript / memory items / daily reports | Until the session is deleted | User-deleted only; not time-based | `SessionDataDeletion` (refuses while a session is active or has server-side audio still pending) |
| Deleted session's server-side data | Propagated via sync queue | N/A | `purge_sessions` (sync) — see below |

## Server — temporary audio (ADR-0003, ADR-0009; `app/retention.py`)

| Case | Who deletes | When |
| --- | --- | --- |
| Clip transcribed, transcript committed | Transcription handler | Immediately after the commit |
| Transcription dead-lettered | Worker dead-letter hook, else the sweep | Immediately, or next sweep (interval is deployment-configured) |
| Upload never completed (`pending`) | Retention sweep | Presigned URL TTL + `PENDING_GRACE` (1h) |
| Received but never processed | Retention sweep | `MAX_HOLD` (7 days) after receipt |
| Integrity check failed on complete | `complete_upload` handler | Immediately; the phone is told to upload again |
| Finished upload metadata (`delivered`/`failed` rows — not the bytes) | Retention sweep (`_purge_finished`) | `FINISHED_METADATA` (30 days) after creation |
| Sealed transcript delivery the phone never collected | Retention sweep (`_expire_deliveries`) | `SealedDelivery.expires_at` (7-day hold, ADR-0014); the clip is marked `failed` so the phone (which still has the audio) re-uploads |
| Account deleted | `DELETE /v1/me` deletes the whole `users/<id>/` prefix synchronously; a `pending_account_object_deletions` tombstone catches anything written through a presigned URL issued before deletion but completed after | Synchronous at deletion; the tombstone resolves after every outstanding presigned URL for that account has expired (`MAX_UPLOAD_URL_TTL_SECONDS` + `PENDING_GRACE`), now bounded at `MAX_ACCOUNT_PURGE_ATTEMPTS` attempts before dead-lettering to an operator |
| Bucket lifecycle backstop | Object-store lifecycle rule (`docs/operations/OBJECT_STORAGE.md`) | 8 days after creation — only catches what the app's own sweep missed |

## Server — structured memory and account data

| Data | Retention | Trigger |
| --- | --- | --- |
| `memory_items`, `memory_item_evidence`, `memory_item_corrections`, `daily_reports`, `notes`, `note_folders` | Until the session (sync deletion) or the account is deleted | User action: delete a note/session (phone or web), or delete the account |
| `deleted_notes` tombstones | Indefinite — they exist precisely so a later sync from an older phone copy can't resurrect a removed note | Only removed by account deletion (cascades with the user row) |
| `transcripts` / `transcript_segments` (full_cloud, legacy path) | Immutable evidence; never rewritten (ADR-0004). Removed only by session/account deletion | User action, or account deletion |
| `access_tokens` (device tokens, API keys) | Until revoked, expired (browser sessions: 30 days), or account deletion | User action (`DELETE /v1/tokens/{id}`), natural expiry, or account deletion |
| `device_keys` | Revoked when the owning token is revoked | `revoke_token` cascades to the device key and its sealed deliveries |
| `credentials`, `email_tokens`, `login_failures`, `usage_counters`, `pending_account_object_deletions` (keyed to the account) | Until account deletion (cascade), or their own short-lived expiry (`email_tokens` single-use; `login_failures` pruned after a day idle) | Account deletion cascades every `user_id`-scoped row in one statement (ADR-0009: "children of a user cascade on delete") |

## Account deletion, end to end (`DELETE /v1/me`, `app/api/account.py`)

1. A browser session must re-confirm the password; a phone's device token is itself the proof
   of possession (no separate confirmation).
2. An advisory lock (`pg_advisory_xact_lock`) serializes deletion against any upload in flight
   for the same account, so a concurrent upload can't reserve a new object after the prefix
   delete starts.
3. The entire `users/<id>/` object-storage prefix is deleted **before** the database row. If
   storage deletion fails or is incomplete, the request fails with 503 and the account **is
   not deleted** — deletion is retried whole, never left half-done.
4. A `pending_account_object_deletions` tombstone is recorded for any object a still-valid
   presigned URL could write *after* this request returns (the phone may have one outstanding).
   It resolves once every such URL has expired, is retried with a bounded backoff documented in
   `RETENTION_AND_DELETION.md`'s table above, and now dead-letters to an operator instead of
   retrying forever (migration 0015).
5. The `users` row is deleted, cascading to every table above through `ON DELETE CASCADE` —
   one statement, not a hand-maintained list of tables to remember.

## Known gaps (do not claim these are resolved)

- **No backup-retention policy is verified yet.** Database backups are not configured and
  monitored (`docs/product/LAUNCH_GAPS.md`); a restore drill is a Phase 8 release gate, not
  done.
- **Bucket lifecycle rule must be applied by the operator** per bucket (`docs/operations/OBJECT_STORAGE.md`);
  this document describes the intended backstop, not a verified-live configuration for every
  deployment.
- **Deleted-account purge retries were unbounded until this pass**; now bounded (migration 0015).
