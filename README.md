# AI Daily Memory

AI Daily Memory turns the Android phone and Wear OS watch you already carry into a privacy-conscious, searchable memory of your day.

The product is inspired by dedicated AI recorders such as PLAUD NotePin, but avoids requiring another physical device. The phone handles reliable audio capture and processing coordination, while the watch acts as a lightweight remote for recording, pausing, and highlighting important moments.

## Product Vision

The goal is not to build another transcription app.

The goal is to create an AI-powered daily memory system that can answer questions such as:

- What did I decide today?
- What did someone promise to follow up on?
- What tasks did I say I needed to do?
- What ideas did I mention while driving?
- What did we discuss about a project last week?
- What important moments did I highlight?

The system should transform raw conversations into structured, evidence-backed memories while remaining reliable, affordable, and privacy-conscious.

## MVP Scope

Version 1 is Android-first.

### Supported devices

- Android phone
- Wear OS watch
- Initial target device: OnePlus Watch 2

### Android app responsibilities

- Start, pause, resume, and stop a daily recording session
- Capture audio using a user-visible microphone foreground service
- Continue recording while the app is not in the foreground when Android permits it
- Store encrypted audio locally
- Recover gracefully from crashes, connectivity loss, calls, microphone interruptions, and process death
- Detect speech locally when practical
- Upload audio in resilient chunks
- Receive and display transcripts
- Display daily summaries, decisions, action items, highlights, and timeline events
- Search historical memories
- Ask questions about previous days

### Wear OS app responsibilities

The watch should remain intentionally simple.

Primary actions:

- Start recording
- Pause or resume
- Stop recording
- Highlight the current moment
- Show recording status
- Show session duration
- Show connectivity state

The phone is the authoritative recording controller. The watch sends commands and displays acknowledged state from the phone.

Standalone watch recording can be explored later.

## Core UX

The intended daily workflow is:

1. Open the Android app or use the watch.
2. Tap **Start My Day**.
3. Go about the day normally.
4. Tap **Highlight** when something important happens.
5. Pause recording when appropriate.
6. End the session.
7. Receive a structured summary of the day.

Example output:

### Daily Summary

**Decisions**

- Use the new implementation for a work project.

**Action Items**

- Investigate a failed process.
- Order household supplies.

**People**

- John agreed to investigate a payment issue.

**Ideas**

- Build an AI daily memory system using phones and watches.

**Highlights**

- Important user-marked moments linked back to the supporting transcript.

## Architecture Principles

The system should follow these principles.

### 1. Offline-first capture

Recording must not depend on the backend or internet connection.

The Android device should persist audio and recording state locally first, then synchronize later.

### 2. Reliable before intelligent

Losing audio is worse than generating a late summary.

Capture reliability is the first engineering milestone.

### 3. Evidence-backed AI

Every extracted memory, decision, task, or commitment should be traceable to the transcript segment that produced it.

The AI should distinguish between:

- Confirmed decisions
- Explicit commitments
- Suggested tasks
- Ideas
- Questions
- Uncertain interpretations

### 4. Temporary audio by default

Raw audio is a processing artifact, not the long-term product.

Default behavior:

1. Record locally.
2. Upload for processing.
3. Transcribe successfully.
4. Extract structured memories.
5. Confirm persistence.
6. Delete temporary cloud audio.
7. Delete local audio after server acknowledgement and the configured recovery window.

Long-term audio retention can become an optional feature later.

### 5. Start simple, scale deliberately

Do not begin with Kubernetes, Kafka, or dozens of microservices.

Start with a modular backend and separate worker processes.

Scale individual components only after production measurements justify it.

## Proposed Technology Stack

### Android

- Kotlin
- Jetpack Compose
- Android foreground service for microphone capture
- Room / SQLite
- WorkManager
- Android Keystore
- Coroutines / Flow
- Retrofit or Ktor client
- Opus audio where practical

### Wear OS

- Kotlin
- Compose for Wear OS
- Wear OS Data Layer APIs

### Backend

- Python
- FastAPI
- Pydantic
- SQLAlchemy
- Alembic
- Pytest

### Database

- PostgreSQL
- Supabase initially
- pgvector for semantic search
- PostgreSQL full-text search

### Authentication

- Supabase Auth

### Temporary audio storage

