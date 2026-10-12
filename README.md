# AI Daily Memory

AI Daily Memory is an Android and Wear OS project for capturing a day locally and turning it into searchable, evidence-backed memory. The phone records and stores audio; the watch controls the phone and displays acknowledged state.

## Current status

- Android capture, session controls, encrypted local segments, recovery, and on-device processing are implemented.
- Wear OS provides remote controls and reflects phone-confirmed state.
- The backend provides account sign-in, scoped tokens, uploads, transcript delivery, sync, and daily reports.
- Cloud transcription and structured extraction are disabled by default. Uploads are refused until those providers are deliberately enabled after privacy, consent, cost, and provider-term reviews.
- Release readiness still requires physical phone/watch accessibility and endurance testing. This repository is not a production release declaration.

## Privacy

Capture is visible and can be paused or stopped. Audio is kept on the phone first. Cloud processing and storage require a user-selected mode; raw cloud audio is temporary. Error monitoring is configured to discard messages, request data, user context, and breadcrumbs before sending exception diagnostics to Sentry. See [Privacy](docs/product/PRIVACY.md) and [Security](SECURITY.md).

## Build and test

Requirements: JDK 17, Android SDK 37, Python 3.13, and `uv`.

```bash
./gradlew qualityCheck
./gradlew :apps:android:assembleDebug :apps:wear:assembleDebug
```

Backend tests require a disposable PostgreSQL database:

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
