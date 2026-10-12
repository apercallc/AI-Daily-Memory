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

## Private device-test milestone (before any public testing)

This milestone means a privately sideloaded phone app and paired Wear OS companion for the
owner's own device testing. It is not a public beta or a Play release. Keep the product
acceptance criteria here even where they depend on later phase work; do not imply they are
implemented until their checks below and in the phase exit gates pass.

- [ ] Install and upgrade the phone + watch builds signed with the same key; verify pairing
  and reconnection on the target Android phone and Wear OS watch.
- [ ] Start, pause, resume, stop, and mark a report request from either the phone or watch;
  the phone remains the capture device and the watch only displays phone-acknowledged state.
  Watch-only microphone recording is out of scope unless a later ADR changes that decision.
- [ ] A watch command that cannot reach the phone or start its microphone service is visibly
  unconfirmed/failed; it never claims recording began. Phone notification always exposes state,
  pause, and stop.
- [ ] On Stop, offer explicit Keep or Delete. Keep retains the session's local evidence for
  processing/review under the selected retention setting; Delete removes audio and derived
  data for that session and confirms the result.
- [ ] A report request made during a session covers the complete session through Stop,
  including evidence-linked summary and action items. Show pending, partial, complete, and
  failed states; never present a partial result as complete.
- [ ] Decide and document the transcription/summarization execution mode before enabling it.
  The requested default is on-device inference with no audio or transcript sent off-device;
  benchmark model availability, quality, latency, storage, memory, and battery on the target
  phone. Any cloud fallback requires a separate, explicit opt-in and privacy disclosure.
- [ ] Exercise offline capture and processing, reconnection, watch disconnect/reconnect,
  denied/revoked microphone permission, phone call/mic contention, process death/reboot,
  low storage, low battery/thermal limits, corrupt or missing audio, and inference failure.
  Each case must preserve evidence or report a visible recoverable loss; never silently drop it.
- [ ] Verify a short end-to-end session on the target phone and watch, then complete the Phase
  0 device/endurance checks and the relevant Phase 1–3 exit gates before calling the workflow
  ready for regular personal testing.

The on-device inference request conflicts with ADR-0014's current assumption that speech
processing is transiently cloud-based. Before implementing that change, write a superseding
ADR and update consent, retention, failure recovery, and processing-cost decisions.

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
- [x] Shadow speech analysis: energy VAD + speech regions (core), charging-time job, per-session talk time, `segment_speech` totals (ADR-0015)
- [ ] Measure speech-active ratio per recorded hour (VAD in shadow mode; feeds the Gate B cost model) — tooling ready; device runs owed
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
- [x] Define API versioning policy (/v1)
- [ ] Generate OpenAPI document in CI (policy: docs/api/VERSIONING.md)
- [ ] Validate breaking API changes
- [x] Add request IDs / correlation IDs (validated inbound `X-Request-ID`)
- [x] Add structured redacted logging (JSON; field-name redaction; no exception messages or query strings)
- [x] Shared error envelope; validation errors never echo input
- [x] User-scoped schema from the first migration: user_id on every user-owned table, cascading FKs (ADR-0009)
- [ ] Backend is the only DB client; Supabase Data API disabled, RLS deny-all backstop (ADR-0009)
- [ ] Separate DB roles: migration owner, app (DML only), read-only
- [x] Bearer-token auth: hashed, scoped, revocable device tokens and API keys
- [x] Add auth once multi-user testing starts (account sign-in: POST /v1/signup + /v1/login,
  argon2 credentials with lockout; the Android app still expects a pasted token — no
  in-app signup form yet)
- [x] Add per-user authorization checks
- [ ] Add rate limiting before public exposure
- [x] Add payload/body size limits (declared and streamed; 413)
- [x] Add request timeouts

## Upload protocol

- [x] Stable client-generated session/segment IDs
- [x] Signed temporary upload instructions
- [x] Direct-to-object-storage upload where possible
- [x] SHA-256 integrity validation
- [x] Idempotent segment finalize endpoint
- [x] Resume/retry behavior
- [x] Duplicate upload behavior
- [x] Expired signed URL handling
- [x] Quota checks
- [x] Temporary object lifecycle policy: app deletes after transcription, on dead-letter, and via the retention sweep; bucket backstop rule documented (docs/operations/OBJECT_STORAGE.md)
- [ ] Configure the backstop lifecycle rule on the production bucket; verify SHA-256 checksum support on R2/MinIO
- [x] Object keys prefixed users/{user_id}/ for isolation and prefix deletion
- [x] Same idempotency key with a different checksum is a hard, operator-visible error (ADR-0006)

