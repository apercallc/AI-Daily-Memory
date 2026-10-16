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

- [ ] Produce evidence-backed facts and highlights through local processing or an explicitly
  user-configured provider; keep transcript-only use functional and never use operator-funded inference

## Human correction

- [x] Let users supersede old decisions and rank confirmed corrections in future retrieval
  - Supersede is in: a correction is stored apart from the extracted item (new
    `memory_item_correction` table, never rewriting evidence or the extracted row), shown in
    place of the original wherever items are displayed, with a "Corrected" label. Ranking
    corrections ahead of originals in a dedicated recall engine's results is Phase 4 scope —
    there is no recall surface yet to rank within; the daily timeline (the only retrieval
    surface that exists today) already always prefers the correction.

## Daily summary / daily report

- [ ] Generate reports at bedtime and deliver them at wake time from structured memory + evidence
  - Connect existing report timing calculations to scheduled processing and wake-time delivery;
    show partial status while audio is pending.
- [x] Add an in-app report screen and share reports as Markdown through the Android share sheet
  - Each day in the timeline has a "View report" action into a read-only report screen (headline,
    summary, coverage, items by kind); its Share action renders the same report as Markdown and
    hands it to the Android share sheet. Days without a generated report show a truthful empty
    state instead of a blank screen.
- [ ] Keep reports grounded and bound processing context
  - Avoid summary-of-summary drift; add chunking/truncation for very long days before generating a
    report. Headlines and deterministic summaries already exist; keep generated content tied to
    evidence and avoid claiming unsupported summary quality.

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

# Phase 3 — Wear OS

- [ ] Session duration

### Phase 3 exit gate

- [ ] Watch controls phone reliably during normal daily use.
- [x] UI never claims a command succeeded before phone acknowledgement.

# Phase 4 — Recall engine

## Time

- [ ] Preserve timestamp provenance through sync and recall, with stable ordering across clock changes
  - Phone capture already stores UTC, source timezone, local date, and monotonic time; verify the
    fields needed by recall survive sync without deriving dates from server-local time.
- [ ] Resolve relative dates and dayparts across daylight-saving and travel changes
  - Support today/yesterday/last week, morning/afternoon/evening, DST transitions, and timezone
    changes while traveling.

## Retrieval

Name mentions remain text evidence; never infer speaker identity from a name appearing in a transcript.

- [ ] Deliver bounded, evidence-backed recall using structured and lexical search
  - Use structured SQL first, PostgreSQL full-text search, date/type filters, and evidence expansion
    around winning events.
  - Bound candidate sets, return evidence references, and abstain when evidence is insufficient.

## Recall evaluation

- [ ] Build a known-day recall benchmark and add regression tests for confirmed failures
  - Measure Recall@K, ranking, temporal accuracy, evidence-source accuracy, and
    insufficient-evidence abstention.

### Phase 4 exit gate

- [ ] Known benchmark questions reliably retrieve the correct evidence without vector search.

# Phase 5 — CLI

### Phase 5 exit gate

- [ ] CLI returns evidence-backed results through the public API.

# Phase 6 — Conditional: semantic recall

Do not implement semantic retrieval unless the Phase 4 benchmark shows a material gap in lexical
and structured recall. If evidence supports it, add a scoped task and review first; until then this
phase does not block launch and no embedding or vector-index infrastructure is needed.

# Phase 7 — Long-term memory

- [ ] Add optional durable summaries and project memory for recurring commitments and relationships;
  use local processing or a provider the user explicitly configured
- [ ] Consolidate and supersede memories while supporting reprocessing by extraction/schema version
- [ ] Provide retention controls and user-visible memory correction/deletion

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

## Security

- [ ] Remove dormant monetization and operator-funded inference paths (ADR-0028)
  - Remove Stripe checkout/portal/webhook routes, config, UI, plan entitlements, and schema fields with a safe migration; retain transactional email and account-recovery behavior.
  - Keep web Ask retrieval-only; ensure user requests never invoke model providers using operator-owned keys, and never fall back silently to paid inference.
  - Test that abuse limits remain availability safeguards rather than paid-plan metering.
- [ ] Verify the release security controls
  - Complete the authentication threat model and authorization tests for every user-owned resource.
  - Verify object-storage path isolation and signed URL scope/TTL tests.
  - Establish rate and abuse limits; shared edge throttling must precede horizontal scaling or
    broader public exposure.
  - Produce an SBOM for releases and run the backend container as a non-root user.

## Reliability / operations

- [ ] Demonstrate production monitoring and recovery readiness
  - Define service-level indicators and alert on ingestion failures, processing backlog, and
    provider failures.
  - Monitor database backups and pass a disaster-recovery test.
  - Pass load tests and chaos/fault tests for worker retry behavior.

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