- Cloudflare R2
- Private bucket
- Short-lived signed access
- Lifecycle deletion as a safety net

### Background jobs

Initial design:

- Redis
- Dramatiq
- PostgreSQL outbox / durable processing state

Redis is transport, not the source of truth for job state.

### AI layer

Provider abstractions should exist from the beginning.

#### TranscriptionProvider

Possible implementations:

- OpenAI
- Deepgram
- Google
- Local models later

#### LLMProvider

Possible implementations:

- OpenAI
- Anthropic
- Gemini
- Local models later

#### EmbeddingProvider

Provider-neutral interface for future cost and model flexibility.

No provider-specific SDK calls should be spread throughout application code.

### Hosting

Initial deployment:

- Railway for FastAPI
- Railway worker service
- Railway Redis or another managed Redis
- Supabase PostgreSQL
- Cloudflare R2

### Observability

- Sentry
- OpenTelemetry
- Structured application logs
- No raw audio or transcript content in ordinary logs

### CI/CD

- GitHub Actions
- Android unit tests
- Android instrumentation tests
- Backend tests
- Static analysis
- Formatting
- Build verification

## High-Level Architecture

```text
Wear OS Watch
     |
     | Start / Pause / Highlight / Stop
     v
Android Phone
     |
     | Native audio capture
     | Encrypted local storage
     | Durable session state
     | Upload queue
     v
Ingestion API
     |
     +-------------------+
     |                   |
     v                   v
Temporary R2        PostgreSQL
Audio Storage       Metadata
     |
     v
Durable Job Pipeline
     |
     +--> Speech detection
     +--> Transcription
     +--> Speaker diarization
     +--> Event extraction
     +--> Decision extraction
     +--> Action-item extraction
     +--> Embeddings
     +--> Daily summary
     |
     v
PostgreSQL + pgvector
     |
     v
Android App
Timeline / Search / Ask AI / Daily Summary
```

## Recording State Machine

Recording behavior must be explicitly modeled.

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

Every important transition should be persisted locally.

The app should be able to determine after restart whether a previous session:

- completed normally
- was interrupted
- contains recoverable audio
- still has pending uploads
- has partially processed chunks

## Audio Pipeline

Avoid a single multi-hour file.

Use crash-resistant local segments and adaptive batching.

```text
Microphone
   |
   v
Small local segments
   |
   v
Speech / silence analysis
   |
   v
Adaptive logical chunks
   |
   v
Encrypted local queue
   |
   v
Background upload
   |
   v
Temporary R2 object
   |
   v
Transcription
   |
   v
Verified transcript
   |
   v
Delete temporary audio
```

The implementation must balance:

- transcription cost
- request overhead
- crash recovery
- context continuity
- battery usage
- network usage

Voice activity detection may reduce unnecessary transcription, but should never be trusted blindly enough to silently discard potentially important speech.

## Data Model

Initial entities should include:

### users

Account and subscription information.

### devices

Registered Android and Wear OS devices.

### recording_sessions

One logical memory session.

Example fields:

- id
- user_id
- started_at
- ended_at
- status
- device_id
- duration
- created_at

### audio_chunks

Temporary processing metadata.

Example fields:

- id
- recording_session_id
- sequence
- local_started_at
- duration
- checksum
- storage_key
- upload_status
- transcription_status
- processing_status

### transcript_segments

Evidence layer.

Example fields:

- id
- recording_session_id
- audio_chunk_id
- started_at
- ended_at
- text
- speaker_id
- confidence

### highlights

User-created important moments.

### speakers

Diarized speakers and user-confirmed identities.

Do not assume diarized speakers are correctly identified by real-world name.

### timeline_events

Structured events extracted from transcripts.

### decisions

Evidence-backed decisions.

### action_items

Tasks extracted from conversation.

Useful fields:

- owner
- description
- status
- confidence
- source_transcript_id
- due_date

### memories

Longer-lived structured facts or events.

### daily_summaries

Generated summaries for a particular date or session.

### embeddings

Semantic-search representations linked to source records.

### processing_jobs

Durable representation of asynchronous work.

## Memory Architecture

Use three layers.

### Layer 1: Evidence

Original timestamped transcript segments.

This is the source of truth for AI-derived information.

### Layer 2: Structured understanding

Examples:

- decisions
- action items
- ideas
- meetings
- topics
- people
- highlights

