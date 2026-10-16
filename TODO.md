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

Phone and Wear OS acceptance, endurance, battery, and fault measurements are release sign-off, tracked outside this delivery TODO in `docs/performance/PHASE0_CAPTURE_MEASUREMENTS.md` and `docs/quality/QUALITY_GATES.md`. Do not claim capture reliability or production readiness without that evidence.

ADR-0020 and ADR-0026 establish on-phone transcription and notes-only sync. The one-time
transcription model download is optional; recordings stay on the phone while it is unavailable.
Audio only leaves the phone when the user explicitly selects their own provider. Preserve legacy
server flows only for existing data; do not add new audio upload or operator-funded inference paths.

ADR-0028 resolves the product direction: the product is free, users may use free on-device
models or their own provider accounts, and the project does not pay model costs on users' behalf.
Remove dormant billing and operator-funded inference paths before production; abuse limits remain
availability controls, not paid-plan metering.

# Phase 0 — Capture reliability

Goal: prove reliable Android capture before building AI infrastructure.

Physical-device evidence remains a release sign-off, not a software delivery task. Software phases may proceed; release readiness still requires the evidence in `docs/performance/PHASE0_CAPTURE_MEASUREMENTS.md`.

Phase 0 hardware acceptance is intentionally not tracked as a TODO checkbox. Automated code tests and quality gates remain required for software changes.

## Cross-surface compatibility

- [x] Implement responsive web layouts and adaptive Android tablet navigation
  - Web app layouts support phone, tablet, and desktop widths; the notes library switches to a
    folder sidebar at tablet widths.
  - Android uses bottom navigation below 600 dp and a labeled navigation rail with a centered,
    960 dp maximum content width at tablet widths.

# Phase 1 — On-device processing + notes sync

## API foundation

- [x] Generate and verify the versioned OpenAPI document in CI (policy: docs/api/VERSIONING.md)
- [x] Reject breaking API changes against the base branch's contract snapshot

## Transcription

Transcription quality testing with noisy/distant speech and multiple speakers is outside this software TODO and remains part of release validation.

Speech gating remains deferred until measured evidence supports it; retain complete audio and do not suppress ranges without that evidence (ADR-0022).
- [x] Give users a high-fidelity option and truthful speech/capture status
  - Offer the free on-device model or a user-configured provider. Distinguish not recording, no
    speech detected after complete analysis, waiting for local processing, and unavailable or
    failed processing. When no speech is detected, suggest checking microphone placement without
    claiming a cause.

## Optional notes sync (ADR-0020 / ADR-0026)

ADR-0026 removes storage modes and custom server addresses from the phone. Sync is an optional
phone-to-hosted-server copy of notes and never sends audio; the phone remains the source of truth.

- [x] Show the last successful notes sync and clearly identify failed or pending notes; offer a manual
  retry if automatic retries do not resolve them
- [x] Warn during onboarding and settings that unsynced memory exists only on the phone and can be
  lost with the phone

### Phase 1 exit gate

- [x] Every captured segment reaches a visible processing outcome. After the one-time model download,
  the on-device default works offline; before then, audio remains local and visibly pending.
- [x] Reprocessing and sync retries are idempotent; local transcripts remain until user deletion.
  Deletions of data already synced to the hosted server remain queued and propagate after reconnect,
  including when sync has since been disabled.

# Phase 2 — Daily timeline + structured memory

## Event grouping

- [x] Group short notes and long meetings into useful, correctable events
  - Avoid over-fragmented 30-second memories and support event-boundary corrections.
  - `EventGrouper` (core/recording, pure/tested) clusters closely spaced short sessions into one
    event and always keeps a long session (a meeting) standing alone. The timeline shows a
    merge/split control between every two adjacent sessions; the person's choice is stored per
    session (`session_event_override`, migration 11->12) and always wins over the automatic
    rule, without changing the automatic algorithm itself.

## Structured extraction (ADR-0020 / ADR-0026)

