# AGENTS.md — AI Daily Memory

This is the canonical rule file for **all** coding agents working in this repository
(Claude Code, Codex, Hermes, and any other). `CLAUDE.md` points here; `.claude/agents`
and `.claude/skills/` contain Claude Code implementations of the standard roles
defined at the bottom of this file — other agents should emulate those roles from
this document.

## What this project is

A personal memory system: continuous audio capture → understanding → structured
memory → trustworthy recall. It is **not** a transcription app. The differentiator
is that it can answer "What did I decide today?", "What did someone promise to
follow up on?" with evidence backing. Privacy is a product requirement, not a
feature flag: this app records people's conversations.

Because we record real conversations, two product-level rules outrank everything:

1. **Never design anything that hides recording state.** Recording indicators and
   pause/stop controls are product requirements (see SECURITY.md).
2. **Evidence is immutable; derived AI output is always correctable.** Transcripts
   are never rewritten; corrections are stored separately. Immutable is not
   undeletable: user deletion and retention remove evidence and cascade to everything
   derived from it (ADR-0004 amendment).

The commercial direction (TODO.md Phase 10): release publicly, charge a monthly
subscription, let users connect their own clients via the public REST API (ADR-0021: no MCP server), and support
self-service data export. Three more rules follow from that:

3. **Entitlements are server-authoritative.** No client — app, CLI, or third-party
   API client — gates itself into paid features. Every paid surface checks
   entitlements server-side.
4. **API keys are user-owned secrets.** Stored only as hashes, scoped read-only by
   default, rate-limited, revocable. Third-party clients never get bulk transcript
   export by default.
5. **Users own their data.** Self-service export (including raw audio while
   retention still holds, per ADR-0003) and complete account deletion are rights,
   not upsells — and third-party API scopes cannot weaken them.

## Repository map

| Path | What it is |
| --- | --- |
| `apps/android` | Phone app. Compose Material 3, Kotlin, offline capture. |
| `apps/wear` | Wear OS controller. Wear Compose Material 3. Controller only — it shows **acknowledged phone state**, never assumes a command succeeded. |
| `core/recording` | Pure Kotlin JVM recording state machine. No Android dependencies. |
| `backend` | FastAPI + Pydantic, Python 3.13, uv-managed. |
| `interfaces/cli` | Future API client. Must use the public API, never the DB directly. |
| `docs/adr` | Architecture Decision Records. Read before changing an architectural decision. |
| `docs/architecture/` | Architecture review, edge-case catalog (`EDGE_CASES.md`), performance & scale plan. Update the catalog when you handle a new edge case. |
| `docs/quality/QUALITY_GATES.md` | Release-quality standards. |
| `docs/performance/` | Performance evidence process. |
| `TODO.md` | The executable delivery plan with phase exit gates. **Work comes from here.** |
| `SECURITY.md`, `CONTRIBUTING.md`, `README.md` | Policies and full architecture. |

## How work is chosen

- **Phase discipline:** implement the current phase in `TODO.md`. Do not implement
  later-phase infrastructure early — each phase has explicit exit criteria that must
  be met before the next phase becomes the priority.
- **Gate discipline:** architecture gates A–G (README.md) control adding continuous
  capture, cloud processing, structured memory, recall, semantic search, approximate
  vector indexes, and infrastructure. Don't cross a gate speculatively.
- **Dependencies:** prefer stable, production-ready versions. An alpha/beta library
  requires an ADR justifying the exception.
- **No infrastructure without a measured need.** Do not add Redis, queues, or
  services "to be safe."
- **ADRs:** any decision that reverses a recorded one (offline-first, temporary raw
  audio, evidence-backed memory, single query service, client IDs/time model,
  append-safe encrypted segments, watch protocol + shared applicationId, backend as sole
  DB client, mic service lifecycle, markers, daily rhythm, audio retention, where memory
  lives, speech gating, owner voice, no speech retention, transcription provider, structured
  extraction, on-device processing, REST-only integration, offline-or-hosted server, own server optional, on-phone transcription with notes-only sync — ADR-0001…0027) requires a new ADR first, not a
  silent change.