## Processing jobs

- [x] PostgreSQL-backed initial job table
- [x] Claim/lease semantics for workers
- [x] Idempotency key per processing stage
- [x] Bounded exponential backoff
- [x] Dead-letter / terminal-failure state
- [x] Operator-visible failure reason
- [x] Retry-safe provider calls where possible
- [x] Do not require Redis until measured throughput justifies it

## Transcription

- [ ] Per user-day cost model from Phase 0 speech-ratio measurements (Gate B input)
- [ ] Provider terms reviewed: no training on customer data, bounded retention, DPA, region
- [ ] Recording-consent legal review (all-party-consent jurisdictions) before any non-developer user
- [ ] Choose one provider for MVP
- [x] Abstract provider behind a thin interface
- [x] Preserve timestamps
- [x] Preserve provider/model/version metadata
- [x] Store confidence metadata if available
- [x] Handle provider timeout
- [x] Handle partial/invalid result
- [x] Define max chunk size
- [ ] Evaluate noisy/distant speech
- [ ] Evaluate multiple speakers
- [x] Delete temporary cloud audio only after verified transcript persistence
- [x] Re-transcription creates a new transcript revision; exactly one active revision per segment (ADR-0004 amendment)

## Phone upload client (ADR-0015)

- [x] Frame-aligned speech clips (ClipPlanner) with size + SHA-256, planned once per segment
- [x] Upload queue (Room) with create -> presigned PUT -> complete; every server error mapped
- [x] Charging + unmetered job with exponential backoff; pure UploadPolicy in core
- [x] Connect account (URL + device token, verified via /v1/me), encrypted token storage
- [x] Backlog consent: only audio recorded after connecting, unless the user opts in
- [x] Failed uploads and unreadable segments shown with a retry
- [x] Transcript screen per session (delivered transcripts, local time, speaker labels)
- [x] Account sign-in flow (backend: POST /v1/signup, /v1/login; `python -m app.admin` still
  works for operator use; Android UI still expects a pasted token, not its own signup form)

## Speech gating + night processing (ADR-0015)

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

## Storage modes (ADR-0014)

- [x] Account storage mode stored per user (phone_only default; reports_cloud; full_cloud)
- [x] Storage-mode switching UI (takes effect for uploads from now on; migrating data already
  stored under a previous mode is not implemented — see the two items below)
- [x] Transient processing hold: sealed copy until the phone acknowledges, max 7 days
- [x] Transcripts sealed to the uploading device's HPKE key (X25519/HKDF-SHA256/AES-256-GCM; Tink on the phone; interop tested both ways)
- [x] Invariant: local audio acknowledged (deletable) only after its transcript is stored on the phone
- [x] Expired, failed, or unknown deliveries -> the phone re-uploads from its kept audio (no silent loss)
- [x] One device key per token, no rotation; token revocation revokes the key and its sealed copies
- [x] Disconnect revokes the token server-side; a failed revoke is kept and retried when online
- [ ] Cross-day context packet (open action items) sent by the phone with the night job
- [ ] Mode switch phone -> cloud (from now / include history), idempotent by client IDs
- [ ] Mode switch cloud -> phone: download, verify, then server delete; never delete first
- [ ] Onboarding copy: "stored only on your phone" vs "never leaves your phone" (processing is cloud)
- [ ] "Last saved to Drive" indicator; phone-loss warning in phone-only mode

### Phase 1 exit gate

- [ ] Every acknowledged upload becomes a transcript or an explicit recoverable/terminal failure.
- [ ] Duplicate retries produce no duplicate transcript evidence.
- [x] Temporary audio retention/deletion is tested.

# Phase 2 — Daily timeline + structured memory

## Event grouping

- [x] Group transcript segments into useful event windows (session_id, ADR-0019 — reuses the
  client-assigned session, no new grouping table or boundary-detection logic)
- [x] Preserve exact evidence links (memory_item_evidence -> transcript_segments)
- [ ] Avoid over-fragmented 30-second memories
- [ ] Handle long meetings
- [ ] Handle short Quick Notes
- [ ] Handle event boundary corrections

## Structured extraction (ADR-0019; full_cloud accounts only — see ADR-0014)