- [x] Produce evidence-backed facts and highlights through local processing or an explicitly
  user-configured provider; keep transcript-only use functional and never use operator-funded inference
  - `ApiKeyExtractionProvider` (apps/android, tested) calls the user's own OpenAI-compatible
    endpoint with their own key, chunked through `TranscriptChunker` so one long session never
    becomes a single unbounded prompt; every item is validated (`ProcessingValidation`) before
    it reaches the memory table. With no provider configured, `AppContainer` passes `null` and
    `ProcessingPipeline` stores the transcript alone (`TRANSCRIPT_ONLY`) — summaries are strictly
    opt-in, never a fallback to an operator-funded model. `StubExtractionProvider` is an
    unreachable placeholder that only satisfies the type when settings are incomplete.

## Human correction

- [x] Let users supersede old decisions and rank confirmed corrections in future retrieval
  - Supersede is in: a correction is stored apart from the extracted item (new
    `memory_item_correction` table, never rewriting evidence or the extracted row), shown in
    place of the original wherever items are displayed, with a "Corrected" label. Ranking
    corrections ahead of originals in a dedicated recall engine's results is Phase 4 scope —
    there is no recall surface yet to rank within; the daily timeline (the only retrieval
    surface that exists today) already always prefers the correction.

## Daily summary / daily report

- [x] Generate reports at bedtime and deliver them at wake time from structured memory + evidence
  - Connect existing report timing calculations to scheduled processing and wake-time delivery;
    show partial status while audio is pending.
  - Bedtime (rhythm END_DAY) now kicks off the on-device processing job immediately instead of
    waiting for the next opportunistic charging check; wake time (the existing morning reminder)
    now also delivers the most recent generated report as a notification. `DailyReport` carries
    a `status` (`complete`/`partial`, core/recording), computed from whether every session
    known to belong to that memory day has reached a terminal processing outcome; the report
    screen, the shared Markdown, and the wake notification all state plainly when a day is only
    partially processed instead of implying it is final.
- [x] Add an in-app report screen and share reports as Markdown through the Android share sheet
  - Each day in the timeline has a "View report" action into a read-only report screen (headline,
    summary, coverage, items by kind); its Share action renders the same report as Markdown and
    hands it to the Android share sheet. Days without a generated report show a truthful empty
    state instead of a blank screen.
- [x] Keep reports grounded and bound processing context
  - Avoid summary-of-summary drift; add chunking/truncation for very long days before generating a
    report. Headlines and deterministic summaries already exist; keep generated content tied to
    evidence and avoid claiming unsupported summary quality.
  - `TranscriptChunker` (core/recording, pure/tested) bounds one extraction call's transcript so
    a long session never becomes a single unbounded prompt; chunk results are concatenated, not
    re-summarized, and line indices are preserved so evidence citations stay correct.
    `ReportBuilder`'s per-kind and whole-string caps keep the generated summary itself bounded
    even when a day produces far more items than a headline should ever spell out.

## Owner voice attribution (deferred, not a launch requirement)

ADR-0016's hybrid upload design conflicts with ADR-0026's phone-only processing. Do not implement
it. Reconsider only after a new ADR and biometric legal/consent review; never store another person's
voiceprint.

## AI evaluation

- [ ] Create human-reviewed gold dataset
- [ ] Measure precision/recall for decisions, explicit tasks, commitments, and ideas; treat false
  positive tasks and commitments as high-severity failures
- [ ] Evaluate prior open action items as extraction context; keep it only if it improves follow-up
  accuracy without more false positives
- [ ] Add regression tests for every confirmed extraction bug

### Phase 2 exit gate

- [ ] Timeline surfaces useful information, every derived item traces to evidence, and corrections
  never mutate original evidence.
  - Evidence traceability and correction immutability are structurally in place (every
    `MemoryItem` carries `evidenceSegmentIndices`/`evidenceEpochMs`/`quote`; corrections live in
    `memory_item_correction`, never touching the extracted row). Not yet met: "useful
    information" is an extraction-quality claim, and the "AI evaluation" items above (gold
    dataset, precision/recall) that would substantiate it are still open — this gate stays open
    until they close, not stretched ahead of them.

# Phase 3 — Wear OS

- [x] Session duration
  - The watch shows a live elapsed-time readout ("12 min" / "1h 5m") beside the existing session
    start time, for any live phone-acknowledged session (same gate as the start-time display);
    it ticks on its own 30s timer so it keeps advancing between phone snapshots, and clamps to
    zero so watch/phone clock drift never shows a negative duration. `WearPresentation.
    sessionDurationMs` is pure and unit-tested; nothing here claims a command succeeded before
    phone acknowledgement, matching the existing exit-gate rule below.

