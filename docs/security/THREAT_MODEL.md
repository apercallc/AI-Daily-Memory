# Authentication and authorization threat model

The release gate `TODO.md` Phase 8 Security calls "the authentication threat model and
authorization tests for every user-owned resource." This document is the threat model; the
authorization-test audit (which endpoints have which tests) is a separate, still-open pass —
see "Open items" at the bottom. Scope: the backend API (`backend/app`) and the object-storage
boundary it controls. Phone-side threats (lost/stolen device, local malware) are covered by
`SECURITY.md`'s "Local audio at rest" section, not repeated here.

## Assets

1. Raw audio (temporary, in object storage) and plaintext transcript/evidence (server-side,
   only for accounts that aren't phone-only).
2. Structured memory (decisions/actions/commitments/ideas/questions, corrections, reports).
3. Account credentials: password hash, device tokens, API keys, email verification/reset tokens.
4. Availability of the API itself (abuse/DoS).

## Actors and their trust level

| Actor | Can authenticate as | Scope |
| --- | --- | --- |
| The phone app | A `device` token, scopes `upload:write`, `memory:read` | Its own `user_id` only |
| A signed-in browser | A `device`-kind web token (30-day TTL), scopes `memory:read`, `memory:write`, `transcript:read`, `account:manage` | Its own `user_id` only |
| A third-party / CLI client | An `api`-kind key, scope `memory:read` only by default | Its own `user_id` only; read-only |
| Anyone unauthenticated | Signup, login, password-reset-request, email-verify-resend only | No account access |

Scopes are enforced per-endpoint by `app.auth.require(scope, *alternatives)` — a FastAPI
dependency, not a convention callers must remember to check.

## Threats and mitigations

### T1 — Token theft (device token, browser session, or API key leaked)

- **Mitigation.** Tokens are 256-bit random values (`secrets.token_urlsafe(32)`); only a
  SHA-256 hash is stored (`app/auth.py`), so a database leak alone doesn't yield a usable
  credential. Every token is individually revocable (`GET /v1/tokens`, `DELETE
  /v1/tokens/{id}`); revoking a device token cascades to its HPKE device key and any sealed
  transcripts, so a stolen phone's access can be fully cut off remotely.
- **Gap.** No login/API-key anomaly detection (new-geography sign-in alerts, per-key volume
  spike alerts) exists yet — see Phase 10 "abuse-monitored public API access," still open.

### T2 — Cross-user data access (one account reading another's data)

- **Mitigation.** Every query in `app/api/*.py` filters by `principal.user_id` (or an
  equivalent ownership check via `_owned()`/`_active()` helpers); there is no endpoint that
  accepts an arbitrary user id from the caller. Object-storage keys are namespaced
  `users/<uuid>/...` and `S3ObjectStore.delete_prefix` refuses any prefix that doesn't match
  exactly one user (`_USER_PREFIX` regex) — a bug that constructed a broader prefix would raise
  rather than delete across users.
- **Resolved.** `tests/test_authorization_matrix.py` is the consolidated audit this item asked
  for: every resource-owning endpoint (uploads, notes, folders, memory items + their evidence,
  tokens) is checked against a second account's valid-scope token and must 404, never return or
  modify the first account's data.

### T3 — Scope escalation (a read-only API key doing a write, or reading transcripts)

- **Mitigation.** `API_KEY_SCOPES = ("memory:read",)` — created keys get nothing else by
  construction (`issue_token`'s default), and `require()` 403s a token whose scopes don't
  include what the endpoint asks for. An API key can never create or revoke another key or
  token (`tokens.py`'s `Manager` dependency requires `upload:write` or `account:manage`,
  neither of which an API key carries) — so a leaked read-only key cannot escalate itself.
- **Resolved.** `tests/test_authorization_matrix.py` enumerates every one of the 32
  authenticated routes with its required scope(s) and proves, for each: no bearer token -> 401,
  a valid token missing every accepted scope -> 403. The 8 unauthenticated-by-design routes
  (signup/login, health/ready/capabilities, the password-reset and email-verify flows) are
  checked the other way — they must not demand a bearer token at all.

### T4 — Forged or tampered uploads (fake clip, oversized clip, wrong user's clip id reused)

- **Mitigation.** Every clip is size- and SHA-256-pinned at presign time (`presign_put`'s
  `ContentLength`/`ChecksumSHA256` params constrain the object store itself, not just the
  app), re-verified independently at `complete_upload` via `store.stat()`, and rejected (with
  the bad object deleted) on any mismatch. Upload ids are the client's own UUIDv7 and are
  idempotency-checked against the same content (`_same_clip`); a reused id with different
  content is a 409, never a silent overwrite.
- **Gap.** None known; this is the most heavily tested path (`tests/test_uploads.py`).

### T5 — Presigned URL abuse (URL shared, reused after expiry, or used to write outside the
account's prefix)

- **Mitigation.** URLs are scoped to one exact object key (`audio_key(user_id, upload_id)`)
  under that user's own prefix, size- and checksum-pinned, and time-boxed
  (`upload_url_ttl_seconds`). A presigned URL cannot be redirected to write a different key —
  S3-compatible presigning binds the key into the signature.
- **Gap.** TTL/scope behavior is exercised by `tests/test_uploads.py` and `tests/test_storage.py`
  and has now been reviewed as a named security control set (the specific ask in TODO.md Phase
  8): see `docs/security/OBJECT_STORAGE_REVIEW.md`. Its own residual-risk note stands: a
  presigned URL is valid for its full TTL even if the token that requested it is revoked a
  moment later — mitigated by a short TTL, not eliminated by revocation.

### T6 — Storage-outage or partial-failure data leakage (an account deletion that silently
leaves audio behind)

- **Mitigation.** `DELETE /v1/me` deletes the object-storage prefix *before* the database row,
  and fails the whole request (leaving the account intact) if storage deletion is incomplete —
  never deletes the account record while storage might still hold data. The
  `pending_account_object_deletions` tombstone catches the one remaining race (a presigned URL
  issued before deletion, completed after) and — as of this pass — is bounded and dead-lettered
  to an operator instead of retrying a permanent failure forever (migration 0015).
- **Gap.** No restore/disaster-recovery drill has been run (`docs/product/LAUNCH_GAPS.md`).

### T7 — Brute-force login / credential stuffing

- **Mitigation.** Per-credential, per-source lockout (`LoginFailure`, keyed-hashed source, not
  the raw address) plus a process-local sliding-window rate limiter on public auth routes
  (`app/auth_rate_limit.py`). Passwords are hashed with a slow KDF (`app/credentials.py`).
- **Gap.** The rate limiter is in-process; a second API replica would need a shared limiter
  before it's a real control at scale (`docs/product/LAUNCH_GAPS.md` "Single API replica" —
  explicitly deferred until that need is measured, per AGENTS.md "no infrastructure without a
  measured need").
- **Operational risk.** If deployed behind a proxy that doesn't forward (or isn't configured to
  be trusted for) the real client address, every caller can collapse into one rate-limit
  bucket. `warn_if_client_ips_collapse` logs loudly at startup when this misconfiguration is
  detected in staging/production rather than failing silently.

