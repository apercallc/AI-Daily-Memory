# AI Daily Memory — Delivery TODO

This file turns the architecture in README.md into an executable delivery plan.

## Delivery rules

- Ship in phases; do not skip architecture gates.
- Prefer stable, production-ready dependencies over alpha/beta libraries unless an ADR justifies the exception.
- Keep recording offline-first and independent of backend availability.
- Preserve immutable transcript evidence; derived AI output is always correctable.
- Do not add infrastructure without a measured need.
- Every production change requires tests, observability, security review appropriate to the risk, and documentation.
- Every phase must have explicit exit criteria before the next phase becomes the priority.

Checkboxes track user-facing deliverables or phase gates. Indented lines under a checkbox are its
acceptance criteria, not separate projects. Future-phase work stays open until its dependencies pass.

Checked items are trimmed to a one-line summary; the full build evidence for each lives in its
commit (`git log --oneline -- TODO.md` plus the referenced source/doc files) and, where notable,
in CHANGELOG.md. A checked item keeps an indented note only when it left genuine follow-up work
that isn't tracked by its own checkbox elsewhere — never delete those notes, they're the one
record that the gap isn't closed.

Phone and Wear OS acceptance, endurance, battery, and fault measurements are release sign-off, tracked outside this delivery TODO in `docs/performance/PHASE0_CAPTURE_MEASUREMENTS.md` and `docs/quality/QUALITY_GATES.md`. Do not claim capture reliability or production readiness without that evidence.

ADR-0020 and ADR-0026 establish on-phone transcription and notes-only sync. The one-time
transcription model download is optional; recordings stay on the phone while it is unavailable.
Audio only leaves the phone when the user explicitly selects their own provider. Preserve legacy
server flows only for existing data; do not add new audio upload or operator-funded inference paths.

ADR-0028 resolves the product direction: the product is free, users may use free on-device
models or their own provider accounts, and the project does not pay model costs on users' behalf.
Remove dormant billing and operator-funded inference paths before production; abuse limits remain
availability controls, not paid-plan metering.

# Phase 0 — Capture reliability (closed)

Goal: prove reliable Android capture before building AI infrastructure.

Phase 0 hardware acceptance (endurance/battery/fault measurements) is intentionally not tracked
as a TODO checkbox — see `docs/performance/PHASE0_CAPTURE_MEASUREMENTS.md` for that sign-off.

## Cross-surface compatibility

- [x] Implement responsive web layouts and adaptive Android tablet navigation

# Phase 1 — On-device processing + notes sync (closed)

## API foundation

- [x] Generate and verify the versioned OpenAPI document in CI (policy: docs/api/VERSIONING.md)
- [x] Reject breaking API changes against the base branch's contract snapshot

## Transcription

Transcription quality testing with noisy/distant speech and multiple speakers is outside this software TODO and remains part of release validation.

Speech gating remains deferred until measured evidence supports it; retain complete audio and do not suppress ranges without that evidence (ADR-0022).

- [x] Give users a high-fidelity option and truthful speech/capture status

## Optional notes sync (ADR-0020 / ADR-0026)

ADR-0026 removes storage modes and custom server addresses from the phone. Sync is an optional
phone-to-hosted-server copy of notes and never sends audio; the phone remains the source of truth.

- [x] Show the last successful notes sync and clearly identify failed or pending notes; offer a manual retry if automatic retries do not resolve them
- [x] Warn during onboarding and settings that unsynced memory exists only on the phone and can be lost with the phone

### Phase 1 exit gate

- [x] Every captured segment reaches a visible processing outcome.
- [x] Reprocessing and sync retries are idempotent; local transcripts remain until user deletion.

# Phase 2 — Daily timeline + structured memory

## Event grouping

- [x] Group short notes and long meetings into useful, correctable events

## Structured extraction (ADR-0020 / ADR-0026)

- [x] Produce evidence-backed facts and highlights through local processing or an explicitly
  user-configured provider; keep transcript-only use functional and never use operator-funded inference

## Human correction

- [x] Let users supersede old decisions and rank confirmed corrections in future retrieval

## Daily summary / daily report

- [x] Generate reports at bedtime and deliver them at wake time from structured memory + evidence
- [x] Add an in-app report screen and share reports as Markdown through the Android share sheet
- [x] Keep reports grounded and bound processing context

## Owner voice attribution (deferred, not a launch requirement)

ADR-0016's hybrid upload design conflicts with ADR-0026's phone-only processing. Do not implement
it. Reconsider only after a new ADR and biometric legal/consent review; never store another person's
voiceprint.

## AI evaluation

- [ ] Create human-reviewed gold dataset
  - `ExtractionGoldDataset` (core/recording, pure/tested) defines the dataset shape and seeds it
    with cases covering every `MemoryItem` kind plus deliberate negative cases (a transcript that
    should extract nothing — a false-positive task/commitment is a high-severity case). Every
    case is explicitly `reviewed = false`: an agent session drafted the starting shape, not the
    ground truth. Stays open until a human reviews each case and flips that flag — see
    `docs/product/AI_EVALUATION.md`.