### Phase 3 exit gate

- [ ] Watch controls phone reliably during normal daily use.
  - Not verifiable from this session: needs sustained use on physical Wear hardware (ADR-0008 /
    the "no infrastructure or hardware claim without measured evidence" rule), not a desk check.
- [x] UI never claims a command succeeded before phone acknowledgement.

# Phase 4 — Recall engine

## Time

- [x] Preserve timestamp provenance through sync and recall, with stable ordering across clock changes
  - Phone capture already stores UTC, source timezone, local date, and monotonic time; verify the
    fields needed by recall survive sync without deriving dates from server-local time.
  - The notes-sync path (`/v1/sync`, what the shipped Android app uses) already sends the
    phone's own computed memory day directly — nothing to fix there. The separate `full_cloud`
    raw-audio path (`/v1/uploads` + server-side extraction, for any REST client that chooses
    that storage mode) did not: Postgres stores `captured_at` as a bare instant and drops
    whatever UTC offset a client originally attached, so `memory_day` was always the UTC
    calendar date of a session's first clip, which silently shifts late-evening/early-morning
    sessions to the wrong local day. Uploads now carry an optional `source_timezone` (IANA zone
    id, migration 0013); `extraction.memory_day_for` uses it when present and falls back to the
    previous UTC-derived day when absent, so older clients are unaffected.
- [x] Resolve relative dates and dayparts across daylight-saving and travel changes
  - Support today/yesterday/last week, morning/afternoon/evening, DST transitions, and timezone
    changes while traveling.
  - Already implemented in `app/recall.py` (`resolve_range`): today/yesterday/this-or-last
    week/month/last-N-days all resolve against the *caller's own* `today` (the phone's local
    date, per `AskRequest.today`), clamped to within a day of the real UTC date so a traveling
    phone's clock can shift what "today" means without opening a wider window than intended.
    Dayparts (morning/afternoon/evening) are recognized as day-scoped phrasing, matching the
    whole day: `MemoryItem` is day-granularity only, so there is no finer window to resolve
    them into without a schema change this phase doesn't need.

## Retrieval

Name mentions remain text evidence; never infer speaker identity from a name appearing in a transcript.

- [x] Deliver bounded, evidence-backed recall using structured and lexical search
  - Use structured SQL first, PostgreSQL full-text search, date/type filters, and evidence expansion
    around winning events.
  - Bound candidate sets, return evidence references, and abstain when evidence is insufficient.
  - Already implemented: `POST /v1/ask` (`app/api/ask.py`) resolves a date window and item-kind
    hint, runs PostgreSQL full-text search first and falls back to a bounded structured
    date/kind query when the question names a time or kind but no useful keywords; every
    candidate set is capped (`MAX_CANDIDATES`), every answer carries the evidence items behind
    it, and the response abstains outright when nothing matches rather than guessing.

## Recall evaluation

- [ ] Build a known-day recall benchmark and add regression tests for confirmed failures
  - Measure Recall@K, ranking, temporal accuracy, evidence-source accuracy, and
    insufficient-evidence abstention.
  - Blocked on a human-reviewed benchmark (same constraint as AI evaluation's gold dataset
    above): an agent session should not author the "known-good" answers it would then grade
    itself against.

### Phase 4 exit gate

- [ ] Known benchmark questions reliably retrieve the correct evidence without vector search.
  - Blocked on the same benchmark as "Recall evaluation" above.

# Phase 5 — CLI

### Phase 5 exit gate

- [x] CLI returns evidence-backed results through the public API.
  - `interfaces/cli/adm.py` (7 tests, all green) is a stdlib-only client against the real
    `/v1` API with a read-only API key: `adm ask` returns `POST /v1/ask`'s answer plus the
    `Evidence` items it cites (abstains rather than guessing when nothing matches);
    `adm day`/`search`/`decisions`/`tasks`/`commitments`/`ideas`/`questions` page through
    `GET /v1/memory/items`, the same grounded `MemoryItem` rows (never free-text), each
    showing its original extracted wording alongside any correction. No direct DB access;
    every path is the same public, scoped, rate-limited API a third-party client would use.
    (Per-item verbatim transcript quotes via `/v1/memory/items/{id}/evidence` need the
    separate `transcript:read` scope, which read-only API keys don't carry — out of scope
    for this CLI, which only ever reads `memory:read` data.)

