# AI Daily Memory

AI Daily Memory turns the Android phone and Wear OS watch you already carry into a private, searchable memory of your day.

The product is inspired by dedicated AI recorders such as PLAUD NotePin, but removes the need for another physical device. The phone is the reliable recording engine. The watch is the lightweight control surface. AI turns captured speech into useful, evidence-backed information such as decisions, action items, ideas, commitments, and highlights.

> **Core promise:** Start your day, live normally, and later remember what mattered.

---

## Product Thesis

This is **not** primarily a transcription app.

The product should evolve through four layers:

```text
Capture
   ↓
Understand
   ↓
Remember
   ↓
Retrieve
```

A successful product should answer questions such as:

- What did I decide today?
- What did someone promise to follow up on?
- What tasks did I say I needed to do?
- What ideas did I mention while driving?
- What did we discuss about a project last week?
- What important moments did I explicitly highlight?

The differentiator is not recording audio. The differentiator is building a trustworthy personal memory system over time.

---

# Architecture Priorities

The project follows these rules in order:

1. **Reliable capture before AI**
2. **Offline-first before cloud-first**
3. **Evidence before inference**
4. **Privacy before convenience**
5. **Simple architecture before scale architecture**
6. **Measured bottlenecks before optimization**

If the phone cannot reliably capture a normal day, the rest of the platform has no value.

---

# Android-First Scope

Version 1 targets:

- Android phone
- Wear OS watch
- Initial watch target: OnePlus Watch 2

iOS and Apple Watch are intentionally postponed until the Android experience is proven.

---

# MVP User Experience

The primary workflow should remain extremely simple.

```text
START MY DAY
      ↓
Phone records reliably
      ↓
Watch / Phone controls
Pause | Resume | Highlight | Stop
      ↓
Audio is processed
      ↓
TODAY
Summary
Highlights
Decisions
Action Items
Ideas
Commitments
      ↓
Ask My Memory
```

The product should be useful even if the user never opens a chat interface.

The main experience should be a **timeline**, not a chatbot.

---

# Primary Product Surfaces

## 1. Start My Day

Begins a user-visible recording session.

The user should always know whether capture is active.

## 2. Highlight

A first-class feature.

The user knows what is important better than the AI does.

A highlight should capture the timestamp and enough nearby transcript context to make the moment useful later.

Highlights should receive stronger ranking when generating summaries or answering questions.

## 3. Quick Note

A short voice-memory flow for users who do not want continuous recording.

Example:

> Remember that I need to replace the air filter Saturday.

The app records a short note, transcribes it, extracts the useful information, then stops automatically.

## 4. Pause Now

Privacy must be immediate.

One tap should pause recording without a confirmation dialog.

Later versions may support:

- Pause for 5 minutes
- Pause for 15 minutes
- Pause for 1 hour

## 5. Daily Timeline

The home screen should prioritize events over chat.

Example:

```text
TUESDAY

8:03 AM
Started Day

8:42 AM
Daily Standup

Decision
Use the new consumer implementation.

Action
Review failed messages.

★ Highlight

11:16 AM
Conversation

Idea
Add a watch shortcut.

12:30 PM
Paused

1:04 PM
Resumed
```

## 6. Ask My Memory

Conversational retrieval comes after reliable capture and useful timeline generation.

Example questions:

- What did we decide about Kafka yesterday?
- What tasks did I mention this week?
- What did John say he would follow up on?
- What ideas did I have while driving?

Answers should point back to supporting transcript timestamps whenever possible.

---

# MVP Responsibilities

## Android Phone

The phone is the authoritative recording device.

Responsibilities:

- Start, pause, resume, and stop a recording session
- Run a user-visible microphone foreground service
- Persist recording state
- Store audio locally first
- Recover from process death
- Recover usable audio after interruption
- Detect storage pressure
- Track recording health
- Queue uploads when offline
- Upload resumably when connectivity returns
- Display transcripts and extracted memories
- Display daily timeline
- Display highlights
- Display processing state

## Wear OS

The watch is a controller, not the primary recorder.

Responsibilities:

- Start recording
- Pause / resume
- Stop
- Highlight
- Trigger Quick Note
- Show recording state
- Show session duration
- Show phone connectivity

The watch must display **acknowledged phone state**, not assume a command succeeded.

Standalone watch recording is a later experiment.

---