Every item should point back to supporting evidence.

### Layer 3: Long-term memory

Examples:

- daily summaries
- weekly summaries
- project context
- recurring commitments
- historical semantic search

Long-term memories should be derived from evidence-backed information rather than repeatedly summarizing summaries.

## Search Strategy

Start with hybrid search.

Use:

- PostgreSQL full-text search
- pgvector semantic similarity
- metadata filters
- dates
- speakers
- projects
- event types

Example query:

> What did John say about Kafka last week?

Search should combine:

1. date filtering
2. person filtering if known
3. semantic similarity
4. keyword matching
5. supporting transcript retrieval

Answers returned by Ask AI should include source timestamps wherever practical.

## Reliability Requirements

The Android client should survive:

- temporary internet loss
- app backgrounding
- process recreation
- phone reboot
- Bluetooth/watch disconnect
- microphone interruption
- incoming calls
- low storage
- backend outage
- transcription provider outage
- duplicate upload attempts
- duplicate worker execution

### Important design rules

#### Idempotency

Every chunk should have a stable unique ID.

Repeated upload or processing requests must not create duplicate transcripts or memories.

#### Checksums

Audio segments should have hashes to validate integrity and assist duplicate detection.

#### Durable job state

PostgreSQL holds authoritative job state.

Redis should not be the only record that processing needs to happen.

#### Outbox pattern

When database changes require asynchronous work, write an outbox event in the same transaction.

A dispatcher then publishes that work safely.

#### Dead-letter handling

Jobs that repeatedly fail must become visible for investigation rather than retrying forever.

## Privacy and Security

This application can capture extremely sensitive information.

Security is a core product requirement.

### Device

- Encrypt temporary audio at rest
- Store encryption keys through Android Keystore
- Require explicit microphone permission
- Clearly display recording state
- Offer fast pause and stop controls
- Support immediate deletion

### Backend

- TLS everywhere
- Private object storage
- Short-lived signed upload/download URLs
- Authentication on every request
- Authorization on every resource
- Row-level security where appropriate
- Secrets stored outside the repository
- Never log access tokens
- Never put full transcripts into standard application logs

### AI providers

Users should be informed when audio or transcript information is sent to third-party AI services.

Provider abstractions make it possible to add more private processing options later.

### Recording consent

The product should make recording status visible and provide guidance that recording laws and workplace policies vary by jurisdiction.

The product should not be designed for covert recording.

## Cost Controls

Cost must be measurable from day one.

Track per-user:

- recorded minutes
- uploaded audio duration
- transcribed minutes
- transcription cost
- LLM input tokens
- LLM output tokens
- embedding usage
- object storage
- retries

Implement:

- user quotas
- platform-wide spending limits
- provider cost reporting
- retry limits
- model routing

Do not send every transcript through an expensive model.

A typical processing strategy should be:

```text
Audio
  |
  v
Transcription
  |
  v
Small/cheap extraction model
  |
  +--> Decisions
  +--> Tasks
  +--> Topics
  +--> People
  +--> Events
  |
  v
Structured database
```

Larger models should primarily be used when the task actually requires stronger reasoning.

## Scaling Plan

### Stage 1 — MVP

Rough target: first internal users through early beta.

- One FastAPI deployment
- One worker deployment
- Redis
- PostgreSQL
- R2
- Android app
- Wear OS app

Priority:

- capture reliability
- useful summaries
- trustworthy memory extraction
- unit economics

### Stage 2 — Growth

As measured load requires it:

- separate transcription workers
- separate AI workers
- autoscale workers using queue depth
- database index tuning
- connection pooling
- stricter quotas
- improved caching

### Stage 3 — Larger scale

Only when production measurements justify it:

- managed durable queue
- independently scaled ingestion service
- database partitioning
- read replicas
- regional processing
- dedicated search infrastructure if PostgreSQL becomes insufficient

Do not migrate to complex infrastructure based only on projected user count.

## Repository Structure

Proposed monorepo:

