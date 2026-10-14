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

**Product decision conflict:** the repository rules and Phase 10 plan call for a monthly
subscription with server-authoritative entitlements, while ADR-0026 says the product is free and
billing stays off. Keep billing and plan-metered processing work open until this product direction
is reconciled; do not silently delete either requirement.

# Phase 0 — Capture reliability

Goal: prove reliable Android capture before building AI infrastructure.

Phase 0 remains open until target-device battery, thermal, storage, speech-ratio, audio-quality, and recovery evidence passes the acceptance checklist in `docs/performance/PHASE0_CAPTURE_MEASUREMENTS.md`. Do not advance delivery priority based on source-only test results.

# Phase 1 — On-device processing + notes sync

## API foundation

- [ ] Generate OpenAPI document in CI (policy: docs/api/VERSIONING.md)
- [ ] Validate breaking API changes
- [ ] Shared edge throttling before horizontal scaling or broader public exposure

## Transcription

- [ ] Evaluate noisy/distant speech
- [ ] Evaluate multiple speakers

## Legacy phone audio upload client (ADR-0015)

The existing implementation remains for legacy installs and self-hosters. ADR-0026 removes
audio upload from the new app's default path; new local processing must not depend on this client.

## Local speech analysis and gating (ADR-0015 / ADR-0026)

- [ ] Labeled day-audio set (speech / no speech / media / whisper / pocket) for VAD tuning
- [ ] Measure MediaCodec decode + VAD throughput on the device matrix (docs/performance)
- [ ] Energy gate then Silero VAD (ONNX Runtime, stable); pre/post-roll, gap merge as config
- [ ] Preserve source segment IDs and timestamp offsets when local VAD ranges are transcribed
- [ ] VAD failure falls back to the energy gate (more audio, never less); capture-health event
- [ ] High-fidelity setting (all non-silent audio)
- [ ] Coverage states: not recording / no speech / waiting for local processing / transcription unavailable
- [ ] "Muffled" capture-health signal (phone in pocket/bag)

## Storage modes (ADR-0014)

- [ ] Cross-day context packet (open action items) sent by the phone with the night job
- [ ] Mode switch phone -> cloud (from now / include history), idempotent by client IDs
- [ ] Mode switch cloud -> phone: download, verify, then server delete; never delete first
- [ ] "Last saved to Drive" indicator; phone-loss warning in phone-only mode

### Phase 1 exit gate

- [ ] Every acknowledged upload becomes a transcript or an explicit recoverable/terminal failure.
- [ ] Duplicate retries produce no duplicate transcript evidence.

# Phase 2 — Daily timeline + structured memory

## Event grouping

- [ ] Avoid over-fragmented 30-second memories
- [ ] Handle long meetings
- [ ] Handle short Quick Notes
- [ ] Handle event boundary corrections

## Structured extraction (ADR-0019; full_cloud accounts only — see ADR-0014)

- [ ] Facts
- [ ] Highlights

## Human correction

- [ ] Supersede old decisions
- [ ] Include confirmed corrections in future retrieval ranking

## Daily summary / daily report

- [ ] Generate the report at bedtime (ReportTiming.dueAt) from structured memory + evidence —
  no scheduler exists; a report is built as soon as extraction has something (ADR-0019)
- [ ] Deliver it at wake time (ReportTiming.deliverAt); "partial" while audio is pending — the
  "partial" status is implemented (GET /v1/reports/{memory_day}); wake-time delivery is not
- [ ] Report screen in the app; share as Markdown (Google Drive via the share sheet) — not built;
  `render_markdown` exists and is unused by the app