### T8 — Availability / resource-exhaustion abuse (upload flooding, export flooding, Ask flooding)

- **Mitigation.** Per-user daily upload-byte quota (`limits_for(settings).upload_bytes_per_day`),
  per-user daily Ask limit (`app/usage.py`'s `take`, metered server-side — a client can't raise
  or reset its own counter), and export rate-limiting (3/hour, `auth_rate_limit("export", ...)`).
  Per AGENTS.md rule 3, these are availability controls, not paid-plan metering — there is
  exactly one tier.
- **Gap.** No shared, cross-replica throttling yet (see T7); no per-key anomaly/scraping
  detection yet (Phase 10, still open).

### T9 — A user's own provider credentials (self-configured transcription/extraction keys)

- **Mitigation.** These never reach our server: `ApiKeyTranscriptionProvider`/
  `ApiKeyExtractionProvider` run entirely on the phone, calling the URL and key the user
  entered directly. The transcript sent to that provider is delimited as data inside the
  prompt (not interpreted as instructions), the provider gets no tool access, and every field
  of its response is validated (`ProcessingValidation`) before it's persisted — an untrusted
  model response can't inject itself into app state beyond a rejected/dropped item.
- **Gap.** None known for this specific threat; the provider itself is a third party the user
  chose and trusts with their own data by that choice.

## Open items (do not claim these are resolved)

- **Login/API-key anomaly monitoring** (new-geography alerts, per-key volume spikes) — Phase 10.
- **A shared, cross-replica rate limiter** — deferred until horizontal scaling is a measured
  need, not before (AGENTS.md "no infrastructure without a measured need").
- **A disaster-recovery / restore drill** — `docs/product/LAUNCH_GAPS.md`.
