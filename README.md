# AI Daily Memory

AI Daily Memory is an Android and Wear OS project for capturing a day locally and turning it into searchable, evidence-backed memory. The phone records and stores audio; the watch controls the phone and displays acknowledged state.

## Current status

- Android capture, session controls, encrypted local segments, recovery, and on-phone transcription (free downloadable model, English) are implemented. Summaries are optional and use the person's own API key (ADR-0026).
- Wear OS provides remote controls and reflects phone-confirmed state.
- The backend stores synced notes and serves the web app and REST API: account sign-in, scoped tokens, sync, reports, memory browse and Ask. It does no transcription or summarizing.
- Audio is never uploaded. Server-side transcription and extraction stay disabled, so new uploads are refused. The service is free; billing code exists but is off.
- Release readiness still requires physical phone/watch accessibility and endurance testing. This repository is not a production release declaration.

## Privacy

Capture is visible and can be paused or stopped. Audio stays on the phone and is deleted once transcribed. Syncing notes to an account is optional and never includes audio; a person's own transcription key is the one case where audio goes to a provider, directly from the phone. Error monitoring is configured to discard messages, request data, user context, and breadcrumbs before sending exception diagnostics to Sentry. See [Privacy](docs/product/PRIVACY.md) and [Security](SECURITY.md).

## Build and test

Requirements: JDK 17, Android SDK 37, Python 3.13, and `uv`.

```bash
./gradlew qualityCheck
./gradlew :apps:android:assembleDebug :apps:wear:assembleDebug
```

Backend tests require a disposable PostgreSQL database. If `TEST_DATABASE_URL` is unset, the
database tests fail immediately with `TEST_DATABASE_URL is not set; database tests need
PostgreSQL` (they are not skipped). Local recipe with Docker, matching CI (`postgres:16`):

```bash
docker run -d --name aidm-test-pg -e POSTGRES_USER=aidm -e POSTGRES_PASSWORD=aidm-local-only \
  -e POSTGRES_DB=aidm_test -p 5432:5432 postgres:16
export TEST_DATABASE_URL=postgresql+psycopg://aidm:aidm-local-only@localhost:5432/aidm_test
```

Then run the checks:

```bash
cd backend
uv sync --locked --extra dev
export TEST_DATABASE_URL=postgresql+psycopg://USER:PASSWORD@localhost:5432/aidm_test
uv run pytest
uv run ruff check .
uv run ruff format --check .
uv run mypy app tests
```

For local API development, configure `DATABASE_URL` and any object-storage settings from `.env.example`, then run:

```bash
uv run alembic upgrade head
uv run uvicorn app.main:app --reload
```

## Repository layout

- `apps/android` — Android phone app
- `apps/wear` — Wear OS controller
- `core/recording` — pure Kotlin recording state machine and policies
- `backend` — FastAPI service, PostgreSQL models/migrations, and worker
- `website` — static product and download page
- `docs/adr` — architecture decisions

## Project guides

- [Delivery plan](TODO.md)
- [Architecture and edge cases](docs/architecture/EDGE_CASES.md)
- [Quality gates](docs/quality/QUALITY_GATES.md)
- [Deployment](docs/operations/DEPLOYMENT.md)
- [Privacy](docs/product/PRIVACY.md)
- [Contributing](CONTRIBUTING.md)

The project follows the phase gates in `TODO.md`. Do not enable cloud processing or claim all-day capture readiness until its review and measurement gates pass.