- [ ] Optional nightly auto-save to Drive from the phone (`drive.file`, on-device auth; server never holds Drive tokens)
- [ ] Avoid summary-of-summary drift (no headline/summary text is generated at all yet)
- [ ] Bound LLM context (no chunking/truncation for a very long day's transcript yet)

## Owner voice attribution (ADR-0016)

- [ ] Consent screen; enrollment behind device unlock; delete anytime
- [ ] Enrollment (~45 s, quality checks), embedding encrypted on device, excluded from backup/export
- [ ] Owner spans (ME / NOT_ME / UNSURE) uploaded as metadata only
- [ ] Map diarization clusters to Me by overlap-weighted vote; short turns inherit cluster label
- [ ] Not-enrolled path: "Someone", report still complete
- [ ] Extraction: owner + direction (i_committed / asked_of_me / i_asked / they_committed) + confidence
- [ ] Report contract v1.1 (additive): Your to-dos / Waiting on others / Not sure who
- [ ] Speaker flip stored as a correction; re-derives affected items
- [ ] Never store voiceprints for anyone but the enrolled owner (test + review checklist)

## AI evaluation

- [ ] Create human-reviewed gold dataset
- [ ] Measure decision precision/recall
- [ ] Measure explicit task precision/recall
- [ ] Measure commitment precision/recall
- [ ] Measure idea extraction quality
- [ ] Treat false-positive tasks/commitments as high-severity quality failures
- [ ] Add regression tests for every confirmed extraction bug

### Phase 2 exit gate

- [ ] Timeline consistently surfaces information worth remembering.
- [ ] Every derived item can trace back to evidence.
- [ ] User corrections never mutate original evidence.

# Phase 3 — Wear OS

- [ ] Session duration

### Phase 3 exit gate

- [ ] Watch controls phone reliably during normal daily use.
- [ ] UI never claims a command succeeded before phone acknowledgement.

# Phase 4 — Recall engine

## Time

- [ ] Persist UTC timestamps + source timezone + local date
- [ ] Resolve today/yesterday/last week
- [ ] Resolve morning/afternoon/evening
- [ ] Handle DST transitions
- [ ] Handle travel / timezone changes
- [ ] Use monotonic device time for capture ordering where wall-clock drift could matter

## Entities

- [ ] Person/project/company/place/product entity model
- [ ] Aliases
- [ ] Entity mentions
- [ ] User correction/merge
- [ ] Do not assume mentioned name == speaker identity

## Retrieval

- [ ] Structured SQL first
- [ ] PostgreSQL full-text search
- [ ] Date/type/entity filters
- [ ] Evidence expansion around winning event
- [ ] Bounded candidate sets
- [ ] Answer abstains when evidence is insufficient
- [ ] Return evidence references

## Recall evaluation

- [ ] Build benchmark questions from known days
- [ ] Recall@K
- [ ] Ranking quality
- [ ] Temporal query accuracy
- [ ] Entity alias accuracy
- [ ] Evidence-source accuracy
- [ ] Insufficient-evidence abstention rate
- [ ] Add regression test for every confirmed recall failure

### Phase 4 exit gate

- [ ] Known benchmark questions reliably retrieve the correct evidence without vector search.

# Phase 5 — CLI

## CLI

- [ ] adm highlights

### Phase 5 exit gate

- [ ] CLI returns evidence-backed results through the public API.

# Phase 6 — Semantic recall (only if benchmark proves need)

- [ ] Establish lexical/structured baseline first
- [ ] Add event embeddings
- [ ] Add structured-memory embeddings
- [ ] Avoid embeddings for every tiny transcript fragment
- [ ] pgvector exact search first
- [ ] Hybrid lexical + vector retrieval
- [ ] Reciprocal Rank Fusion
- [ ] Rerank only a small candidate set
- [ ] Benchmark recall improvement
- [ ] Benchmark latency
- [ ] Benchmark cost
- [ ] Add approximate index only if exact vector search becomes too slow
- [ ] Measure recall loss before HNSW/IVF rollout

### Phase 6 exit gate

- [ ] Semantic retrieval provides measurable recall improvement for acceptable latency/cost.

# Phase 7 — Long-term memory

- [ ] Weekly summaries
- [ ] Project memory
- [ ] Recurring commitments
- [ ] Memory supersession
- [ ] Durable entity relationships
- [ ] Memory consolidation
- [ ] Reprocessing by extraction/schema version
- [ ] Retention controls
- [ ] User-visible memory deletion/correction

# Phase 8 — Production hardening

## Privacy / legal / abuse resistance

- [ ] Consent guidance
- [ ] Jurisdiction/workplace-policy disclaimer
- [ ] Threat model
- [ ] Privacy data-flow diagram
- [ ] Data inventory and classification
- [ ] Retention/deletion matrix
- [ ] Account export
  - [ ] User-initiated export includes transcripts, events, highlights, memory items, entities, and daily summaries (JSON + JSONL)
  - [ ] Raw audio included only while retention still holds (ADR-0003); document the expiry honestly
  - [ ] Export delivered via scoped, short-TTL signed URL, never inline in the API response
  - [ ] Export requests rate-limited and idempotent; re-request within TTL does not regenerate
  - [ ] Export is excluded from third-party/API-key scopes by default (self-service only)

## Security

- [ ] Authentication threat model
- [ ] Authorization tests for every user-owned resource
- [ ] Object-storage path isolation
- [ ] Signed URL scope/TTL tests
- [ ] Rate limits / abuse limits
- [ ] SBOM for releases
- [ ] Backend container runs non-root

## Reliability / operations

- [ ] Service-level indicators
- [ ] Alerting for ingestion failures
- [ ] Alerting for processing backlog
- [ ] Alerting for provider failure
- [ ] Database backup monitoring
- [ ] Disaster recovery test
- [ ] Load tests
- [ ] Chaos/fault tests for worker retry behavior

# Phase 10 — Public launch: subscription, user API, data portability

Do not begin until Phase 5 (CLI) and Phase 8 (production hardening) have
exited. Charging money and opening third-party API access raise the bar on
authorization, billing correctness, and data rights — everything below builds on
hardened phases, it does not replace them.

## Billing / subscription

- [ ] Entitlement service behind the same API surface as other features
- [ ] Plan definition: free tier limits, paid tiers, what is metered (recording hours, processing, recall calls, API calls)
- [ ] Grace period / dunning / cancellation behavior defined and tested
- [ ] Sandbox + production billing test suite (purchase, renew, cancel, refund, restore on reinstall)
- [ ] Subscription state surfaced honestly in the app (no dark patterns; cancellation as easy as sign-up)

## Public API access (user-owned clients)

- [ ] API keys shipped with the public API (scopes, hashing, rotation, rate limits)
- [ ] Public API docs with auth, rate limits, and error semantics
- [ ] Versioned API contract; deprecation policy for breaking changes
- [ ] API access gated by subscription entitlement
- [ ] Abuse monitoring per key (volume anomalies, scraping patterns)

## Data portability

- [ ] Export completeness tested against the data inventory
- [ ] Account deletion covers derived data, embeddings, jobs, and retained objects (SECURITY.md)

### Phase 10 exit gate

- [ ] A paying user can subscribe, connect an API client, export all their data, and cancel — entirely self-service, with correct entitlement enforcement at every step.