# Phase 6 — Conditional: semantic recall

Do not implement semantic retrieval unless the Phase 4 benchmark shows a material gap in lexical
and structured recall. If evidence supports it, add a scoped task and review first; until then this
phase does not block launch and no embedding or vector-index infrastructure is needed.

# Phase 7 — Long-term memory

- [x] Add optional durable summaries and project memory for recurring commitments and relationships;
  use local processing or a provider the user explicitly configured
  - "People & commitments" on the memory timeline (`MemoryViewModel.projectMemory`,
    `ProjectMemoryBuilder` in core/recording, pure/tested) groups every still-open commitment
    and action by counterpart (falling back to owner) across every day, newest mention first —
    durable, cross-day, and collapsible, unlike the per-day timeline. Deterministic local
    aggregation over already-extracted items: no new AI call, no new provider dependency: it
    works identically whether or not summaries are configured.
- [x] Consolidate and supersede memories while supporting reprocessing by extraction/schema version
  - Fixed a real data-loss gap this closes: `MemoryItemDao.replaceForSession` always deletes and
    reinserts a session's items (fresh ids every run), which silently un-dismissed and orphaned
    corrections on any reprocessing — a known limitation the entity's own kdoc called out
    (ADR-0019). `MemoryReconciler` (core/recording, pure/tested, 11 tests) now matches a freshly
    (re)extracted item back to the one it supersedes by evidence identity (kind + evidence
    anchor + owner/counterpart/direction/due), greedily and one-to-one, and
    `ProcessingPipeline` carries the dismissal forward and repoints the correction in the same
    transaction as the replace. Covered end-to-end in `ProcessingPipelineTest` (a reworded
    re-extraction keeps its dismissal and correction; an unrelated re-extraction leaves the old
    correction inert, never misapplied).
  - Still open: there is no in-app action that actually *triggers* reprocessing of an
    already-COMPLETED session (e.g. after switching extraction providers) — the data-safety
    fix above makes reprocessing safe whenever it happens, but nothing in the UI invokes it yet.
- [x] Provide retention controls and user-visible memory correction/deletion
  - Correction/deletion already existed and were verified: `MemoryViewModel.correct`/`.dismiss`
    store a correction apart from the extracted row and hide an item without rewriting evidence
    (AGENTS.md). What was missing was a retention *control* for structured memory itself — audio
    retention (`AudioRetention`) already existed, but memory items, corrections, and daily
    reports persisted forever with no setting. Added `MemoryRetention` (core/recording, pure
    `MemoryRetentionCutoff`, tested) with 90-day / 1-year / until-deleted options (default:
    until deleted, since memory is the point of the product, unlike audio); `MemoryRetentionSweep`
    deletes corrections, then items, then daily reports past the cutoff in one transaction
    (never touches the transcript evidence itself), runs on the same app-start/session-complete
    hook as the existing audio sweep, and is exposed in Settings next to Audio.

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
  - Written and reconciled against the real schema in this pass: `docs/product/DATA_INVENTORY.md`,
    `docs/product/RETENTION_AND_DELETION.md`, `docs/product/DATA_FLOW.md`,
    `docs/security/THREAT_MODEL.md`. Consent/jurisdiction guidance already exists in
    `website/privacy.html` ("Recording other people") and `website/terms.html`, both explicitly
    marked draft pending a lawyer's review (`docs/product/LAUNCH_GAPS.md`).
  - Still genuinely open, not done: JSONL export format and including retained raw audio in
    an on-device export are not implemented. A lawyer's review of the privacy/terms drafts
    remains outside what an agent session can do.
  - Resolved in this pass: `GET /v1/export` now delivers through a scoped, short-TTL signed
    URL (`AccountExport`, migration 0016) instead of streaming inline, and reuses the same
    generated object for repeat requests within the TTL instead of regenerating — both
    sub-bullets above are now met.

## Security