- [x] Decisions
- [x] Actions
- [x] Commitments
- [x] Ideas
- [ ] Facts
- [x] Questions
- [ ] Highlights
- [x] Confidence/review state
- [x] Extraction/model/prompt version metadata
- [x] Never rewrite source transcript
- [x] Treat transcript text as untrusted model input: delimit as data, schema-validate output, no tool access
- [x] Memory items cite evidence many-to-many (memory_item_evidence)

## Human correction

- [ ] Correct
- [ ] Edit
- [x] Dismiss / Not a task (memory_items.dismissed_at)
- [ ] Supersede old decisions
- [ ] Store correction separately from evidence
- [ ] Include confirmed corrections in future retrieval ranking

## Daily summary / daily report

- [x] Daily report contract v1 + JSON Schema (contracts/) + Markdown template (docs/product/daily-report-example.md)
- [ ] Generate the report at bedtime (ReportTiming.dueAt) from structured memory + evidence —
  no scheduler exists; a report is built as soon as extraction has something (ADR-0019)
- [ ] Deliver it at wake time (ReportTiming.deliverAt); "partial" while audio is pending — the
  "partial" status is implemented (GET /v1/reports/{memory_day}); wake-time delivery is not
- [ ] Report screen in the app; share as Markdown (Google Drive via the share sheet) — not built;
  `render_markdown` exists and is unused by the app
- [ ] Optional nightly auto-save to Drive from the phone (`drive.file`, on-device auth; server never holds Drive tokens)
- [x] Build from structured memory + evidence (app/reports/build.py)
- [ ] Avoid summary-of-summary drift (no headline/summary text is generated at all yet)
- [x] Link important items to source timestamps
- [x] Regenerate when extraction version changes (rebuilt on every extraction run; a version
  bump alone does not retroactively re-run past sessions)
- [ ] Bound LLM context (no chunking/truncation for a very long day's transcript yet)

## Owner voice attribution (ADR-0016)

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
- [x] Wear module depends on core/recording for shared command/state types
- [x] Pair communication using Wear OS Data Layer (commands via MessageClient, state via DataClient; ADR-0008)
- [x] Ensure watch state always reflects acknowledged phone state (revision-ordered snapshots)
- [x] Command IDs deduplicated on the phone; retries return the cached outcome
- [x] Start
- [x] Pause
- [x] Resume
- [x] Stop
- [x] Highlight
- [x] Quick Note
- [x] Recording status
- [ ] Session duration
- [x] Phone-disconnected state
- [x] Command acknowledgement / timeout UX
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
- [x] API keys and MCP require a cloud storage mode (GET /v1/reports 403 `cloud_storage_required`
  for non-full_cloud accounts, surfaced as a clear tool error); key creation still offers no
  opt-in UI — full_cloud is today only reachable via the storage-mode switch, not a
  dedicated "connect MCP" flow
- [x] Read-only initial server (interfaces/mcp, FastMCP, stdio transport)
- [ ] ask_memory (needs semantic recall — Gate F, not crossed; would be speculative to build)
- [ ] search_memory (same as ask_memory)
- [x] get_day
- [x] get_timeline
- [x] get_decisions
- [x] get_action_items
- [x] get_daily_report
- [x] get_commitments
- [ ] get_highlights (no highlight data is populated yet — Phase 2's Highlights are unchecked)
- [x] Per-user authorization (the backend's own bearer-token scoping; MCP adds nothing new here)
- [x] Bounded result sizes (inherits the report contract's own list caps, ≤50 items each)
- [x] No direct database access (the MCP server's only dependency is the backend HTTP API)
- [x] No bulk transcript export by default (no tool ever returns transcript_segments)
- [ ] Transcript text returned as bounded, clearly quoted fields (prompt-injection hygiene for the user's agent) — moot today: no tool returns transcript text at all
- [ ] Audit sensitive tool access
- [x] API-key authentication for user-owned third-party clients (existing `kind="api"` tokens,
  `memory:read` scope by default)
- [x] Key scopes (read-only initially) and least-privilege defaults (API_KEY_SCOPES, app/auth.py)
- [ ] Key creation / rotation / revocation UI and API (still admin-CLI or self-service-signup
  device tokens only; no dedicated API-key management surface)
- [ ] Per-key and per-user rate limits
- [x] Key stored only as a secure hash (SHA-256, app/auth.py — predates this task)
- [x] MCP transport auth (authenticate the calling user, never a shared service key — one MCP
  process per account, that account's own API key from its own environment)

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
