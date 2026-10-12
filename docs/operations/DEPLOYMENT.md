# Deployment runbook

One FastAPI app, one worker, one PostgreSQL, one S3-compatible bucket. No queue service
(ADR: no Redis until measured need). Everything runs from `backend/` with `uv`.

## Local / private-test stack

```bash
cd backend
export DATABASE_URL=postgresql+psycopg://USER:PASS@HOST:5432/aidm
uv run alembic upgrade head                 # migrations are forward-only; test recovery by restore
uv run uvicorn app.main:app --port 8000     # the API
uv run python -m app.worker                 # the worker (separate process)
```

The phone app needs the API's base URL (Settings → Account). The worker needs the same
`DATABASE_URL` and object-storage settings as the API.

## Object storage

S3-compatible (R2 in production, MinIO locally). All of `S3_BUCKET`, `S3_ACCESS_KEY_ID`,
`S3_SECRET_ACCESS_KEY` (plus endpoint/region) must be set or uploads return 503. Configure
the bucket lifecycle backstop per [OBJECT_STORAGE.md](OBJECT_STORAGE.md).

## Provider enablement (Gate B — deliberate)

Default posture: **both providers ship disabled.** With them disabled the API refuses
uploads (`503 processing_unavailable`) and the worker registers no stage handlers — it
only runs the retention sweep and logs `worker_no_stages_enabled`. That is correct
behavior, not an outage.

Enabling (only after the ADR-0018 checklist — terms, consent review, cost model):

```bash
TRANSCRIPTION_PROVIDER=deepgram   # worker exits at startup without DEEPGRAM_API_KEY
EXTRACTION_PROVIDER=claude        # same posture; needs CLAUDE_API_KEY
```

The startup `SystemExit` on a missing key is intentional: better a loud boot failure than
audio accepted with nowhere to go.

## Error monitoring

The API/worker Sentry SDKs are disabled until `SENTRY_DSN` is set. Configure the backend project DSN on the API service and on the worker if it is deployed separately. Set `APP_ENV=production` and `SENTRY_RELEASE` (or allow Railway's commit SHA fallback) in each process. Keep the backend DSN in the platform's protected environment variables. Android and Wear use the public client DSN for their shared mobile project; the website uses its public web DSN and integrity-pinned browser SDK.

All three integrations discard event messages, request details, user context, breadcrumbs, and source locals before sending. They capture errors only, with tracing, replay, screenshots, and view hierarchy disabled. Before enabling production reporting, send a synthetic error from a staging build and confirm the Sentry event has the expected environment and release and contains no message, request, user, or source context.

## Public authentication limits

`/v1/signup` allows five attempts per client address per hour; `/v1/login` allows ten per minute. Credential lockout remains a separate per-account safeguard. The limiter is bounded, process-local, and does not persist addresses. Set `TRUST_RAILWAY_X_REAL_IP=true` only when authentication traffic reaches the API through Railway's public HTTP edge; its current edge behavior overwrites `X-Real-IP`. Private-network calls do not pass through that edge and must not trust the header. Railway describes this behavior as changeable, so verify the client-IP path during a platform migration. Keep one API replica with the in-process limiter; use a shared trusted-edge limiter before scaling.

## Account deletion and outstanding storage race

`DELETE /v1/me` removes the user's object-storage prefix before the database cascade and returns 503 if object deletion is incomplete. A signed PUT URL remains usable until it expires, so an upload already in flight can recreate an object after the prefix sweep. Keep upload URL TTL short and configure the bucket lifecycle backstop. A durable post-expiry prefix cleanup is still required before claiming complete account deletion under concurrent uploads; the current single-process worker is not deployed in production.

## Health and sanity checks

```bash
curl -s https://HOST/v1/health          # config state incl. storage/DB/provider flags
```

Watch for in logs (event names, no payloads): `upload_completed`, `job_dead_letter`
(operator attention), `retention_sweep`, `worker_no_stages_enabled`.

## Remaining production gates

DB roles, backup-and-restore drills, deletion cleanup after signed URL expiry, a shared auth limiter before horizontal scaling, a production Sentry DSN and event-ingestion check, and phone/watch accessibility and endurance runs still require operator or physical-device verification. Do not treat a private-test deployment as hardened.