# Capture Health

This is a core feature, not debug information.

The user needs confidence that their memory system is actually working.

Example:

```text
● Recording

3h 42m

Last audio saved: 4 sec ago
Watch connected
Pending upload: 12 min
Local storage: Good
Battery: 62%
```

If capture fails:

```text
⚠ Recording interrupted at 2:14 PM

Tap to resume
```

The product must never silently pretend it recorded something that it did not.

---

# Recording State Machine

Recording must be explicitly modeled and persisted.

```text
IDLE
  |
  v
STARTING
  |
  v
RECORDING
  |   |   interruption
  |   v
  | INTERRUPTED
  |   |
  |   v
  | RECOVERING
  |   |
  +---+
  |
  +--> PAUSED
  |      |
  |      v
  |   RECORDING
  |
  v
STOPPING
  |
  v
COMPLETED
```

After app restart, the client should know whether the previous session:

- completed normally
- was interrupted
- contains recoverable audio
- has pending uploads
- contains partially processed segments

---

# Phase 0: Prove Capture Before Building the Backend

The first milestone intentionally excludes AI.

Build:

- Android application shell
- microphone permission flow
- foreground recording service
- recording state machine
- local segmented audio
- encrypted local storage
- Room persistence
- notification controls
- pause / resume / stop
- highlight markers
- capture-health UI
- crash recovery
- basic battery / thermal telemetry

Then carry the app during real days.

Required endurance tests:

- 1 hour
- 4 hours
- 8 hours
- 12 hours

Measure:

- battery drain
- CPU usage
- memory use
- device temperature
- storage growth
- dropped audio
- microphone interruptions
- Android process behavior
- recovery success

Do not commit to continuous all-day capture until real device testing proves that battery, thermal behavior, and reliability are acceptable.

If continuous recording is too expensive, the product should pivot toward selective capture, Quick Notes, meetings, or smarter listening modes rather than forcing the architecture.

---

# Audio Architecture

Avoid one multi-hour audio file.

Use short crash-resistant local segments.

```text
Microphone
   |
   v
Local audio segments
   |
   v
Encrypted local storage
   |
   v
Optional speech analysis
   |
   v
Logical upload batches
   |
   v
Temporary cloud processing
   |
   v
Verified transcript
   |
   v
Delete temporary audio
```

## Audio Retention

Raw audio is a temporary processing artifact by default.

Default behavior:

1. Record locally.
2. Upload only what needs processing.
3. Transcribe successfully.
4. Persist the transcript.
5. Extract structured information.
6. Confirm persistence.
7. Delete temporary cloud audio.
8. Delete local audio after the configured recovery window.

Long-term audio retention is **not** part of the MVP.

It can later become an opt-in or paid capability.

---

# Speech Detection

Local voice activity detection may reduce transcription cost.

However, it must not be trusted blindly.

A false negative could remove the exact sentence the user wanted to remember.

Therefore:

- VAD is an optimization, not the source of truth
- false-negative rate must be measured
- surrounding audio buffers should be retained around detected speech
- high-fidelity mode should remain possible
- speech filtering should be introduced only after capture reliability is proven

---

# Evidence-Backed Memory

The AI must not silently convert uncertain speech into facts.

Use three conceptual layers.

## Layer 1 — Evidence

Timestamped transcript segments.

This is the immutable evidence layer.

AI corrections must never rewrite the original transcript as though the model had heard something different.

## Layer 2 — Structured Understanding

Examples:

- event
- decision
- action item
- idea
- commitment
- highlight
- question
- person reference

Each generated object should include:

- source transcript reference
- timestamps
- extraction version
- confidence / review state

## Layer 3 — Long-Term Memory

Later versions may generate:

- daily summaries
- weekly summaries
- recurring commitments
- project context
- personal knowledge
- long-term retrieval

Long-term memory is not required for the first usable version.

---

# Human Correction Is a First-Class Feature

AI extraction will be wrong sometimes.

Every generated item should be correctable.

Example:

```text
Call Mike tomorrow

[Correct] [Edit] [Not a task]
```

Do not expose raw model probabilities as the primary UX.

Prefer human-friendly states such as:

- Confirmed
- Possible
- Needs review

Corrections should be stored separately from the original evidence.

They can later improve prompts, evaluation datasets, and extraction quality.

---

# Speaker Handling

