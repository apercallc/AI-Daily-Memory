# Data inventory and classification

Every category of data the product creates or stores, where it lives, and how sensitive it is.
This is the release gate `docs/product/PRIVACY.md` and `TODO.md` Phase 8 call "data
inventory/classification." Sources: `apps/android/.../data/*.kt` (Room entities), `backend/app/db/models.py`
(the only backend schema — ADR-0009 "backend is the sole DB client"), `SECURITY.md`.

Classification bands used below:

- **Highly sensitive** — raw speech content, or anything that could re-identify who said what.
- **Sensitive** — identifies the person or their activity but is not raw speech (account
  identity, structured memory, derived summaries).
- **Internal** — operational metadata with no content (job state, usage counters).

## On the phone (always; this is the source of truth — ADR-0001, ADR-0026)

| Data | Classification | Where | Lifetime | Leaves the phone? |
| --- | --- | --- | --- | --- |
| Raw audio segments | Highly sensitive | `noBackupFilesDir`, Tink AEAD-encrypted (`SegmentCipher`) | Governed by `AudioRetention` (transcripts-only default deletes after the recovery window; 7d / 30d / "until I delete it" are opt-in) | Never (ADR-0026). A user-configured transcription provider receives it directly, phone-to-provider, if the user sets one. |
| On-device transcript lines | Highly sensitive | `capture.db`, encrypted by `TranscriptCipher` (separate Keystore key from audio) | Kept until the user deletes the session; not purged by `AudioRetention` | Only if the user enables "Also sync full transcripts"; otherwise never. |
| Extracted memory items (decisions/actions/commitments/ideas/questions) | Sensitive | `capture.db` (`MemoryItemEntity`) | Kept until the user deletes the session | Synced (metadata, not audio) when notes sync is on. |
| Daily reports | Sensitive | `capture.db` (`DailyReportEntity`) | Regenerated per processing run; kept until the day's sessions are deleted | Synced when notes sync is on. |
| Capture/session/segment metadata (timestamps, checksums, lifecycle state) | Internal | `capture.db`, device file-based encryption | Follows the session's own lifetime | Session id/start time only, if sync is on. |
| User-supplied provider API keys (transcription/extraction) | Highly sensitive (credential) | Android Keystore-wrapped `SharedPreferences` (`ProcessingSettings`) | Until the user clears them | Sent only to the URL the user configured, as the `Authorization` header; never to this project's server. |
| Watch pairing/ack state | Internal | `SharedPreferences` | Until uninstall | No (local Wear Data Layer only). |

## On the server (only if the user connects an account and enables sync — ADR-0014, ADR-0026)

| Table | Classification | Lifetime | In `GET /v1/export`? |
| --- | --- | --- | --- |
| `users` | Sensitive (account identity) | Until account deletion | Account summary only |
| `credentials` (email + password hash) | Highly sensitive (credential) | Until account deletion | Email only; password hash never |
| `access_tokens` (device tokens, API keys — hash only) | Sensitive | Until revoked or account deletion | Metadata only (kind/name/scopes/timestamps); the secret itself is never stored, so never exported |
| `device_keys` (phone's HPKE public key) | Internal | Until the owning token is revoked | No |
| `audio_uploads` (clip metadata; **not audio bytes**) | Sensitive | Temporary — see `RETENTION_AND_DELETION.md` | Clip list (state only, no content) |
| Object storage `users/<id>/audio/*` (temporary raw audio) | Highly sensitive | Temporary — deleted right after transcription, or by the retention sweep; 8-day bucket lifecycle backstop | Never inlined; the clip list says whether audio is still held |
| `transcripts` / `transcript_segments` (plaintext evidence) | Highly sensitive | Immutable evidence; exists only for `full_cloud` accounts (legacy path, disabled by default — see `DATA_FLOW.md`) | Yes, for accounts that have it |
| `sealed_deliveries` (transcript, HPKE-sealed to the phone's key) | Highly sensitive (ciphertext; server cannot read it) | 7-day hold or until the phone acknowledges | No (the server can't decrypt it) |
| `memory_items` / `memory_item_evidence` / `memory_item_corrections` | Sensitive | Until the session/account is deleted | Yes, including corrections |
| `daily_reports` | Sensitive | Until the account is deleted; regenerated per extraction pass | Yes |
| `notes` / `note_folders` / `deleted_notes` | Sensitive; **Highly sensitive** when `notes.transcript_text` is populated (raw spoken words, filled only if the user turns on full transcript sync — see `Note` model docstring) | Until deleted | Yes — titles/organization directly; the raw spoken words themselves are exported as part of `daily_reports` (the `transcriptLines` the report JSON carries), not duplicated onto the note row itself. |
| `usage_counters` | Internal | Per-day counters; not separately purged (low-sensitivity, no content) | No |
| `processing_jobs` | Internal | Job-queue state (kind, attempts, lease, error code) referencing an upload/session id; no transcript or memory content | No |
| `email_tokens` (password reset / verify — hash only) | Internal (credential-adjacent) | Single-use, expires | No |
| `login_failures` (keyed hash of client address, never the address) | Internal | Pruned after a day idle | No |
| `pending_account_object_deletions` | Internal | Deleted once the purge completes (or dead-lettered — see `app/retention.py`) | No |

## What never exists on the server

- Raw audio at rest beyond the temporary upload window (ADR-0003).
- Any plaintext transcript for an account whose phone keeps its own memory (`phone_only`,
  `reports_cloud`): transcripts for those accounts are sealed with HPKE to the phone's own key
  before they ever touch disk server-side (`DeviceKey`/`SealedDelivery`), so the server holds
  ciphertext it cannot read.
- Voiceprints or biometric speaker models (ADR-0016 is explicitly not implemented).
- The plaintext of any password, device token, API key, or email verification/reset token —
  only salted/keyed hashes are ever stored (`app/auth.py`, `app/credentials.py`, `app/email_tokens.py`).
- Third-party provider API keys the user enters on their phone (those stay phone-side only).

## Where sensitivity gets "downgraded" and why that's intentional

- `AudioUpload` rows hold clip *metadata* (duration, codec, checksum, state) and not the audio
  itself — the bytes live only in object storage and only temporarily. That's why the row
  survives longer (duplicate-detection window, `FINISHED_METADATA` = 30 days) than the object does.
- `login_failures.source_hash` is a keyed hash, not the client address, so the row carries no
  reversible network identity even though it exists to rate-limit by address.
