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

## Health and sanity checks

```bash
curl -s https://HOST/v1/health          # config state incl. storage/DB/provider flags
```

Watch for in logs (event names, no payloads): `upload_completed`, `job_dead_letter`
(operator attention), `retention_sweep`, `worker_no_stages_enabled`.

## Still manual (Phase 8)

DB roles (migration owner / app / read-only), backups + restore drills, Sentry wiring,
rate limiting, secrets manager, incident runbook. Do not treat a private-test deployment
as hardened.
