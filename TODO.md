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

Phone and Wear OS device acceptance, endurance, battery, and fault evidence remain required before Phase 0 closes. The detailed checklists live in `docs/performance/PHASE0_CAPTURE_MEASUREMENTS.md` and `docs/quality/QUALITY_GATES.md`; a source build is not device evidence.

ADR-0020 and ADR-0026 supersede ADR-0014's cloud-processing assumption and accept on-device
transcription. This warning is retained only as historical context; do not require another ADR
for the already accepted phone-only path. Keep the Phase 0 model, battery, and speech-quality
benchmarks open before calling that path production-ready. For the new app, ADR-0026 supersedes
ADR-0015's cloud clip upload and server speech-minute limit: Vosk runs locally, and optional
user-key providers receive audio directly. Preserve the older server flow only for legacy installs
and self-hosters; do not build new cloud upload, plan-limit, or fallback behavior into the phone
pipeline without a new ADR. The relationship between the two ADRs remains a documentation cleanup
item before any future work revives those legacy paths.

ADR-0028 resolves the product direction: the product is free, users may use free on-device
models or their own provider accounts, and the project does not pay model costs on users' behalf.
Remove dormant billing and operator-funded inference paths before production; abuse limits remain
availability controls, not paid-plan metering.

# Phase 0 — Capture reliability

Goal: prove reliable Android capture before building AI infrastructure.

Phase 0 remains open until target-device battery, thermal, storage, speech-ratio, audio-quality, and recovery evidence passes the acceptance checklist in `docs/performance/PHASE0_CAPTURE_MEASUREMENTS.md`. Do not advance delivery priority based on source-only test results.

## Cross-surface compatibility

- [x] Implement responsive web layouts and adaptive Android tablet navigation
  - Web app layouts support phone, tablet, and desktop widths; the notes library switches to a
    folder sidebar at tablet widths.
  - Android uses bottom navigation below 600 dp and a labeled navigation rail with a centered,
    960 dp maximum content width at tablet widths.

# Phase 1 — On-device processing + notes sync

## API foundation

- [ ] Generate OpenAPI document in CI (policy: docs/api/VERSIONING.md)
- [ ] Validate breaking API changes

## Transcription

- [ ] Evaluate transcription quality with noisy/distant speech and multiple speakers

## Local speech analysis and gating (ADR-0015 / ADR-0026)

- [ ] Establish labeled-audio and device-performance evidence before choosing a speech gate
  - Label speech, no speech, media, whisper, and pocket/bag recordings; measure MediaCodec decode
    and VAD throughput on the device matrix (docs/performance).
- [ ] Implement conservative local speech gating only if the measured results support it
  - Use an energy gate before Silero VAD, with configurable pre/post-roll and gap merging.
  - Preserve source segment IDs and timestamp offsets when transcribing selected ranges.
  - On VAD failure, fall back to the energy gate so more audio is retained, and emit capture health.
- [ ] Give users a high-fidelity option and truthful speech/capture status
  - Cover not recording, no speech, waiting for local processing, transcription unavailable, and
    muffled capture (for example, a phone in a pocket or bag).

## Optional notes sync (ADR-0020 / ADR-0026)

ADR-0026 removes storage modes from the phone. Sync is an optional, phone-to-server copy of
notes and never sends audio; the phone remains the source of truth. Do not build transfers between
ADR-0014 storage modes or Drive-backed storage into the current product.

- [ ] Show the last successful notes sync and clearly identify failed or pending notes; offer a manual
  retry if automatic retries do not resolve them
- [ ] Warn during onboarding and settings that unsynced memory exists only on the phone and can be
  lost with the phone

### Phase 1 exit gate

- [ ] Every captured segment is classified as no speech or reaches a transcript or visible
  recoverable/terminal outcome; the on-device default works without network access.
- [ ] Reprocessing and sync retries are idempotent; local transcripts remain until user deletion,
  and deletions propagate to the configured sync target.

# Phase 2 — Daily timeline + structured memory

## Event grouping

- [ ] Group short notes and long meetings into useful, correctable events
  - Avoid over-fragmented 30-second memories and support event-boundary corrections.

## Structured extraction (ADR-0020 / ADR-0026; on-device)

- [ ] Extract evidence-backed facts and highlights on device

## Human correction

- [ ] Let users supersede old decisions and rank confirmed corrections in future retrieval

## Daily summary / daily report

- [ ] Generate reports at bedtime and deliver them at wake time from structured memory + evidence
  - Schedule with `ReportTiming.dueAt` / `ReportTiming.deliverAt`; show partial status while audio is
    pending. The `partial` API status exists, but scheduling and wake-time delivery do not.