Do not build voice biometrics for the MVP.

Start with:

- Me
- Speaker 2
- Speaker 3

Allow users to optionally rename speakers.

Speaker diarization and speaker identity are separate problems.

The system must never assume that a person's name mentioned in a conversation proves that person is the current speaker.

---

# Context

A future version may support lightweight contexts such as:

- Personal
- Work
- Meeting
- Private

Do not require context selection every time a recording starts.

Context should reduce friction, not add setup work.

Private mode may later support:

- immediate pause
- local-only processing
- exclusion from long-term memory

---

# Processing Versioning

Every derived artifact should record how it was produced.

Example metadata:

```text
transcription_provider
transcription_model
transcription_version

extraction_model
extraction_prompt_version

memory_schema_version
created_at
```

This allows future models or prompts to reprocess old transcripts without modifying original evidence.

Versioning is inexpensive to add now and difficult to retrofit later.

---

# Simplified MVP Backend

Do not overbuild infrastructure before the product works.

Initial architecture:

```text
Android
   |
   v
FastAPI
  /    v     v
Postgres   Temporary R2
   |
   v
Worker
   |
   v
AI Provider
```

That is enough for the first beta.

## Intentionally excluded from the initial backend

Do not require these at the beginning:

- Redis
- Dramatiq
- Kafka
- Kubernetes
- dedicated vector database
- multiple transcription providers
- multiple LLM providers
- complex provider routing
- read replicas
- regional processing

Introduce them only when a measured problem requires them.

A database-backed job table is acceptable for the earliest worker pipeline.

---

# Technology Stack

## Android

- Kotlin
- Jetpack Compose
- Coroutines / Flow
- Room / SQLite
- WorkManager
- Android Keystore
- Native Android microphone APIs
- Foreground service
- OkHttp / Retrofit or Ktor
- Opus where practical

## Wear OS

- Kotlin
- Compose for Wear OS
- Wear OS Data Layer APIs

## Backend

- Python
- FastAPI
- Pydantic
- SQLAlchemy
- Alembic
- Pytest

## Database

- PostgreSQL
- Supabase initially if it reduces operational work

## Authentication

- Supabase Auth or another managed auth provider

Auth is required when multi-user testing begins.

It does not need to block local Phase 0 development.

## Temporary Audio Storage

- Cloudflare R2
- private bucket
- short-lived signed access
- lifecycle deletion as a safety net

## AI

Start with one transcription provider and one LLM provider.

Use thin internal interfaces around provider-specific calls, but do not build a complex plugin framework before a second provider is actually needed.

## Hosting

Initial target:

- Railway for FastAPI
- Railway worker
- Supabase PostgreSQL
- Cloudflare R2

## Observability

Add early:

- Sentry
- structured application logs

Add OpenTelemetry only when distributed tracing becomes useful.

Never include full audio, transcript contents, access tokens, or private prompts in ordinary telemetry.

## CI/CD

- GitHub Actions
- Android unit tests
- Android instrumentation tests
- backend tests
- linting
- formatting
- build verification

---

# Suggested Data Model

Keep the schema small.

## users

Account-level information.

## devices

Registered phones and watches.

## recording_sessions

One logical capture session.

Suggested fields:

- id
- user_id
- device_id
- started_at
- ended_at
- status
- created_at

## audio_segments

Temporary segment metadata.

Suggested fields:

- id
- recording_session_id
- sequence
- started_at
- duration
- checksum
- local_state
- upload_state
- processing_state
- storage_key

## transcript_segments

Evidence layer.

Suggested fields:

- id
- recording_session_id
- audio_segment_id
- started_at
- ended_at
- text
- speaker_label
- confidence

## highlights

User-marked moments.

## memory_items

One table can initially represent derived objects.

Suggested fields:

- id
- recording_session_id
- type
- title
- body
- owner
- status
- confidence_state
- source_transcript_id
- extraction_version
- created_at

Possible types:

- decision
- action
- idea
- commitment
- event
- question

## daily_summaries

Generated session or day summary.

## processing_jobs

Durable asynchronous work state.

This schema can split into more specialized tables later if actual usage demands it.

---

# Initial Search Strategy

Do not introduce vector search until users need historical semantic retrieval.

MVP search can use:

- PostgreSQL text search
- date filters
- type filters
- session filters
- speaker labels

