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

## Quality bar

- [x] Kotlin/Android code style baseline via EditorConfig
- [x] Python lint/type/test baseline via Ruff + mypy + pytest
- [x] CI scaffold for Android and backend
- [x] Dependabot scaffold for Gradle, Python, and GitHub Actions
- [x] Security policy and sensitive-data rules
- [x] Pure recording state-machine module with unit-test scaffold
- [x] 90% minimum line coverage gate for deterministic recording-domain logic
- [x] 90% minimum backend coverage gate
- [ ] Keep >=90% line coverage for core/domain/retrieval/processing business logic
- [ ] Keep >=90% branch coverage for safety-critical state machines and retry/idempotency logic
- [ ] Do not game coverage with trivial UI tests; use Compose/instrumentation tests for user behavior
- [ ] No high/critical known dependency vulnerabilities in release builds
- [ ] No secrets, access tokens, raw transcripts, or audio in logs
- [ ] All API inputs validated and all user resources authorization-scoped
- [ ] All migrations have forward + rollback/recovery strategy
- [ ] All retryable operations are idempotent
- [ ] All background work has bounded retry behavior
- [ ] All user-facing failure states are visible and recoverable where possible

## Repository / engineering foundation

- [x] Create Android app scaffold
- [x] Create pure Kotlin recording-domain module
- [x] Create FastAPI backend scaffold
- [x] Create Wear OS placeholder
- [x] Create MCP placeholder
- [x] Create CLI placeholder
- [x] Create CI workflow
- [x] Create quality-gates document
- [x] Create ADR directory and first architecture decision
- [x] Add security-conscious .gitignore
- [x] Add PR template and contributing guide
- [x] Generate and commit Gradle wrapper using Gradle 9.7.1
- [x] Generate and commit backend uv.lock
- [x] CI uses the wrapper and fails on a stale uv.lock (`uv sync --locked`)
- [ ] Verify fresh clone builds on macOS, Linux, and CI (Linux verified with JDK 17 + Android SDK 37; macOS and CI pending)
- [ ] Enable main-branch protection
- [ ] Require CI before merge
- [ ] Require at least one approving review once more than one maintainer exists
- [ ] Enable GitHub secret scanning / push protection if available
- [ ] Enable dependency review for pull requests
- [ ] Add CODEOWNERS when ownership expands
- [ ] Add release notes / changelog automation when first release exists
- [ ] Decide license before making repository public

## Android design system

- [x] Material 3 Compose shell
- [x] Edge-to-edge app shell
- [x] Responsive status / action layout scaffold
- [x] Define semantic color tokens for recording, paused, warning, error (fixed, not dynamic: `StatusColors`)
- [x] Add typography scale and spacing tokens (`Tokens.kt`: `Spacing`, `AppTypography`)
- [x] Support light/dark mode
- [x] Support dynamic color where appropriate (chrome only; capture state uses fixed tokens)
- [ ] Meet WCAG/Android accessibility expectations
- [x] 48dp minimum touch targets (`Spacing.minTouchTarget`; asserted in `CaptureScreenTest`)
- [x] Content descriptions for non-text controls (all controls are text; status dot is decorative next to the headline; headline/detail are a polite live region, stats read as label + value)
- [ ] TalkBack test critical flows
- [ ] Support font scaling to at least 200% (main screen scrolls, all text in sp, buttons grow with `heightIn`; verify on device)
- [ ] Add screenshot/golden testing only if it provides maintainable value

# Phase 0 — Capture reliability

Goal: prove reliable Android capture before building AI infrastructure.

## Early hardware spike (do first — it shapes the service design)

- [ ] Verify mic foreground-service start/resume from background on the target phone (ADR-0010)
- [ ] Verify Pause-keeps-service + Resume from notification works with screen off
- [ ] Verify what a background-blocked microphone delivers (exception vs digital silence)

## Permissions / foreground service