## Build and verify

Gradle wrapper is bootstrapped (Gradle 9.7.1) — use `./gradlew` for all builds.

```bash
# Full pre-merge gate (unit tests, coverage, Android lint for all modules)
./gradlew qualityCheck

# Per-module
./gradlew :core:recording:check          # state machine tests + JaCoCo ≥90% line & branch
./gradlew :apps:android:testDebugUnitTest
./gradlew :apps:android:lintDebug
./gradlew :apps:android:assembleDebug

# Backend (Python 3.13, uv)
cd backend
uv sync --locked --extra dev
uv run ruff check .
uv run ruff format --check .
uv run mypy app tests
uv run pytest                        # coverage fail-under 90, branch coverage on
uv run pip-audit
```

`qualityCheck` must pass before a change is reported done. Lint is configured
`abortOnError = true` and `warningsAsErrors = true` — treat every lint warning as a
build failure, not a suggestion.

## Kotlin / Android standards

- Follow official Kotlin conventions and idiomatic Compose.
- **Pure functions for state transitions and business rules.** The recording state
  machine in `core/recording` stays deterministic, side-effect-free, and Android-free.
  UI and services dispatch events into it; they never mutate state ad hoc.
- **Unidirectional data flow** in Compose: state down, events up. No global mutable
  state, no hidden side effects in composables.
- Structured concurrency: scoped coroutines, no `GlobalScope`, no blocking I/O on the
  main thread.
- Small interfaces at real boundaries — not speculative abstractions. Provider SDK
  calls (transcription, AI) live behind one seam, never spread across the codebase.
- Immutable data by default (`val`, `data class`, read-only collections).
- Put decision logic in `core/recording` as pure, tested code (reducers, policies,
  presenters). Android classes wire and render; they don't decide. `core/recording`
  compiles with `allWarningsAsErrors`.
- Error handling: **no silent failure.** No catch-all exception swallowing. Failures
  surface to capture health, logs, or the operator — never vanish.
- Package root is `com.apercallc.aidailymemory`. Match the existing module layout
  (`apps/*` use `src/main/java`, `core/*` use `src/main/kotlin`).
- Dependency versions go in `gradle/libs.versions.toml` — never hardcode versions in
  module build files. New repositories are forbidden
  (`RepositoriesMode.FAIL_ON_PROJECT_REPOS` is intentional).

### Modern design standards (Compose)

- **Material 3 only** (`androidx.wear.compose:compose-material3` on Wear — the Wear
  component set, never the phone set).
- Use **semantic design tokens**, not raw colors: define/use tokens for recording,
  paused, warning, error, success before reaching for `Color(0xFF...)`.
- Theme supports light/dark; dynamic color where appropriate.
- Accessibility is part of done, not a follow-up:
  - ≥48dp touch targets
  - content descriptions for non-text controls (a recording indicator is non-text)
  - font scaling to at least 200%
  - TalkBack-testable critical flows
- Edge-to-edge app shell; respect display cutouts.
- The watch shows **acknowledged phone state**. Design every Wear screen around the
  actual recorded state, never assumed state.

### Android performance standards

- Recording is a long-lived foreground service: battery, memory, and jank are
  correctness concerns, not polish. Don't hold wake locks longer than needed; don't
  leak listeners or coroutines on session end.
- Performance claims require evidence recorded per `docs/performance/` — measure
  before optimizing and attach numbers to the PR.
- Main thread stays jank-free: heavy work off the main thread; Compose recomposition
  kept narrow (stable inputs, remember scoped correctly).
- Budgets and release-quality monitoring targets are defined in
  `docs/quality/QUALITY_GATES.md`. Internal targets must be materially better than
  Play bad-behavior thresholds.

## Python / backend standards

