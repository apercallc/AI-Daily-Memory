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
- [ ] Generate and commit Gradle wrapper using Gradle 9.6.0
- [ ] Generate and commit backend uv.lock
- [ ] Verify fresh clone builds on macOS, Linux, and CI
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
- [ ] Define semantic color tokens for recording, paused, warning, error, success
- [ ] Add typography scale and spacing tokens
- [ ] Support light/dark mode
- [ ] Support dynamic color where appropriate
- [ ] Meet WCAG/Android accessibility expectations
- [ ] 48dp minimum touch targets
- [ ] Content descriptions for non-text controls
- [ ] TalkBack test critical flows
- [ ] Support font scaling to at least 200%
- [ ] Add screenshot/golden testing only if it provides maintainable value

# Phase 0 — Capture reliability

Goal: prove reliable Android capture before building AI infrastructure.

## Permissions / foreground service

- [ ] Implement microphone permission education and request flow
- [ ] Handle permanently denied microphone permission
- [ ] Request notification permission where required
- [ ] Implement microphone foreground service
- [ ] Declare correct foreground-service type
- [ ] Create persistent recording notification
- [ ] Add notification actions: Pause, Resume, Stop, Highlight
- [ ] Ensure foreground service enters foreground within platform timeout
- [ ] Handle app background / screen-off behavior
- [ ] Handle OEM-specific battery restrictions without misleading the user

## Recording state machine

- [x] Create pure deterministic state machine
- [x] Unit-test legal transitions
- [x] Unit-test invalid transitions
- [ ] Wire state machine to Android service
- [ ] Persist state transitions before side effects where necessary
- [ ] Recover persisted session state after process death
- [ ] Recover after phone reboot when appropriate
- [ ] Ensure watch state always reflects acknowledged phone state
- [ ] Add state-machine property/fuzz tests if transition count expands materially

## Audio capture

- [ ] Select initial capture format after device benchmark
- [ ] Implement short crash-resistant local segments
- [ ] Add monotonic sequence IDs
- [ ] Add checksums
- [ ] fsync/close segments safely before marking persisted
- [ ] Avoid a single all-day audio file
- [ ] Handle microphone loss / audio route changes
- [ ] Handle incoming/outgoing calls
- [ ] Handle another app taking microphone focus
- [ ] Detect audio gaps
- [ ] Detect corrupt or zero-byte segments
- [ ] Never mark uncaptured time as successfully recorded

## Local security

- [ ] Encrypt local temporary audio
- [ ] Protect keys with Android Keystore
- [ ] Define key rotation / invalidation behavior
- [ ] Disable Android backup of sensitive app data
- [ ] Verify no sensitive files are exported via backup/debug tooling
- [ ] Delete local audio only after explicit server acknowledgement + recovery policy
- [ ] Secure-delete semantics documented honestly; do not promise physical flash erasure

## Storage pressure

- [ ] Track free disk space
- [ ] Define warning threshold
- [ ] Define hard-stop threshold
- [ ] Surface storage problems before recording fails
- [ ] Test device-low-storage conditions
- [ ] Bound local pending-upload storage

## Capture health

- [ ] Last segment successfully persisted
- [ ] Current session duration
- [ ] Pending upload duration
- [ ] Watch connectivity
- [ ] Available storage health
- [ ] Interruption indicator
- [ ] Battery/thermal telemetry in debug/internal builds
- [ ] Never show "Recording" unless capture is actually healthy

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
- [ ] Record benchmark results in docs/performance/

### Phase 0 exit gate

- [ ] A normal workday can be captured with acceptable battery, thermal, storage, audio quality, and recovery behavior.
- [ ] No silent capture failure in endurance/fault tests.
- [ ] Core recording-domain tests maintain >=90% line and branch coverage.

# Phase 1 — Upload + transcription

## API foundation

- [x] FastAPI health endpoint scaffold
- [x] Strict Python lint/type/test scaffold
- [ ] Define API versioning policy (/v1)
- [ ] Generate OpenAPI document in CI
- [ ] Validate breaking API changes
- [ ] Add request IDs / correlation IDs
- [ ] Add structured redacted logging
- [ ] Add auth once multi-user testing starts
- [ ] Add per-user authorization checks
- [ ] Add rate limiting before public exposure
- [ ] Add payload/body size limits
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

## Human correction

- [ ] Correct
- [ ] Edit
- [ ] Dismiss / Not a task
- [ ] Supersede old decisions
- [ ] Store correction separately from evidence
- [ ] Include confirmed corrections in future retrieval ranking

## Daily summary

- [ ] Build from structured memory + evidence
- [ ] Avoid summary-of-summary drift
- [ ] Link important items to source timestamps
- [ ] Regenerate when extraction version changes
- [ ] Bound LLM context

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

- [ ] Create Wear OS application module
- [ ] Pair communication using Wear OS Data Layer
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
- [ ] Read-only initial server
- [ ] ask_memory
- [ ] search_memory
- [ ] get_day
- [ ] get_timeline
- [ ] get_decisions
- [ ] get_action_items
- [ ] get_commitments
- [ ] get_highlights
- [ ] Per-user authorization
- [ ] Bounded result sizes
- [ ] No direct database access
- [ ] No bulk transcript export by default
- [ ] Audit sensitive tool access

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