- [x] Implement microphone permission education and request flow
- [x] Handle permanently denied microphone permission (persisted "asked before" → Settings deep link)
- [x] Request notification permission where required (required to record: the indicator must be visible)
- [x] Implement microphone foreground service (`RecordingService`)
- [x] Declare correct foreground-service type (`microphone`)
- [x] Create persistent recording notification (public on lock screen; re-posted if swiped away)
- [x] Add notification actions: Pause, Resume, Stop, Highlight
- [x] Ensure foreground service enters foreground within platform timeout (`startForeground` first; never started when it would fail)
- [ ] Handle app background / screen-off behavior (implemented as an FGS without wake locks; verify in the hardware spike)
- [x] Handle OEM-specific battery restrictions without misleading the user (kills surface as interruptions; honest info card + battery settings link)
- [x] Pause keeps the foreground service running with the mic closed (ADR-0010)

## Recording state machine

- [x] Create pure deterministic state machine
- [x] Unit-test legal transitions
- [x] Unit-test invalid transitions
- [x] Reducer returns Applied / Ignored / Rejected instead of throwing (a bad event cannot crash the service)
- [x] Session identity, monotonic revision, and capture-problem reason in state
- [x] Exhaustive status × event matrix test
- [x] Separate user commands from capture-engine signals (watch may only send commands)
- [x] Wire state machine to Android service (`RecordingController` + `CaptureEffectPlanner`)
- [x] Persist state transitions (append-only journal keyed by revision) before dependent side effects
- [x] Decide recovery events for a persisted state after process death/reboot (`StartupRecovery`)
- [x] Recover persisted session state after process death (service wiring)
- [x] After reboot: never auto-start; post "tap to resume" notification and record the gap (ADR-0010)
- [ ] Add state-machine property/fuzz tests if transition count expands materially

## Local persistence, identity, time

- [x] Room with exported schema and migration tests from the first entity
- [x] UUIDv7 generator for client-created IDs (ADR-0006) — monotonic, clock-skew safe
- [x] Capture-time model: occurred_at (UTC), timezone, UTC offset, local_date, elapsedRealtime + boot id (`CaptureTime`)
- [x] Stamp every captured record with `CaptureTime` in the recorder service (transitions, segments, markers)
- [x] Decide Quick Note model: marked window inside an active session (ADR-0011)

## Audio capture

- [ ] Select initial capture format after device benchmark, among append-safe containers only (Ogg/Opus or AAC-ADTS; not MP4 — ADR-0007). AAC-LC/ADTS 16 kHz 32 kbit/s implemented provisionally; benchmark owed (docs/performance/PHASE0_CAPTURE_MEASUREMENTS.md)
- [x] Implement short crash-resistant local segments (60 s, ADTS, row before bytes)
- [x] Add monotonic sequence IDs (dense per session, unique index)
- [x] Add checksums (SHA-256 over plaintext)
- [x] fsync/close segments safely before marking persisted (fsync + atomic rename + dir sync, then SEALED)
- [x] Avoid a single all-day audio file
- [x] Handle microphone loss / audio route changes (verify on device)
- [x] Handle incoming/outgoing calls (audio mode + silenced client → `PHONE_CALL`; resumes when the call ends; verify on device)
- [x] Handle another app taking microphone focus (`isClientSilenced` + digital silence; bounded retries)
- [x] Detect audio gaps — classification logic (`GapDetector`)
- [x] Feed sealed-segment timings from the recorder into `GapDetector` (capture health shows gaps)
- [x] Detect corrupt or zero-byte segments at seal (`SegmentLifecycle` → QUARANTINED)
- [x] Detect sustained digital silence (blocked mic) — `DigitalSilenceDetector`
- [x] Report detector output as `Interrupted(MICROPHONE_UNAVAILABLE)` from the recorder
- [x] Never mark uncaptured time as successfully recorded (only SEALED counts; torn segments quarantined)