- [ ] Measure precision/recall for decisions, explicit tasks, commitments, and ideas; treat false
  positive tasks and commitments as high-severity failures
  - `ExtractionEvaluator` (core/recording, pure/tested) scores predicted vs. gold items by kind;
    `GoldDatasetMeasurementRunner` (`apps/android`, a manually-triggered JUnit test guarded by
    env vars so it never runs in CI/qualityCheck) can call a configured provider against every
    reviewed gold case and print the score. Stays open because no case is reviewed yet and no one
    has actually run it against real money (`docs/product/AI_EVALUATION.md` step 2).
- [ ] Evaluate prior open action items as extraction context; keep it only if it improves
  follow-up accuracy without more false positives
  - `PriorItemsContext` (core/recording, pure/tested) is wired into `ApiKeyExtractionProvider` as
    an optional parameter, `null` by default, so today's prompt is unchanged. Stays open and
    unwired-by-default until the A/B comparison in `docs/product/AI_EVALUATION.md` step 3 shows
    it helps — that needs a human reviewer to author a prior-open-items fixture dimension on the
    gold dataset first, which doesn't exist yet.
- [ ] Add regression tests for every confirmed extraction bug
  - No confirmed extraction bug is on record as of this pass. `docs/product/AI_EVALUATION.md`
    documents adding a future confirmed bug as a new `ExtractionGoldDataset.GoldCase` so it
    becomes permanent dataset coverage instead of a one-off fix.

### Phase 2 exit gate

- [ ] Timeline surfaces useful information, every derived item traces to evidence, and corrections
  never mutate original evidence.
  - Evidence traceability and correction immutability are structurally in place. Not yet met:
    "useful information" is an extraction-quality claim, and the AI evaluation items above (gold
    dataset, precision/recall) that would substantiate it are still open — this gate stays open
    until they close.

# Phase 3 — Wear OS

- [x] Session duration

### Phase 3 exit gate

- [ ] Watch controls phone reliably during normal daily use.
  - Not verifiable from an agent session: needs sustained use on physical Wear hardware (ADR-0008 /
    the "no infrastructure or hardware claim without measured evidence" rule), not a desk check.
- [x] UI never claims a command succeeded before phone acknowledgement.

# Phase 4 — Recall engine

## Time

- [x] Preserve timestamp provenance through sync and recall, with stable ordering across clock changes
- [x] Resolve relative dates and dayparts across daylight-saving and travel changes

## Retrieval

Name mentions remain text evidence; never infer speaker identity from a name appearing in a transcript.

- [x] Deliver bounded, evidence-backed recall using structured and lexical search

## Recall evaluation

- [ ] Build a known-day recall benchmark and add regression tests for confirmed failures
  - Measure Recall@K, ranking, temporal accuracy, evidence-source accuracy, and
    insufficient-evidence abstention.
  - Done: `app/recall_evaluation.py` + `tests/test_recall_benchmark.py` — a known-day benchmark
    (13 live `/v1/ask` questions, `mean_recall_at_k == 1.0`, `abstention_accuracy == 1.0`,
    deterministic calendar-boundary coverage for "last week"/"last month"). See
    `docs/product/RECALL_BENCHMARK.md`.
  - Still open: a human-curated set of realistically-worded, ambiguous real-world questions —
    where judging "good enough" ranking is a preference call, not a deterministic check — does
    not exist yet.

### Phase 4 exit gate

- [ ] Known benchmark questions reliably retrieve the correct evidence without vector search.
  - `tests/test_recall_benchmark.py` demonstrates this for its thirteen deterministic cases.
    Stays open because it does not yet cover realistic/ambiguous real-world question variety
    (deliberately deferred, see `docs/product/RECALL_BENCHMARK.md`).

# Phase 5 — CLI (closed)

### Phase 5 exit gate

- [x] CLI returns evidence-backed results through the public API.

# Phase 6 — Conditional: semantic recall

Do not implement semantic retrieval unless the Phase 4 benchmark shows a material gap in lexical
and structured recall. If evidence supports it, add a scoped task and review first; until then this
phase does not block launch and no embedding or vector-index infrastructure is needed.

# Phase 7 — Long-term memory (closed)

- [x] Add optional durable summaries and project memory for recurring commitments and relationships;
  use local processing or a provider the user explicitly configured
- [x] Consolidate and supersede memories while supporting reprocessing by extraction/schema version
  - Still open: there is no in-app action that actually *triggers* reprocessing of an
    already-COMPLETED session (e.g. after switching extraction providers) — reprocessing is now
    data-safe whenever it happens, but nothing in the UI invokes it yet.
- [x] Provide retention controls and user-visible memory correction/deletion

# Phase 8 — Production hardening

## Privacy / legal / abuse resistance

