# API endpoints (v1)

All routes are under `/v1` (see [VERSIONING.md](VERSIONING.md)). Every response error uses the
shared envelope: `{"error": {"code": "...", "message": "...", "request_id": "..."}}`. Clients
branch on `error.code`, never on the message.

Authentication is `Authorization: Bearer <token>`. Tokens are stored only as SHA-256 hashes.
Unknown, revoked, and expired tokens all get the same `401`.

## Health

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/v1/health` | none | Liveness |

## Sign-in (self-service)

| Method | Path | Scope | Purpose |
| --- | --- | --- | --- |
| POST | `/v1/signup` | none | Email + password → device token (shown exactly once) |
| POST | `/v1/login` | none | Email + password → device token |

Argon2id hashing; 5 consecutive wrong passwords from one client address lock that address
(not the account) for 15 minutes (`account_locked`), and 50 failures across all addresses
within an hour lock the whole credential for 15 minutes. The operator CLI (`python -m app.admin`) remains available for
operator-issued tokens.

## Account (device tokens)

| Method | Path | Scope | Purpose |
| --- | --- | --- | --- |
| GET | `/v1/me` | `upload:write` | Verify a token; returns `storage_mode` and scopes |
| PUT | `/v1/me` | `upload:write` | Switch storage mode (`phone_only` / `reports_cloud` / `full_cloud`; affects future uploads only) |
| DELETE | `/v1/me` | `upload:write` | Delete the account: clears the user's object-storage prefix, then cascades the database rows (tokens, device key, uploads, transcripts, memory items, reports). `204` on success |
| DELETE | `/v1/tokens/current` | `upload:write` | Revoke the calling token (Disconnect). Also revokes its device key and deletes transcripts sealed to it |

`DELETE /v1/me` is irreversible and takes no body. Object storage is cleared before the database
delete; if clearing is incomplete (or the store is unavailable while uploads exist) the call
returns `503` and the account stays intact, so the client can retry. A purge record sweeps any
upload that was in flight when the account was deleted. The calling token is the proof of
possession and is revoked by the same delete.

## Sync (on-device processed memory, ADR-0020)

| Method | Path | Scope | Purpose |
| --- | --- | --- | --- |
| POST | `/v1/sync` | `upload:write` | Push structured daily memory the phone produced on-device |

The backend never processes audio in this flow; it stores what the phone sends. Idempotent per
session: a report for a `memoryDay` replaces the memory items and transcript lines of the
sessions it carries and leaves other sessions' data for that day (for example from a second
device) untouched.

Request body (camelCase fields):

| Field | Type | Notes |
| --- | --- | --- |
| `device` | string | Required, max 80 characters |
| `generatedAtEpochMs` | integer | Required |
| `reports` | array | Required, may be empty, max 31. Each report: `memoryDay` (ISO date), `zoneId` (IANA zone), `headline` (max 280), `summary` (max 1680), `items`, `coverage` (`recordedMs`, `speechMs`, `transcribedMs`, `sessions`), `generatedAtEpochMs`, `extractionModel`, `extractionVersion`; optional `transcriptLines` |
| `includeTranscripts` | boolean | Default `false`. Transcript lines are stored only when `true` |
| `deletedSessions` | array of UUID | Max 500. Removes everything derived from sessions deleted on the phone: memory items, report content, and any server-held clips (objects included), transcripts, sealed deliveries and jobs. Repeating an id is a no-op; `503` if stored audio cannot be deleted (nothing changes, retry) |

Each item has `kind`, `text` (1 to 280 characters; longer is `422`, never truncated),
`confidence`, `sessionId` (UUID), and optional `quote` (max 280), `owner` and `counterpart`
(max 80), `due`, `direction`, `evidenceEpochMs`. A `commitment` item requires `direction`. A report holds at
most 2000 items and 20000 transcript lines.

Response `200`: `{"synced": <reports>, "items": <items stored>, "deletedSessions": <count>}`.

Errors: `401`/`403` for a missing, revoked, or under-scoped token; `422` for a body that does not
match the contract (the envelope reports field locations, never the rejected values).

## Uploads (speech clips, ADR-0015)

| Method | Path | Scope | Purpose |
| --- | --- | --- | --- |
| POST | `/v1/uploads` | `upload:write` | Create (or re-ask for) an upload instruction for a clip |
| POST | `/v1/uploads/{id}/complete` | `upload:write` | Verify the stored bytes and queue transcription |
| GET | `/v1/uploads/{id}` | `upload:write` | Current state (`pending`, `received`, `transcribed`, `delivered`, `failed`) |

The upload id is the phone's UUIDv7; every call is idempotent by it. Error codes:

| Code | Status | Meaning | Phone does |
| --- | --- | --- | --- |
| `processing_unavailable` | 503 | No processing stage enabled (Gate B) | Back off; keep the audio |
| `device_key_required` | 409 | Phone storage mode, no key registered | Register the key, retry |
| `idempotency_conflict` | 409 | Same id or clip position, different content | Stop (terminal), show it |
| `upload_too_large` / `upload_too_long` | 413 | Over the per-clip limits | Stop (terminal) |
| `quota_exceeded` | 429 | Daily upload quota reached | Back off until tomorrow |
| `upload_missing` | 409 | Completed before uploading the bytes | Upload, then complete |
| `upload_integrity_failed` | 409 | Size or SHA-256 mismatch; object deleted | Upload again |
| `integrity_unverifiable` | 503 | Store didn't report a checksum | Back off (operator problem) |

## Sealed delivery (ADR-0014)

| Method | Path | Scope | Purpose |
| --- | --- | --- | --- |
| PUT | `/v1/device-key` | `upload:write` | Register this device's X25519 public key (one per token, no rotation) |
| GET | `/v1/deliveries?limit=` | `upload:write` | Transcripts sealed to this device's key |
| POST | `/v1/deliveries/ack` | `upload:write` | Phone stored them; the server deletes its copies |

Sealing: HPKE base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256, AES-256-GCM; ciphertext is
`enc || ct`; `info` is `aidm:delivery:v1:` + the upload id. The plaintext is a JSON object
with `v: 1`, the upload and segment ids, `offset_ms`, provider/model/language, and `spans`
(`start_ms`, `end_ms`, `text`, `speaker`, `confidence`).

## Reports (ADR-0019)

| Method | Path | Scope | Purpose |
| --- | --- | --- | --- |
| GET | `/v1/reports/{memory_day}` | `memory:read` | The daily report contract v1 (plus `transcriptLines` with `transcript:read` when synced with transcripts); `404 report_not_found` if nothing was generated |

A synced report is stored as the phone sent it and projected onto the v1 contract on read
(items become the `action_items` / `commitments` / `decisions` / `ideas` / `open_questions`
sections, each with one evidence entry), so the response shape is the same whether the last
writer was a sync or server-side extraction.

Reports are available to an authenticated owner when they exist, including after explicit sync;
storage mode alone does not determine report visibility. `memory:read` is also the scope of
API keys (`kind=api`, read-only by default, `app/auth.py`).

## Coming later

Recall (`ask_memory`, `search_memory`, ...) for API clients arrives with Phase 4's
query engine, through the Memory Query Service only, and only for server-held data
(explicitly synced reports or full-cloud storage). API-key management UI and per-key rate
limits are Phase 5/10 items.