```text
AI-Daily-Memory/
├── apps/
│   ├── android/
│   │   ├── app/
│   │   ├── core/
│   │   ├── data/
│   │   ├── recorder/
│   │   └── sync/
│   │
│   └── wear/
│       ├── app/
│       └── communication/
│
├── backend/
│   ├── app/
│   │   ├── api/
│   │   ├── auth/
│   │   ├── database/
│   │   ├── models/
│   │   ├── processing/
│   │   ├── providers/
│   │   ├── search/
│   │   └── workers/
│   │
│   ├── migrations/
│   └── tests/
│
├── contracts/
│   └── openapi/
│
├── docs/
│   ├── architecture/
│   ├── product/
│   └── decisions/
│
├── infrastructure/
│
├── .github/
│   └── workflows/
│
└── README.md
```

The exact module structure should evolve as implementation begins.

Avoid creating abstractions before they are needed.

## API Design

Use OpenAPI as the contract between Android and backend.

Generate or validate API models where practical rather than manually duplicating schemas.

Initial endpoint groups may include:

```text
/auth
/devices
/sessions
/uploads
/transcripts
/timeline
/highlights
/memories
/search
/ask
/users
/usage
```

Do not expose R2 credentials directly.

The server should create short-lived signed upload instructions.

## Testing Strategy

This product requires more than ordinary UI tests.

### Android unit tests

Test:

- state machine behavior
- segment lifecycle
- upload queue
- duplicate handling
- interruption recovery
- storage cleanup
- quota logic

### Instrumentation tests

Test:

- foreground service
- microphone permissions
- process recreation
- app backgrounding
- watch communication
- notification controls

### Physical device endurance tests

Essential.

Run real recording sessions for:

- 1 hour
- 4 hours
- 8 hours
- full working day

Measure:

- battery drain
- CPU usage
- thermal behavior
- memory
- storage growth
- dropped audio
- upload failures

### Fault injection

Simulate:

- network loss
- backend timeout
- Redis outage
- worker crash
- provider timeout
- corrupted chunk
- duplicate upload
- Android process death

### Backend tests

Use:

- Pytest
- integration tests with PostgreSQL
- queue tests
- idempotency tests
- migration validation
- provider contract tests

## Initial Engineering Targets

These are targets to validate, not promises.

- No permanent loss of acknowledged uploads in fault-injection tests
- No user-visible duplicate transcript segments
- Recover interrupted sessions whenever usable audio remains locally
- Daily summaries generated within a practical timeframe after final processing
- Predictable per-user processing cost
- Clear indication when a transcript or memory has low confidence

## Development Roadmap

### Phase 1 — Android recorder

Build:

- Android project
- permissions
- foreground service
- recording state machine
- local encrypted segments
- Room persistence
- recording notification
- session recovery

Exit condition:

A phone can reliably record a full day locally without any backend.

### Phase 2 — Upload and transcription

Build:

- authentication
- API
- signed temporary uploads
- durable processing jobs
- transcription abstraction
- transcript persistence
- retry behavior
- safe audio deletion

Exit condition:

Uploaded audio reliably becomes a transcript without duplicate or lost processing.

### Phase 3 — Daily memory

Build:

- timeline events
- decisions
- action items
- highlights
- summary generation
- evidence links
- basic search

Exit condition:

The generated daily memory is meaningfully useful and incorrect extractions can be traced back to source text.

### Phase 4 — Wear OS

Build:

- Wear OS application
- paired-device communication
- recording status
- start
- pause
- resume
- stop
- highlight

Exit condition:

The watch reliably controls the phone during real daily usage.

### Phase 5 — Ask AI

Build:

- hybrid search
- embeddings
- retrieval
- evidence-backed answers
- historical queries

Exit condition:

Users can reliably retrieve information from previous days.

### Phase 6 — Production hardening

Build:

- monitoring
- usage metering
- quotas
- privacy controls
- data export
- account deletion
- retention configuration
- backup and recovery procedures
- load tests

### Phase 7 — iOS and Apple Watch

Only after the Android experience proves useful and reliable.

## MVP Non-Goals

Do not include initially:

- iOS
- Apple Watch
- browser dashboard
- permanent audio archive
- standalone watch recording
- automatic calendar changes
- automatic email sending
- autonomous agents acting without approval
- Kubernetes
- Kafka
- dedicated vector database
- complex microservice architecture

These can be revisited after the core product proves itself.

## Definition of Success

The first meaningful product milestone is simple:

> Start a recording session in the morning, live a normal day, and receive a reliable, searchable summary of the conversations, decisions, ideas, action items, and highlights that mattered.

If that experience is genuinely useful and trustworthy, the rest of the platform can grow around it.