- [ ] Complete the release privacy and data-rights package
  - Provide consent guidance and a jurisdiction/workplace-policy disclaimer.
  - Complete a threat model, privacy data-flow diagram, data inventory/classification, and
    retention/deletion matrix.
  - Provide user-initiated export of synced notes, transcripts, events, highlights, memory items, and
    daily summaries (JSON + JSONL), reconciled against the data inventory.
  - Include retained raw audio only in an on-device export; audio never uploads to the server.
  - Deliver large server-side exports through scoped, short-TTL signed URLs, never inline in the API response.
  - Rate-limit and make export requests idempotent; re-requests within TTL must not regenerate.
  - Exclude export from third-party/API-key scopes by default; keep it self-service only.
  - Done: `docs/product/DATA_INVENTORY.md`, `docs/product/RETENTION_AND_DELETION.md`,
    `docs/product/DATA_FLOW.md`, `docs/security/THREAT_MODEL.md`; consent/jurisdiction drafts at
    `website/privacy.html` / `website/terms.html`; on-device raw-audio export
    (`RetainedAudioExporter`); `GET /v1/export` via scoped short-TTL signed URL, TTL-idempotent,
    JSON and JSONL formats.
  - Still open: a lawyer's review of the privacy/terms drafts — outside what an agent session can do.

## Security

- [x] Remove dormant monetization and operator-funded inference paths (ADR-0028)
- [x] Verify the release security controls

## Reliability / operations

- [ ] Demonstrate production monitoring and recovery readiness
  - Define service-level indicators and alert on ingestion failures, processing backlog, and
    provider failures.
  - Monitor database backups and pass a disaster-recovery test.
  - Pass load tests and chaos/fault tests for worker retry behavior.
  - Done: deleted-account purge now dead-letters after `MAX_ACCOUNT_PURGE_ATTEMPTS` with operator
    CLI recovery (`app/retention.py`, migration 0015); `SENTRY_DSN` is set in production and
    verified live (confirmed via the Sentry API and a clean post-deploy health check); an uptime
    watchdog cron job polls `/v1/health`+`/v1/ready` every 10 minutes and alerts only on real
    failure (lives outside this repo — see `docs/product/LAUNCH_GAPS.md`); real concurrent-worker
    fault-injection coverage added (`tests/test_jobs.py`,
    `test_concurrent_workers_never_double_claim_or_lose_a_job`); a real disaster-recovery
    restore drill ran 2026-10-18 — a production backup dump restored into a disposable
    `postgres:18-alpine` container with all 22 tables, `alembic_version` at the repo's actual
    head (0017), and real row data intact (see `docs/product/LAUNCH_GAPS.md`); backend alert
    rules ("issue first seen", "issue seen >10x/hour") are live on `ai-daily-memory-backend`.
  - Still open, each needs an operator/outside-account decision, not code: formal SLI thresholds;
    the same two alert rules on the `ai-daily-memory-android` and `ai-daily-memory-web` Sentry
    projects (this Sentry plan restricts alert-rule CRUD to its web UI; web already has real
    events and zero alerting today — do it first); a *recurring* backup-monitoring cadence (the
    2026-10-18 restore drill is a one-time proof, not ongoing monitoring); load/chaos tests for
    worker retry behavior (the legacy upload-processing worker isn't deployed at all today — Gate
    B providers disabled, ADR-0026 — so there is no live worker to test; revisit only if that gate
    reopens).

# Phase 10 — Public launch: free user API and data portability

Do not begin until Phase 5 (CLI) and Phase 8 (production hardening) have
exited. Public API access still requires strong authorization, abuse controls,
and complete data rights. The product remains free under ADR-0028.

## Public API access (user-owned clients)

- [ ] Launch versioned, abuse-monitored public API access for user-owned clients
  - Ship API keys with scopes, hashing, rotation, and rate limits; document authentication, limits,
    and error semantics.
  - Define the versioned contract and breaking-change deprecation policy.
  - Monitor per-key volume anomalies and scraping patterns; do not add paid tiers or entitlements.
  - Done: `GET/POST /v1/api-keys` (hashed, read-only `memory:read` scope by construction, capped
    at 10, create+revoke achieves rotation); `docs/api/VERSIONING.md`; `docs/api/ENDPOINTS.md`
    (corrected to drop stale `/v1/billing*` routes and `ask_model`/`billing` capability fields,
    plus the matching dead frontend code in `website/app/`).
  - Still blocked, not done: per-key volume/scraping anomaly monitoring needs a real monitoring
    decision and account, not just code (same shape as the login-anomaly gap in
    `THREAT_MODEL.md` T1). Also gated on Phase 8's Reliability item exiting (not yet).

## Data portability

- [x] Verify export completeness against the data inventory and account deletion across derived data,
  embeddings, jobs, and retained objects (SECURITY.md)

### Phase 10 exit gate

- [ ] A user can create an API client, export all their data, and delete their account through
  self-service flows, with authorization and abuse controls enforced throughout.