- `mypy --strict` is the floor — every line of new code type-checks.
- Ruff rules `E,F,I,B,UP,SIM,S,BLE,ASYNC,C4,N,PERF,PT,RET,RUF` (`S` = bandit-style
  security checks; `BLE` = no blind `except Exception` unless it logs or re-raises).
  Line length 100. mypy strict runs on `app` **and** `tests`.
- Errors use the shared envelope in `app/errors.py`; never return ad-hoc error dicts.
  Validation errors report field locations, never the rejected values.
- Log through `logging.getLogger("app....")` with a constant event name as the message
  and data in `extra=`. The JSON formatter logs the template only (never `%s` args),
  redacts sensitive field names (nested too), and logs exceptions as type + frame
  locations only.
- FastAPI: request/response models are Pydantic; never return raw dicts from the
  DB; never trust client-supplied IDs without ownership checks.
- Idempotency: every processing stage has an idempotency key; retries use bounded
  exponential backoff; failures reach a terminal dead-letter state with an
  operator-visible reason.
- Secrets come from the environment (`pydantic-settings`) — see `backend/.env.example`
  for the pattern. Never commit real `.env`.

## Security and privacy rules

- **No secrets in the repo.** No API keys, tokens, passwords, or recorded product
  data — ever, in any file, including tests and docs.
- **Least privilege everywhere:** scoped auth per user-owned resource; signed URLs
  with scope + TTL; object-storage path isolation; non-root containers; least-privilege
  DB roles.
- **Input validation at boundaries:** upload protocol validates size/type; API
  endpoints validate with Pydantic; watch commands validate against the state machine.
- Recording consent and indicators are product requirements — see the two product-level
  rules at the top of this file.
- Don't open public issues for suspected vulnerabilities involving private data,
  auth, crypto, secrets, or data exposure — report privately (SECURITY.md).
- New dependencies: check for known vulns (`pip-audit` for Python; Dependabot watches
  the rest). An unsafe dependency is a blocker, not a TODO.

## Testing standards

- `core/recording` and backend application code: **≥90% line coverage; safety-critical
  state machine / idempotency / retry / authorization logic ≥90% branch coverage.**
  This is enforced by JaCoCo and pytest `--cov-fail-under=90` — the build fails below it.
- Test the legal *and* illegal transitions of every state machine (see
  `RecordingStateMachineTest` for the pattern).
- Coverage is not a substitute for integration, UI, fault, or endurance testing —
  unit coverage is the floor, not the goal.
- Every bug fix includes a regression test that fails without the fix.

## Git and PR conventions

- Branches: `feat/<name>`, `fix/<name>`, `chore/<name>`, `docs/<name>` — short-lived.
- Conventional Commit prefixes: `feat:`, `fix:`, `test:`, `refactor:`, `perf:`,
  `security:`, `docs:`, `build:`, `ci:`.
- Keep PRs small enough to review. The PR template checklist (tests, coverage, lint,
  verification, risk, sensitive-data check, docs) must be answered honestly.
- A "Definition of Done" for every production feature: tests, observability,
  security review appropriate to risk, and documentation.

## Standard reviewer roles

Every agent doing non-trivial work here plays one or more of these roles. The
Claude Code implementations live in `.claude/agents/` (with matching skills in
`.claude/skills/`); Codex and Hermes agents should adopt the same review checklists
when asked to review.

| Role | Reviews for |
| --- | --- |
| `android-reviewer` | Kotlin/Compose quality, UDF, state-machine purity, M3/Wear design and accessibility |
| `backend-reviewer` | Python/FastAPI correctness, typing, idempotency, ownership checks |
| `security-reviewer` | Privacy, secrets, permissions, recording-state visibility, threat coverage |
| `performance-reviewer` | Battery/memory/jank/allocations, backend query efficiency, evidence for perf claims |
| `test-quality-reviewer` | Coverage gates, transition tests, regression tests, test realism |

Before declaring any task done: run `qualityCheck` (and the backend checks when
backend code changed), or state plainly which gates you ran and which you skipped.