Add embeddings / pgvector when **Ask My Memory** begins requiring semantic retrieval across significant history.

---

# Reliability Rules

## Local first

The backend must never be required for recording to continue.

## Stable IDs

Every session and segment receives a stable identifier on-device.

## Idempotency

Retrying uploads or jobs must not create duplicate transcripts or memory items.

## Checksums

Audio segments should use content hashes for integrity validation.

## Explicit acknowledgement

The client deletes recoverable local audio only after the server confirms required processing state.

## No silent failure

Every capture interruption must become visible to the user.

## Bounded retries

Failed backend jobs should retry with limits.

Repeated failures should become visible rather than retry forever.

---

# Privacy and Security

This product can capture highly sensitive conversations.

Security is part of the product design.

## Device

- encrypt temporary audio at rest
- protect keys with Android Keystore
- require explicit microphone permission
- show recording state
- make pause / stop immediately available
- support user deletion
- protect recovered sessions

## Backend

- TLS everywhere
- private object storage
- short-lived signed URLs
- authentication on every private endpoint
- authorization on every user resource
- secrets outside source control
- no access tokens in logs
- no raw transcripts in ordinary logs
- retention jobs for temporary audio

## AI Providers

Users should know when audio or transcript data is sent to a third-party AI provider.

Do not claim end-to-end encryption if cloud services must decrypt audio or text to process it.

## Recording Consent

Recording laws and workplace policies vary by jurisdiction.

The product should:

- keep recording state visible
- make pausing easy
- provide consent guidance
- avoid covert-recording UX

---

# Cost Controls

Measure cost before optimizing architecture.

Track:

- recorded minutes
- uploaded minutes
- transcribed minutes
- transcription cost
- LLM input tokens
- LLM output tokens
- temporary storage
- failed retries

The most important optimization is:

```text
Recorded time
    ≠
Transcribed time
```

Eight hours of capture may contain far less than eight hours of meaningful speech.

Potential optimization path:

```text
Audio
  ↓
Measured speech detection
  ↓
Transcription
  ↓
Small extraction model
  ↓
Structured memory
```

Do not send every transcript through a large reasoning model.

Use larger models only where their quality produces measurable value.

---

# Scaling Strategy

Scale from observed bottlenecks.

## Stage 0 — Personal Prototype

- Android only
- local recording
- no backend dependency
- battery and reliability testing

## Stage 1 — Early Beta

- FastAPI
- PostgreSQL
- temporary R2
- one worker
- one transcription provider
- one LLM provider
- Wear OS controller

## Stage 2 — Product Validation

Introduce only as needed:

- stronger asynchronous queue
- multiple workers
- embeddings
- pgvector
- quotas
- billing
- autoscaling
- better caching

## Stage 3 — Growth

Possible future changes:

- separate transcription workers
- separate AI workers
- managed durable queue
- database partitioning
- read replicas
- regional data placement
- dedicated search infrastructure

No migration should happen merely because a theoretical user count was reached.

---

# Repository Structure

Keep the monorepo understandable.

```text
AI-Daily-Memory/
├── apps/
│   ├── android/
│   └── wear/
│
├── backend/
│   ├── app/
│   ├── migrations/
│   └── tests/
│
├── contracts/
│   └── openapi/
│
├── docs/
│   ├── architecture/
│   ├── product/
│   └── adr/
│
├── .github/
│   └── workflows/
│
└── README.md
```

Create additional modules only when the codebase actually needs them.

Use Architecture Decision Records in `docs/adr/` for decisions that are expensive to reverse.

---

# API Strategy

Use OpenAPI as the contract between Android and the backend.

Initial endpoint groups should remain small:

```text
/sessions
/uploads
/transcripts
/highlights
/memory
/summary
/health
```

Later:

```text
/auth
/devices
/search
/ask
/usage
```

The backend should issue short-lived upload instructions.

Permanent object-storage credentials must never exist in the mobile application.

---

# Testing Strategy

This application needs heavy reliability testing.

## Android Unit Tests

Test:

- recording state machine
- segment lifecycle
- interruption recovery
- upload state
- duplicate prevention
- cleanup rules
- storage-pressure behavior

## Instrumentation Tests

Test:

- microphone permission
- foreground service
- app backgrounding
- process recreation
- notification controls
- phone reboot recovery where practical
- Wear OS communication

## Physical Device Endurance Tests