## Local security

- [x] Encrypt local temporary audio with Tink streaming AEAD (not deprecated security-crypto; ADR-0007)
- [x] Protect keys with Android Keystore (no user-auth requirement; Tink's cleartext fallback refused)
- [x] No capture before first unlock after reboot (not direct-boot aware + `DEVICE_LOCKED` blocker)
- [x] Define key rotation / invalidation behavior (SECURITY.md)
- [x] Disable Android backup of sensitive app data (allowBackup=false + exclude-all extraction rules)
- [ ] Verify no sensitive files are exported via backup/debug tooling
- [x] Delete local audio only after explicit server acknowledgement + recovery window (enforced in `SegmentLifecycle`)
- [x] Secure-delete semantics documented honestly; do not promise physical flash erasure (SECURITY.md)

## Storage pressure

- [x] Track free disk space
- [x] Define warning threshold (`StoragePolicy`, provisional values)
- [x] Define hard-stop threshold (`StoragePolicy`, provisional values)
- [x] Surface storage problems before recording fails (health + "storage left"; start refused and capture stopped at the hard stop)
- [ ] Test device-low-storage conditions
- [x] Bound local pending-upload storage (warn over budget; never delete unacknowledged audio)

## Capture health

- [x] Last segment successfully persisted ("Last saved")
- [x] Current session duration (shown as saved audio, which never counts gaps)
- [x] Pending upload duration ("Only on this phone")
- [ ] Watch connectivity (health input wired as NOT_PAIRED; Data Layer is Phase 3)
- [x] Available storage health
- [x] Interruption indicator (headline, problem text, alert notification)
- [x] Battery/thermal telemetry in debug/internal builds (`DebugTelemetry`)
- [x] Never show "Recording" unless capture is actually healthy (`CaptureHealthEvaluator` + `CaptureStatusPresenter`)

## Daily rhythm (ADR-0012)

- [x] Sleep schedule and quiet-time model with transition planner (DST, midnight, overlaps, travel)
- [x] Memory day turns at the sleep midpoint; fixed per session at start
- [x] Schedule pause/resume/stop in the reducer; a user pause is never resumed by the schedule
- [x] Precise in-service timer while recording; inexact alarms otherwise; catch-up after sleep/reboot/clock change
- [x] Bedtime ends the day; morning reminder (never auto-starts capture)
- [x] Sessions list by memory day with automatic titles and validated rename (Room v2 + migration)
- [x] Settings UI: sleep schedule, quiet times, audio retention with storage estimates
- [ ] Device-verify alarm lateness in Doze and pause precision while recording (docs/performance/)
- [ ] Usability test the settings flow with real users (sleep + two quiet times in under a minute)

## Endurance / device matrix

- [ ] 1-hour test
- [ ] 4-hour test
- [ ] 8-hour test
- [ ] 12-hour test
- [ ] Screen on/off test
- [ ] Wi-Fi/mobile/offline transitions
- [ ] Bluetooth connected/disconnected
- [ ] Phone call interruption
- [ ] Process kill / recovery
- [ ] Reboot / recovery
- [ ] Low-storage test
- [ ] Thermal stress test
- [ ] Measure battery drain
- [ ] Measure CPU
- [ ] Measure memory
- [ ] Measure storage growth
- [ ] Measure audio gaps
- [ ] Measure speech-active ratio per recorded hour (VAD in shadow mode; feeds the Gate B cost model)
- [ ] Record benchmark results in docs/performance/

### Phase 0 exit gate

- [ ] A normal workday can be captured with acceptable battery, thermal, storage, audio quality, and recovery behavior.
- [ ] No silent capture failure in endurance/fault tests.
- [ ] Core recording-domain tests maintain >=90% line and branch coverage.

# Phase 1 — Upload + transcription

## API foundation

- [x] FastAPI health endpoint scaffold
- [x] Strict Python lint/type/test scaffold
- [x] Typed settings from the environment; API docs/OpenAPI hidden outside development/test
- [ ] Define API versioning policy (/v1)
- [ ] Generate OpenAPI document in CI
- [ ] Validate breaking API changes
- [x] Add request IDs / correlation IDs (validated inbound `X-Request-ID`)
- [x] Add structured redacted logging (JSON; field-name redaction; no exception messages or query strings)
- [x] Shared error envelope; validation errors never echo input
- [ ] User-scoped schema from the first migration: user_id on every user-owned table, cascading FKs (ADR-0009)
- [ ] Backend is the only DB client; Supabase Data API disabled, RLS deny-all backstop (ADR-0009)
- [ ] Separate DB roles: migration owner, app (DML only), read-only
- [ ] Add auth once multi-user testing starts
- [ ] Add per-user authorization checks
- [ ] Add rate limiting before public exposure
- [x] Add payload/body size limits (declared and streamed; 413)
- [ ] Add request timeouts

## Upload protocol

- [ ] Stable client-generated session/segment IDs
- [ ] Signed temporary upload instructions
- [ ] Direct-to-object-storage upload where possible
- [ ] SHA-256 integrity validation
- [ ] Idempotent segment finalize endpoint
- [ ] Resume/retry behavior
- [ ] Duplicate upload behavior
- [ ] Expired signed URL handling
- [ ] Quota checks
- [ ] Temporary object lifecycle policy
- [ ] Object keys prefixed users/{user_id}/ for isolation and prefix deletion
- [ ] Same idempotency key with a different checksum is a hard, operator-visible error (ADR-0006)

## Processing jobs

- [ ] PostgreSQL-backed initial job table
- [ ] Claim/lease semantics for workers
- [ ] Idempotency key per processing stage
- [ ] Bounded exponential backoff
- [ ] Dead-letter / terminal-failure state
- [ ] Operator-visible failure reason
- [ ] Retry-safe provider calls where possible
- [ ] Do not require Redis until measured throughput justifies it

## Transcription

- [ ] Per user-day cost model from Phase 0 speech-ratio measurements (Gate B input)
- [ ] Provider terms reviewed: no training on customer data, bounded retention, DPA, region
- [ ] Recording-consent legal review (all-party-consent jurisdictions) before any non-developer user
- [ ] Choose one provider for MVP
- [ ] Abstract provider behind a thin interface
- [ ] Preserve timestamps
- [ ] Preserve provider/model/version metadata
- [ ] Store confidence metadata if available
- [ ] Handle provider timeout
- [ ] Handle partial/invalid result
- [ ] Define max chunk size
- [ ] Evaluate noisy/distant speech
- [ ] Evaluate multiple speakers
- [ ] Delete temporary cloud audio only after verified transcript persistence
- [ ] Re-transcription creates a new transcript revision; exactly one active revision per segment (ADR-0004 amendment)

## Speech gating + night processing (ADR-0015, proposed)

- [ ] Labeled day-audio set (speech / no speech / media / whisper / pocket) for VAD tuning
- [ ] Night WorkManager job: charging + unmetered, deadline fallback at wake + 2 h, "late" flag
- [ ] Measure MediaCodec decode + VAD throughput on the device matrix (docs/performance)
- [ ] Energy gate then Silero VAD (ONNX Runtime, stable); pre/post-roll, gap merge as config
- [ ] Cut clips on ADTS frame boundaries; clip carries segment_id + offset
- [ ] VAD failure falls back to the energy gate (more audio, never less); capture-health event
- [ ] High-fidelity setting (all non-silent audio)
- [ ] Coverage states: not recording / no speech / not yet processed / over plan limit
- [ ] Plan daily speech-minute limit enforced server-side; overflow kept locally, shown as a gap
- [ ] "Muffled" capture-health signal (phone in pocket/bag)

## Storage modes (ADR-0014, proposed)

- [ ] Account storage mode: On this phone (default) / Reports in cloud / Full cloud memory
- [ ] Transient processing hold: until device ack, max 7 days, then crypto-shred
- [ ] Day bundle encrypted to a per-device Keystore public key (Tink)
- [ ] Invariant test: local audio deleted only after its transcript is persisted where the user's data lives
- [ ] Expired bundle -> unacknowledged audio re-uploaded (no silent loss)
- [ ] Cross-day context packet (open action items) sent by the phone with the night job
- [ ] Mode switch phone -> cloud (from now / include history), idempotent by client IDs
- [ ] Mode switch cloud -> phone: download, verify, then server delete; never delete first
- [ ] Onboarding copy: "stored only on your phone" vs "never leaves your phone" (processing is cloud)
- [ ] "Last saved to Drive" indicator; phone-loss warning in phone-only mode

### Phase 1 exit gate

- [ ] Every acknowledged upload becomes a transcript or an explicit recoverable/terminal failure.
- [ ] Duplicate retries produce no duplicate transcript evidence.
- [ ] Temporary audio retention/deletion is tested.

# Phase 2 — Daily timeline + structured memory

## Event grouping

- [ ] Group transcript segments into useful event windows
- [ ] Preserve exact evidence links
- [ ] Avoid over-fragmented 30-second memories
- [ ] Handle long meetings
- [ ] Handle short Quick Notes
- [ ] Handle event boundary corrections

## Structured extraction

- [ ] Decisions
- [ ] Actions
- [ ] Commitments
- [ ] Ideas
- [ ] Facts
- [ ] Questions
- [ ] Highlights
- [ ] Confidence/review state
- [ ] Extraction/model/prompt version metadata
- [ ] Never rewrite source transcript
- [ ] Treat transcript text as untrusted model input: delimit as data, schema-validate output, no tool access
- [ ] Memory items cite evidence many-to-many (memory_item_evidence)

## Human correction

- [ ] Correct
- [ ] Edit
- [ ] Dismiss / Not a task
- [ ] Supersede old decisions
- [ ] Store correction separately from evidence
- [ ] Include confirmed corrections in future retrieval ranking

## Daily summary / daily report

- [x] Daily report contract v1 + JSON Schema (contracts/) + Markdown template (docs/product/daily-report-example.md)
- [ ] Generate the report at bedtime (ReportTiming.dueAt) from structured memory + evidence
- [ ] Deliver it at wake time (ReportTiming.deliverAt); "partial" while audio is pending
- [ ] Report screen in the app; share as Markdown (Google Drive via the share sheet)
- [ ] Optional nightly auto-save to Drive from the phone (`drive.file`, on-device auth; server never holds Drive tokens)
- [ ] Build from structured memory + evidence
- [ ] Avoid summary-of-summary drift
- [ ] Link important items to source timestamps
- [ ] Regenerate when extraction version changes
- [ ] Bound LLM context

## Owner voice attribution (ADR-0016, proposed)

- [ ] Legal review: biometric consent (BIPA / CUBI / WA / GDPR Art. 9), published retention schedule
- [ ] Consent screen; enrollment behind device unlock; delete anytime
- [ ] Enrollment (~45 s, quality checks), embedding encrypted on device, excluded from backup/export
- [ ] On-device speaker-embedding model: size, speed, battery measured at night
- [ ] Owner spans (ME / NOT_ME / UNSURE) uploaded as metadata only
- [ ] Map diarization clusters to Me by overlap-weighted vote; short turns inherit cluster label
- [ ] Not-enrolled path: "Someone", report still complete
- [ ] Extraction: owner + direction (i_committed / asked_of_me / i_asked / they_committed) + confidence
- [ ] Report contract v1.1 (additive): Your to-dos / Waiting on others / Not sure who
- [ ] Speaker flip stored as a correction; re-derives affected items
- [ ] Measure false-Me and missed-Me rates on a real-conditions labeled set
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

- [x] Create Wear OS application module (scaffold)
- [x] Watch shares the phone applicationId and debug suffix (Data Layer requirement; ADR-0008)
- [ ] Wear module depends on core/recording for shared command/state types
- [ ] Pair communication using Wear OS Data Layer (commands via MessageClient, state via DataClient; ADR-0008)
- [ ] Ensure watch state always reflects acknowledged phone state (revision-ordered snapshots)
- [ ] Command IDs deduplicated on the phone; retries return the cached outcome
- [ ] Start
- [ ] Pause
- [ ] Resume
- [ ] Stop
- [ ] Highlight
- [ ] Quick Note
- [ ] Recording status
- [ ] Session duration
- [ ] Phone-disconnected state
- [ ] Command acknowledgement / timeout UX
- [ ] Watch battery benchmark
- [ ] Watch accessibility
- [ ] Physical OnePlus Watch 2 endurance tests

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

# Phase 5 — MCP + CLI

## MCP

- [x] Directory scaffold
- [ ] API keys and MCP require a cloud storage mode; key creation offers the opt-in (ADR-0014)
- [ ] Read-only initial server
- [ ] ask_memory
- [ ] search_memory
- [ ] get_day
- [ ] get_timeline
- [ ] get_decisions
- [ ] get_action_items
- [ ] get_daily_report (returns contracts/daily-report.v1.schema.json)
- [ ] get_commitments
- [ ] get_highlights
- [ ] Per-user authorization
- [ ] Bounded result sizes
- [ ] No direct database access
- [ ] No bulk transcript export by default
- [ ] Transcript text returned as bounded, clearly quoted fields (prompt-injection hygiene for the user's agent)
- [ ] Audit sensitive tool access
- [ ] API-key authentication for user-owned third-party clients
- [ ] Key scopes (read-only initially) and least-privilege defaults
- [ ] Key creation / rotation / revocation UI and API
- [ ] Per-key and per-user rate limits
- [ ] Key stored only as a secure hash
- [ ] MCP transport auth (authenticate the calling user, never a shared service key)

## CLI

- [x] Directory scaffold
- [ ] adm ask
- [ ] adm day
- [ ] adm search
- [ ] adm decisions
- [ ] adm tasks
- [ ] adm highlights
- [ ] Secure credential storage
- [ ] Machine-readable JSON mode

### Phase 5 exit gate

- [ ] MCP and CLI return the same evidence-backed results as Android through the shared query API.

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
- [ ] No graph database unless relational limitations are measured

# Phase 8 — Production hardening

## Privacy / legal / abuse resistance

- [ ] Clear recording indicator
- [ ] Fast privacy pause
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
- [ ] Account deletion
- [ ] Child/minor policy before broad release
- [ ] Abuse review for covert recording scenarios

## Security

- [ ] Authentication threat model
- [ ] Authorization tests for every user-owned resource
- [ ] Object-storage path isolation
- [ ] Signed URL scope/TTL tests
- [ ] Rate limits / abuse limits
- [ ] Secrets manager in production
- [ ] Key rotation procedures
- [ ] Dependency vulnerability gate
- [ ] SBOM for releases
- [ ] Mobile release signing procedure
- [ ] Backend container runs non-root
- [ ] Database least-privilege roles
- [ ] Backup encryption
- [ ] Restore drill
- [ ] Incident response runbook

## Reliability / operations

- [ ] Sentry
- [ ] Redacted structured logs
- [ ] Service-level indicators
- [ ] Alerting for ingestion failures
- [ ] Alerting for processing backlog
- [ ] Alerting for provider failure
- [ ] Database backup monitoring
- [ ] Disaster recovery test
- [ ] Load tests
- [ ] Chaos/fault tests for worker retry behavior

## Performance

- [ ] Define startup budget
- [ ] Define recording CPU budget
- [ ] Define memory budget
- [ ] Define battery budget from Phase 0 evidence
- [ ] Define API p95 latency budgets
- [ ] Define Ask My Memory p95 latency budget
- [ ] Macrobenchmarks for critical Android journeys
- [ ] Baseline Profile generation for release builds
- [ ] Track Android Vitals crash/ANR/battery regressions
- [ ] Detect performance regressions in CI where practical

## Release

- [ ] Internal test track
- [ ] Closed beta
- [ ] Data safety disclosures
- [ ] Privacy policy
- [ ] Terms as required
- [ ] Play Store listing
- [ ] Rollback plan
- [ ] Staged rollout
- [ ] Release checklist

# Phase 9 — iOS + Apple Watch

Do not begin until Android capture and recall are proven.

- [ ] Re-evaluate product-market fit
- [ ] Native iOS architecture
- [ ] Native watchOS architecture
- [ ] Platform-specific capture constraints
- [ ] Shared backend contracts only; do not force UI code sharing

# Phase 10 — Public launch: subscription, user API, data portability

Do not begin until Phase 5 (MCP + CLI) and Phase 8 (production hardening) have
exited. Charging money and opening third-party API access raise the bar on
authorization, billing correctness, and data rights — everything below builds on
hardened phases, it does not replace them.

## Billing / subscription

- [ ] ADR: billing provider decision (Google Play Billing for Android + server-side entitlement verification; decide web/stripe path if web sign-up exists)
- [ ] Server-authoritative entitlement checks (client state is never trusted for gating)
- [ ] Entitlement service behind the same API surface as other features
- [ ] Plan definition: free tier limits, paid tiers, what is metered (recording hours, processing, recall calls, MCP/API calls)
- [ ] Metering recorded per user with auditable counters
- [ ] Grace period / dunning / cancellation behavior defined and tested
- [ ] Sandbox + production billing test suite (purchase, renew, cancel, refund, restore on reinstall)
- [ ] Subscription state surfaced honestly in the app (no dark patterns; cancellation as easy as sign-up)
- [ ] Tax/merchant-of-record review for target jurisdictions

## Public API access (user-owned clients)

- [ ] API keys shipped per Phase 5 checklist (scopes, hashing, rotation, rate limits)
- [ ] Public API docs with auth, rate limits, and error semantics
- [ ] Versioned API contract; deprecation policy for breaking changes
- [ ] API access gated by subscription entitlement
- [ ] Abuse monitoring per key (volume anomalies, scraping patterns)
- [ ] Terms of service explicitly cover third-party client access

## Data portability

- [ ] Self-service account export (per Phase 8 checklist)
- [ ] Export covered by terms/privacy policy as a user right (GDPR/CCPA portability)
- [ ] Export completeness tested against the data inventory
- [ ] Account deletion covers derived data, embeddings, jobs, and retained objects (SECURITY.md)

## Launch readiness

- [ ] Pricing page and payment flows reviewed for correctness
- [ ] Support channel and SLA expectations defined
- [ ] Cost per active user measured against price (recording, transcription, AI calls)
- [ ] Billing failure playbooks (provider outage, refund abuse)

### Phase 10 exit gate

- [ ] A paying user can subscribe, connect an MCP/API client, export all their data, and cancel — entirely self-service, with correct entitlement enforcement at every step.

# Definition of Done for every production feature

- [ ] Acceptance criteria met
- [ ] Failure modes handled
- [ ] Security/privacy implications reviewed
- [ ] Unit tests added
- [ ] Integration tests added where boundaries exist
- [ ] Critical UI flow test added when applicable
- [ ] Core business logic remains >=90% covered
- [ ] Logs contain no sensitive data
- [ ] Metrics/observability added when operationally useful
- [ ] Performance impact measured for capture/processing hot paths
- [ ] Documentation / ADR updated when architecture changes
- [ ] CI green
