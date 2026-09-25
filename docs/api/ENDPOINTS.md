# API endpoints (v1)

All routes are under `/v1` (see [VERSIONING.md](VERSIONING.md)). Every response error uses the
shared envelope: `{"error": {"code": "...", "message": "...", "request_id": "..."}}`. Clients
branch on `error.code`, never on the message.

Authentication is `Authorization: Bearer adm_...`. Tokens are stored only as SHA-256 hashes.
Unknown, revoked, and expired tokens all get the same `401`.

## Health

| Method | Path | Auth | Purpose |
| --- | --- | --- | --- |
| GET | `/v1/health` | none | Liveness |

## Account (device tokens)

| Method | Path | Scope | Purpose |
| --- | --- | --- | --- |
| GET | `/v1/me` | `upload:write` | Verify a token; returns `storage_mode` and scopes |
| DELETE | `/v1/tokens/current` | `upload:write` | Revoke the calling token (Disconnect). Also revokes its device key and deletes transcripts sealed to it |

Tokens are issued by `python -m app.admin` until account sign-in exists
(`create-account`, `issue-device-token`, `list-tokens`, `revoke-token`).

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

## Coming later

Recall (`ask_memory`, `search_memory`, `get_day`, `get_daily_report`, ...) for API keys and
MCP arrives in Phase 5, through the Memory Query Service only, and only for server-held data
(reports-in-cloud or full-cloud storage modes).