- [ ] Report screen in the app; share as Markdown via the Android share sheet — not built;
  `render_markdown` exists and is unused by the app
- [ ] Keep reports grounded and bound processing context
  - Avoid summary-of-summary drift; add chunking/truncation for very long days before generating a
    report. No headline or summary text is currently generated.

## Owner voice attribution (ADR-0016; deferred)

ADR-0016 assumes speech clips and owner spans are sent to a server for diarization; ADR-0026
removes audio uploads and moves processing to the phone. Do not implement that hybrid flow. First
reconcile ADR-0016 with ADR-0020/0026 in a new ADR. Proceed only if Phase 2 evaluation shows a
material improvement in commitment attribution and the required biometric legal and consent
review passes.

- [ ] Revisit owner voice attribution only if Phase 2 evaluation shows material benefit and a new ADR,
  biometric, legal, and consent review approves an on-device design
  - Enrollment must require device unlock, be deletable, and use quality checks; encrypt the owner
    embedding, and exclude it from backup/export; target an enrollment of about 45 seconds.
  - Keep raw owner spans (ME / NOT_ME / UNSURE) on device; sharing derived ownership needs explicit
    approval in the revised ADR, and never store another person's voiceprint.
  - Map diarization clusters to the owner, with short turns inheriting their cluster; support a
    complete not-enrolled path (“Someone”).
  - Extract owner, direction (`i_committed` / `asked_of_me` / `i_asked` / `they_committed`), and
    confidence. Speaker corrections must re-derive affected items.
  - Add report contract v1.1 fields: Your to-dos / Waiting on others / Not sure who.

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

- [ ] Preserve timestamp provenance and stable capture ordering
  - Store UTC, source timezone, and local date; use monotonic time where wall-clock drift could affect
    capture ordering.
- [ ] Resolve relative dates and dayparts across daylight-saving and travel changes
  - Support today/yesterday/last week, morning/afternoon/evening, DST transitions, and timezone
    changes while traveling.

## Entities

- [ ] Resolve people, projects, companies, places, and products with aliases and correctable mentions
  - Allow user correction/merge; a mentioned name must never be treated as proof of speaker identity.

## Retrieval

- [ ] Deliver bounded, evidence-backed recall using structured and lexical search
  - Use structured SQL first, PostgreSQL full-text search, date/type/entity filters, and evidence
    expansion around winning events.
  - Bound candidate sets, return evidence references, and abstain when evidence is insufficient.

## Recall evaluation

- [ ] Build a known-day recall benchmark and add regression tests for confirmed failures
  - Measure Recall@K, ranking, temporal and entity-alias accuracy, evidence-source accuracy, and
    insufficient-evidence abstention.

### Phase 4 exit gate

- [ ] Known benchmark questions reliably retrieve the correct evidence without vector search.

# Phase 5 — CLI

## CLI

- [ ] adm highlights

### Phase 5 exit gate

- [ ] CLI returns evidence-backed results through the public API.

# Phase 6 — Semantic recall (only if benchmark proves need)

- [ ] Add semantic recall only if Phase 4 benchmarks show a meaningful lexical-recall gap
  - Establish the lexical/structured baseline first; prefer event and structured-memory embeddings
    over tiny transcript fragments.
  - Start with pgvector exact search, hybrid lexical/vector retrieval, Reciprocal Rank Fusion, and
    reranking a small candidate set; measure recall, latency, and cost.
  - Consider approximate indexes only if exact search is too slow, and measure recall loss first.

### Phase 6 exit gate

- [ ] Semantic retrieval provides measurable recall improvement for acceptable latency/cost.

# Phase 7 — Long-term memory

- [ ] Add durable summaries and project memory for recurring commitments and entity relationships
- [ ] Consolidate and supersede memories while supporting reprocessing by extraction/schema version
- [ ] Provide retention controls and user-visible memory correction/deletion

# Phase 8 — Production hardening

## Privacy / legal / abuse resistance

- [ ] Complete the release privacy and data-rights package
  - Provide consent guidance and a jurisdiction/workplace-policy disclaimer.
  - Complete a threat model, privacy data-flow diagram, data inventory/classification, and
    retention/deletion matrix.
  - Provide user-initiated export of transcripts, events, highlights, memory items, entities, and
    daily summaries (JSON + JSONL).
  - Include raw audio only while retention still holds (ADR-0003), and explain expiry honestly.
  - Deliver exports through scoped, short-TTL signed URLs, never inline in the API response.
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