Required.

Measure on real hardware:

- battery drain
- temperature
- CPU
- memory
- storage
- audio gaps
- interruption recovery
- microphone routing

## Fault Injection

Simulate:

- no network
- slow network
- backend timeout
- partial upload
- corrupted segment
- duplicate upload
- worker crash
- AI provider timeout
- Android process death
- low storage

## AI Evaluation

Create a small human-reviewed evaluation dataset.

Measure extraction quality for:

- decisions
- explicit tasks
- possible tasks
- commitments
- ideas
- highlights

False positive tasks and commitments should be treated seriously.

---

# Engineering Targets

These are validation targets, not guarantees.

- No permanent loss of successfully persisted local segments during controlled tests
- No user-visible duplicate transcript segments
- Recover interrupted sessions whenever usable audio remains
- No silent recording failure
- Predictable battery impact
- Predictable processing cost
- Every derived memory can trace back to evidence
- AI corrections never overwrite original evidence

---

# Development Roadmap

## Phase 0 — Capture Reliability

Build only the Android recorder.

Exit condition:

> The phone can reliably capture a normal working day with acceptable battery, thermal, storage, and recovery behavior.

## Phase 1 — Upload + Transcription

Add:

- API
- temporary cloud upload
- worker
- transcription
- transcript persistence
- retries
- audio deletion

Exit condition:

> Every acknowledged upload either becomes a transcript or enters a visible recoverable failure state.

## Phase 2 — Daily Timeline

Add:

- transcript grouping
- events
- highlights
- basic summary
- decisions
- actions
- ideas
- commitments
- correction UX

Exit condition:

> The daily timeline consistently surfaces information the user would otherwise forget.

## Phase 3 — Wear OS

Add:

- phone communication
- start
- pause
- resume
- stop
- highlight
- Quick Note
- capture-health status

Exit condition:

> The watch reliably controls the phone during normal daily use.

## Phase 4 — Ask My Memory

Add:

- text search
- metadata filters
- historical retrieval
- evidence-backed answers

Add embeddings only if plain retrieval is insufficient.

Exit condition:

> Users can retrieve useful information from prior days with supporting evidence.

## Phase 5 — Long-Term Memory

Only after daily use proves valuable.

Potential features:

- semantic search
- recurring commitments
- project memory
- weekly summaries
- personalized retrieval
- longer-term relationships between people, topics, decisions, and tasks

## Phase 6 — Production Hardening

Add:

- account lifecycle
- export
- deletion
- quotas
- billing if needed
- retention settings
- security review
- backup / recovery testing
- load testing

## Phase 7 — iOS + Apple Watch

Build only after Android product-market fit and recording architecture are proven.

---

# MVP Non-Goals

Do not build initially:

- iOS
- Apple Watch
- browser dashboard
- permanent audio archive
- standalone watch recording
- voice biometric identity
- complex speaker recognition
- automatic calendar mutations
- automatic email sending
- autonomous agents
- subscriptions before costs justify them
- Kubernetes
- Kafka
- Redis unless job volume requires it
- dedicated vector database
- pgvector before semantic search is needed
- multiple AI provider routing
- complex microservices
- long-term memory graph

These are future options, not current requirements.

---

# Architecture Decision Gates

Major features should pass explicit gates before implementation.

## Gate A — Continuous Capture

Proceed only if real-device testing shows acceptable:

- battery drain
- thermal behavior
- reliability
- audio quality

## Gate B — Cloud Processing

Proceed only if:

- transcript quality is useful
- per-user processing cost is understood
- retry behavior is reliable

## Gate C — AI Memory

Proceed only if extracted decisions, tasks, and commitments are measurably useful and correctable.

## Gate D — Semantic Search

Add embeddings only when ordinary database search is demonstrably insufficient.

## Gate E — Infrastructure Expansion

Add queues, caching, replicas, or service separation only after metrics identify a bottleneck.

---

# Definition of Success

The first meaningful product milestone is deliberately simple:

> Start AI Daily Memory in the morning, live a normal day, and later discover at least one useful decision, idea, commitment, action item, or highlighted moment that you otherwise would have forgotten.

If that happens consistently, the product deserves deeper AI, longer-term memory, additional devices, and more infrastructure.

Until then, the architecture should remain focused on reliable capture, trustworthy evidence, simple processing, and low operational cost.