- [x] Remove dormant monetization and operator-funded inference paths (ADR-0028)
  - Remove Stripe checkout/portal/webhook routes, config, UI, plan entitlements, and schema fields with a safe migration; retain transactional email and account-recovery behavior.
  - Keep web Ask retrieval-only; ensure user requests never invoke model providers using operator-owned keys, and never fall back silently to paid inference.
  - Test that abuse limits remain availability safeguards rather than paid-plan metering.
  - Deleted `app/billing.py`, `app/api/billing.py`, `app/recall.py`'s `ClaudeAnswerProvider`/
    `AnswerProvider` provider path, and the `ask_provider`/`ask_model`/`stripe_*`/`pro_*`
    settings; `app/api/ask.py` now always answers with `evidence_only_answer` (unconditional,
    not a fallback). Migration 0014 drops `users.plan`, `stripe_customer_id`,
    `plan_renews_at`, `plan_cancels_at_period_end`; `limits_for()` takes only `Settings` now
    (one tier, `daily_ask_limit`/`daily_upload_bytes_quota` — abuse/availability limits, not
    plan metering). `/v1/capabilities` no longer reports `billing`/`ask_model`. Transactional
    email/password-reset/account-recovery routes and tests are untouched
    (`tests/test_email.py`, renamed from `tests/test_email_and_billing.py`). Full backend
    suite (526 tests, 97% coverage), ruff, mypy, pip-audit, and the regenerated
    `docs/api/openapi.v1.json` all verified green.
- [ ] Verify the release security controls
  - Complete the authentication threat model and authorization tests for every user-owned resource.
  - Verify object-storage path isolation and signed URL scope/TTL tests.
  - Establish rate and abuse limits; shared edge throttling must precede horizontal scaling or
    broader public exposure.
  - Produce an SBOM for releases and run the backend container as a non-root user.
  - Non-root container: done (`backend/Dockerfile` runs as `USER app`, not root).
  - SBOM: done — CI's backend job runs `pip-audit --format cyclonedx-json` and uploads
    `backend-sbom` as a build artifact on every run (`.github/workflows/ci.yml`).
  - Authentication threat model: written (`docs/security/THREAT_MODEL.md`) — nine threats
    (token theft, cross-user access, scope escalation, forged uploads, presigned-URL abuse,
    storage-outage leakage, brute force, availability abuse, user-provider credential handling)
    each with its code-level mitigation cited, plus an explicit "Open items" list.
  - Still open: the authorization-test audit that document's own "Open items" section calls
    for (a single matrix of every endpoint × required scope × negative test, rather than the
    per-endpoint tests that already exist scattered through `backend/tests/`), object-storage
    path isolation and signed-URL scope/TTL *tests reviewed together as the named control*
    (the controls themselves — ownership checks, TTL-bounded presigned URLs — already exist
    per-endpoint, e.g. `app/api/uploads.py`'s `_owned`/`presign_put`). Shared edge throttling is
    correctly deferred: the in-process limiter is sized for the single-replica deployment that
    exists today (`docs/product/LAUNCH_GAPS.md` "Single API replica").

## Reliability / operations

- [ ] Demonstrate production monitoring and recovery readiness
  - Define service-level indicators and alert on ingestion failures, processing backlog, and
    provider failures.
  - Monitor database backups and pass a disaster-recovery test.
  - Pass load tests and chaos/fault tests for worker retry behavior.
  - Fixed in this pass: the deleted-account object-storage purge retried a failing delete
    forever with no bound (`docs/product/LAUNCH_GAPS.md` "Deleted-account purge retries
    forever on a permanent error"). `app/retention.py`'s `_purge_deleted_account_prefixes` now
    dead-letters a row after `MAX_ACCOUNT_PURGE_ATTEMPTS` with an operator-visible
    `last_error_code`/`dead_at` (migration 0015), mirroring `app/jobs.py`'s existing
    bounded-retry pattern; `python -m app.admin dead-account-purges`/`requeue-account-purge`
    give an operator the same visibility and recovery path `dead-jobs`/`requeue-job` already
    give for processing jobs. Still open: SLIs/alerting, backup monitoring + a disaster-recovery
    drill, and load/chaos tests — these need real monitoring and operations accounts/decisions,
    not just code.

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

## Data portability

- [ ] Verify export completeness against the data inventory and account deletion across derived data,
  embeddings, jobs, and retained objects (SECURITY.md)

### Phase 10 exit gate

- [ ] A user can create an API client, export all their data, and delete their account through
  self-service flows, with authorization and abuse controls enforced throughout.
