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
| GET | `/v1/me` | `upload:write` | Verify a token; returns the legacy `storage_mode` value and scopes |
| PUT | `/v1/me` | `upload:write` | Update the legacy upload-processing mode. It does not control notes or transcript sync in the current phone app |
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
the legacy storage-mode field does not determine report visibility. `memory:read` is also the scope of
API keys (`kind=api`, read-only by default, `app/auth.py`).

## Web sessions and account (ADR-0024)

`POST /v1/signup` and `POST /v1/login` take an optional `"client": "web"`. A web session gets the
scopes `memory:read`, `memory:write`, `transcript:read`, `account:manage` (never `upload:write`)
and a token that expires after 30 days. A phone keeps the device defaults.

| Route | Scope | What it does |
| --- | --- | --- |
| `GET /v1/me` | phone or `account:manage` | legacy `storage_mode`, scopes, `email` |
| `PUT /v1/me` | phone or `account:manage` | change the legacy upload-processing mode; this does not change notes or transcript sync |
| `POST /v1/me/password` | phone or `account:manage` | change password; signs other browsers out, not phones. 5 per 5 min |
| `DELETE /v1/me` | phone or `account:manage` | delete the account. A browser must send `{"password": "..."}` |
| `GET /v1/tokens` | phone or `account:manage` | active sign-ins (phones, browsers, API keys); `current` marks the caller |
| `DELETE /v1/tokens/{id}` | phone or `account:manage` | revoke one (a lost phone, an old browser, a key) |
| `GET /v1/api-keys`, `POST /v1/api-keys` | phone or `account:manage` | list / create read-only keys (max 10). The secret is shown once |
| `GET /v1/export` | phone or `account:manage` | everything held about you. `?format=json` (default, one document) or `?format=jsonl` (one JSON object per line). 3 per hour, idempotent per format within the signed URL's TTL |

## Memory (browse and correct)

| Route | Scope | What it does |
| --- | --- | --- |
| `GET /v1/memory/days` | `memory:read` | days with memory, newest first |
| `GET /v1/memory/items` | `memory:read` | filter by `day`, `from`, `to`, `kind`, `q`, `include_dismissed`; `limit` (max 100), `offset` |
| `PATCH /v1/memory/items/{id}` | `memory:write` | `{"dismissed": bool}`, `{"text": "..."}` (your wording, stored beside the original), `{"clear_text": true}` |
| `GET /v1/memory/items/{id}/evidence` | `transcript:read` | the cited transcript lines the server holds |

## Notes and folders (ADR-0027)

A note is one synced session. Notes are built when a phone syncs. Archiving hides a note, and
deleting a folder unfiles its notes. Deleting a note removes its server-side note, highlights,
and transcript, then stores a deletion marker so the phone will not sync it back; the phone's
local copy remains untouched.

| Route | Scope | What it does |
| --- | --- | --- |
| `GET /v1/notes` | `memory:read` | `q` (every word must match a title, item or transcript), `folder` (`none` or a folder id), `starred`, `archived`, `day`, `from`, `to`, `sort` (`newest`, `oldest`, `title`, `longest`), `limit` (max 100), `offset`. Returns `{notes[], total}`; while searching each note has a `snippet` |
| `GET /v1/notes/{session_id}` | `memory:read` | `{note, items[], lines[] \| null}`; `lines` is `null` without `transcript:read` |
| `PATCH /v1/notes/{session_id}` | `memory:write` | any of `{"title": "..."}` (blank restores the automatic title), `{"folder_id": id \| null}`, `{"starred": bool}`, `{"archived": bool}` |
| `DELETE /v1/notes/{session_id}` | `memory:write` | remove this note and all server-side data derived from its session; remember the id so phone sync cannot restore it; `204` |
| `POST /v1/notes/bulk` | `memory:write` | the same change (not `title`) for `session_ids` (1 to 100) |
| `GET /v1/folders` | `memory:read` | `{folders[{id, name, notes}], totals{all, starred, unfiled, archived}}` |
| `POST /v1/folders` | `memory:write` | `{"name": "..."}` (1 to 60 characters, unique ignoring case, at most 100 folders) |
| `PATCH /v1/folders/{id}` | `memory:write` | `{"name": "..."}` |
| `DELETE /v1/folders/{id}` | `memory:write` | its notes become unfiled |

Without `transcript:read`, spoken words are neither returned nor searched: a title or preview made
from them is replaced by "Recording" and left out of search.

## Ask (recall)

`POST /v1/ask` with `{"question": "...", "today": "YYYY-MM-DD"}` (scope `memory:read`) returns
`{answer, abstained, source, range_label, items[], notes[]}`. Retrieval is a bounded,
user-scoped query (dates in the question, its keywords, the kind of item it hints at), and the
answer is built only from what that returns; it abstains when nothing matches. `source` is
always `evidence_only` (ADR-0028: Ask is retrieval-only — no model provider, operator-funded or
otherwise, ever sees this person's memory; the items are listed plainly, read back as-is).
`notes` lists sessions whose own words (title or transcript) mention the question, for people
who read transcripts — a way to stay askable even when a question matches no structured item.
Metered per person per UTC day (`DAILY_ASK_LIMIT`, default 50); over the limit gives 429
`quota_exceeded` with `Retry-After`.

## Email and capabilities (ADR-0025, ADR-0028)

| Route | What it does |
| --- | --- |
| `GET /v1/capabilities` | `{email: bool}` — whether this server has a mail provider configured. No billing or model-provider capability exists (ADR-0028: the product is free, with no operator-funded inference) |
| `POST /v1/password/forgot` | `{email}`; always 202; 503 `email_unavailable` without a mail provider. 5 per hour |
| `POST /v1/password/reset` | `{token, new_password}`; ends browser sessions and API keys. 400 `invalid_link` |
| `POST /v1/me/verify`, `POST /v1/verify` | send the confirmation email / confirm with `{token}` |

There is no billing, checkout, portal, or webhook route. Dormant Stripe code paths were
removed entirely (ADR-0028); do not reintroduce them.

## Health

`GET /v1/health` is liveness. `GET /v1/ready` also checks the database (503 `unavailable` when it
cannot answer).

## Failure responses worth handling

`503 database_unavailable` and `503 storage_unavailable` carry `Retry-After`: retry, don't treat
as a bug. `429 quota_exceeded` is the daily Ask limit; other 429s are per-address rate limits.

## Coming later

Entity-aware recall (people, aliases, merge) and a recall benchmark (TODO Phase 4).
