# AI Daily Memory

AI Daily Memory turns the Android phone and Wear OS watch you already carry into a private, searchable memory of your day.

The phone is the reliable capture engine. The watch is the lightweight control surface. The backend turns captured speech into evidence-backed events, decisions, action items, ideas, commitments, and searchable memory.

> **Core promise:** Start your day, live normally, and later remember what mattered.

## Getting Started

The repository is now scaffolded for Android-first development.

See [TODO.md](TODO.md) for the executable delivery plan and [docs/quality/QUALITY_GATES.md](docs/quality/QUALITY_GATES.md) for release-quality standards.

Architecture companions: [edge-case catalog](docs/architecture/EDGE_CASES.md),
[performance & scale plan](docs/architecture/PERFORMANCE_AND_SCALE.md), and the
[architecture review](docs/architecture/REVIEW-2026-09.md).

### Prerequisites

- JDK 17
- Android SDK 37
- No global Gradle needed — use the committed wrapper (`./gradlew`, Gradle 9.7.1)
- Python 3.13
- uv for Python dependency/environment management

### Android

```bash
./gradlew :core:recording:check
./gradlew :apps:android:assembleDebug
./gradlew :apps:android:lintDebug
```

### Backend

```bash
cd backend
uv sync --locked --extra dev
# Tests need a throwaway PostgreSQL 16 database (its public schema is rebuilt each run).
export TEST_DATABASE_URL=postgresql+psycopg://USER:PASSWORD@localhost:5432/aidm_test
uv run pytest
uv run ruff check .
uv run mypy app tests
# Apply migrations, then run the API and the worker.
export DATABASE_URL=postgresql+psycopg://USER:PASSWORD@localhost:5432/aidm
uv run alembic upgrade head
uv run uvicorn app.main:app --reload
uv run python -m app.worker
```

Status: the Android app captures real audio offline (foreground service, encrypted segments,
recovery, daily rhythm, sessions, charging-time speech analysis). The backend has the Phase 1
upload protocol, user-scoped schema, token auth, and job queue. Transcription stays disabled
until Gate B (provider, cost model, and consent review), so no audio reaches any provider yet.


---

## Product Thesis

This is not primarily a transcription app.

```text
Capture
   ↓
Understand
   ↓
Remember
   ↓
Retrieve
```

The differentiator is not recording audio. It is building a trustworthy personal memory system that can efficiently answer questions such as:

- What did I decide today?
- What did someone promise to follow up on?
- What tasks did I say I needed to do?
- What ideas did I mention while driving?
- What did we discuss about a project last week?
- What important moments did I explicitly highlight?

---

## Project Execution

The architecture is implemented incrementally through [TODO.md](./TODO.md).

The repository is scaffolded as a monorepo with Android, Wear OS, backend, MCP/CLI interface boundaries, CI quality gates, security guidance, and Architecture Decision Records. Work should follow the phase gates in TODO.md rather than implementing later-stage infrastructure early.

# Architecture Priorities

1. **Reliable capture before AI**
2. **Offline-first before cloud-first**
3. **Evidence before inference**
4. **Recall quality is a system, not a single vector search**
5. **Privacy before convenience**
6. **One query engine for every client**
7. **Simple infrastructure before scale infrastructure**
8. **Measured bottlenecks before optimization**

If the phone cannot reliably capture a normal day, the rest of the platform has no value.

If the system cannot reliably retrieve the right memory later, storing the day has limited value.

---

# Android-First Scope

Version 1 targets:

- Android phone
- Wear OS watch
- Initial watch target: OnePlus Watch 2

iOS and Apple Watch are intentionally postponed until the Android experience is proven.

---

# MVP User Experience

```text
START MY DAY
      ↓
Phone records reliably
      ↓
Watch / Phone controls
Pause | Resume | Highlight | Quick Note | Stop
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

The main product experience should be a **timeline**, not a chatbot.

Chat is one way to retrieve memory, not the memory store itself.

---

# Primary Product Surfaces

## Start My Day

Begins a user-visible recording session.

## Highlight

A first-class importance signal.

The user knows what matters better than the model does. Highlights should receive a ranking boost during summary generation and retrieval.

## Quick Note

A short voice-memory flow.

Example:

> Remember that I need to replace the air filter Saturday.

The app records, transcribes, extracts the useful information, and stops automatically.

## Pause Now

One tap pauses capture immediately.

No confirmation dialog.

## Daily Timeline

The primary review surface.

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

## Ask My Memory

Examples:

- What did we decide about Kafka yesterday?
- What tasks did I mention this week?
- What did John say he would follow up on?
- What ideas did I have while driving?

Answers should return supporting timestamps and source references.

---

# Client Access Strategy

AI Daily Memory should eventually be accessible through multiple clients without duplicating retrieval logic.

```text
Android App
Web Frontend
CLI
MCP
External API
     │
     └──────────────┐
                    v
             Memory Query API
                    │
                    v
             Retrieval Engine
                    │
                    v
               PostgreSQL
```

All clients use the same authorization, retrieval, ranking, and evidence rules.

Never build separate memory logic for Android, web, CLI, or MCP.

## Initial order

1. REST API
2. Android Ask / Search
3. MCP
4. CLI
5. Web frontend

The web frontend should not block the core product.

---

# MCP Architecture

MCP should be a first-class access layer once memory retrieval works.

MCP must call the same Memory Query Service used by the other clients.

```text
MCP Client
   |
   v
MCP Server
   |
   v
Memory Query Service
   |
   v
PostgreSQL
```

Do not allow MCP to query the database directly.

MCP and the public API serve only server-held data. Users in the default "On this phone" storage
mode must opt into a cloud mode before creating an API key
([ADR-0014](docs/adr/0014-where-memory-lives.md)).

Initial read-only MCP tools may include:

```text
ask_memory
search_memory
get_day
get_timeline
get_decisions
get_action_items
get_commitments
get_highlights
```

Prefer structured tool results and evidence references instead of returning large transcript dumps.

---

# Android Responsibilities

The phone is the authoritative recording device.

- start, pause, resume, and stop a recording session
- run a user-visible microphone foreground service
- persist recording state
- store audio locally first
- recover from process death
- recover usable audio after interruption
- detect storage pressure
- track capture health
- queue uploads when offline
- upload when connectivity returns
- display transcripts and extracted memories
- display daily timeline
- display highlights
- display processing state

---

# Wear OS Responsibilities

The watch is initially a controller, not the primary recorder.

- start recording
- pause / resume
- stop
- highlight
- Quick Note
- recording status
- session duration
- phone connectivity

The watch displays **acknowledged phone state**. It must never assume a command succeeded.

Standalone watch recording is a later experiment.

---

# Capture Health

Capture health is a product feature.

```text
● Recording

3h 42m

Last audio saved: 4 sec ago
Watch connected
Pending upload: 12 min
Local storage: Good
Battery: 62%
```

Failure example:

```text
⚠ Recording interrupted at 2:14 PM

Tap to resume
```

Never silently pretend audio was captured when it was not.

---

# Recording State Machine

Implemented as a pure reducer in `core/recording`:
`reduce(RecordingState, RecordingEvent) -> Transition`.

| From | Event | To |
| --- | --- | --- |
| IDLE, COMPLETED | `Start(sessionId)` | STARTING |
| STARTING | `Started` | RECORDING |
| STARTING | `StartFailed(problem)` | IDLE (problem kept) |
| RECORDING | `Pause` | PAUSED |
| PAUSED | `Resume` | RESUMING (mic reopening; not capturing yet) |
| RESUMING | `Started` | RECORDING |
| RESUMING | `Interrupted(problem)` | INTERRUPTED (session kept) |
| RECORDING, RECOVERING | `Interrupted(problem)` | INTERRUPTED |
| INTERRUPTED | `RecoveryStarted` | RECOVERING |
| RECOVERING | `Recovered` | RECORDING |
| RESUMING, INTERRUPTED, RECOVERING | `Pause` | PAUSED (a privacy pause beats auto-recovery) |
| STARTING, RECORDING, PAUSED, RESUMING, INTERRUPTED, RECOVERING | `Stop` | STOPPING |
| STOPPING | `Stopped` | COMPLETED |
| COMPLETED | `Reset` | IDLE |

Everything else is `Ignored` (duplicate command) or `Rejected`. `RecordingStateMachineTest`
checks every status × event pair against this table.

Contract:

- **RECORDING is only ever entered on the engine's `Started` signal** (audio is flowing),
  never directly from a user command. That is what makes "Recording" on screen honest.
- **Commands** (`Start`, `Pause`, `Resume`, `Stop`, `Reset`) are user intents from the
  phone UI, notification, or watch. **Signals** (`Started`, `StartFailed`, `Interrupted`,
  `RecoveryStarted`, `Recovered`, `Stopped`) come only from the capture engine.
- Result is `Applied`, `Ignored` (a duplicate command whose intent already holds, e.g. a
  watch retry of `Pause`), or `Rejected`. The reducer never throws, so a bad event cannot
  crash the recording service; `Rejected` must be logged and surfaced.
- `revision` increments on every applied transition. It orders the persisted transition
  journal and lets the watch drop stale snapshots (ADR-0008).
- `problem` (a `CaptureProblem`) is carried in `INTERRUPTED` / `RECOVERING`, and in
  `IDLE` after a failed start, so the UI can say *why* capture is not happening.
- Session IDs are generated by the caller (UUIDv7, ADR-0006); the reducer has no clock,
  randomness, or Android types.
- Signals carry their `sessionId`; a late signal from another session is `Rejected`.
  `Start` for a *different* session while one is active is `Rejected`, not `Ignored`.
- Pause keeps the foreground service alive; see ADR-0010 for why.

Every applied transition is persisted locally (an append-only journal keyed by `revision`)
before side effects that depend on it. The journal is also the source for measuring capture
gaps during endurance tests.

After restart, the client should know whether a previous session:

- completed normally
- was interrupted
- contains recoverable audio
- has pending uploads
- has partially processed segments

---

# Phase 0: Prove Capture First

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
- battery / thermal telemetry

Required endurance tests:

- 1 hour
- 4 hours
- 8 hours
- 12 hours

Measure:

- battery drain
- CPU
- memory
- temperature
- storage growth
- audio gaps
- microphone interruptions
- Android process behavior
- recovery success

Do not commit to all-day continuous capture until real hardware proves it is acceptable.

---

# Audio Architecture

Avoid a single multi-hour file.

Use short crash-resistant segments.

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

Raw audio is temporary by default.

1. Record locally.
2. Upload what needs processing.
3. Transcribe successfully.
4. Persist transcript evidence.
5. Extract structured memory.
6. Verify persistence.
7. Delete temporary cloud audio.
8. Delete local audio after the recovery window.

Long-term audio retention is not an MVP requirement.

---

# Speech Detection

Local VAD may reduce transcription cost.

It is an optimization, not the source of truth.

Requirements:

- measure false negatives
- preserve buffers around speech
- support a high-fidelity mode
- introduce aggressive filtering only after testing

Missing one important sentence is more damaging than transcribing a small amount of extra audio.

Concrete design: [ADR-0015](docs/adr/0015-speech-gating-and-night-processing.md) —
gate on speech, not importance; VAD runs at night on the charger, not on the recording hot path.

---

# Memory Architecture

Great recall requires more than saving transcripts and embedding everything.

Use four levels.

## Level 1 — Evidence

Immutable timestamped transcript segments.

These are the source of truth.

## Level 2 — Event Windows

Group neighboring transcript segments into meaningful conversation/event windows.

Examples:

- standup
- drive conversation
- quick note
- phone discussion
- project conversation

Event windows reduce fragmentation and provide better context for retrieval than isolated 30-second transcript chunks.

## Level 3 — Structured Memory Items

Extract high-value objects:

- decision
- action
- commitment
- idea
- question
- fact
- highlight
- topic change

Each memory item references its supporting event and transcript evidence.

## Level 4 — Navigation Summaries

Examples:

- daily summary
- weekly summary
- project summary

Summaries help users navigate history, but they are **not evidence**.

Important answers should resolve back to memory items and transcripts.

Do not build chains of summaries-of-summaries. Regenerate important summaries from structured memory and evidence when needed.

---

# Memory Recall Architecture

Retrieval should use the cheapest reliable method first.

```text
Question
   |
   v
Query Understanding
   |
   +--> time range
   +--> people
   +--> project/topic
   +--> memory type
   +--> exact keywords
   +--> semantic intent
   |
   v
Retrieval Planner
   |
   +--> Structured SQL
   +--> Full-text search
   +--> Semantic search when needed
   |
   v
Merge + Rank
   |
   v
Fetch supporting evidence
   |
   v
Small context package
   |
   v
Answer generation
   |
   v
Answer + evidence
```

The LLM should not receive an entire day, week, or month of transcript text just to answer a narrow question.

---

# Retrieval Strategy

## 1. Structured Retrieval First

Use normal SQL for questions that already map cleanly to structured data.

Examples:

> What tasks do I have from yesterday?

Filter:

- user
- date
- type = action

No vector search is required.

## 2. Temporal Filtering

Time is one of the strongest memory signals.

Every searchable record should carry:

- occurred_at
- local_date
- timezone
- session_id
- event_id

Resolve expressions such as:

- yesterday
- last week
- this morning
- before lunch
- after the standup

before expensive semantic retrieval when possible.

## 3. Entity Filtering

Support normalized entities such as:

- people
- projects
- companies
- places
- products

Do not introduce a graph database.

Use relational tables for:

- entities
- aliases
- entity_mentions

This enables queries such as:

> What did John say about Kafka last week?

to narrow the search before semantic ranking.

## 4. PostgreSQL Full-Text Search

Use lexical search for exact terms, names, identifiers, acronyms, ticket numbers, and phrases.

Examples:

- Kafka
- INC-123
- payment processor
- Model Y

## 5. Semantic Search

Add pgvector when historical natural-language retrieval requires it.

Do not embed every tiny audio segment by default.

Prefer embeddings for:

- event windows
- structured memory items
- selected larger transcript windows

This reduces cost, index size, and noisy matches.

Raw transcript text remains available for evidence and lexical fallback.

## 6. Hybrid Search

When semantic search is enabled:

1. apply user/date/type/entity filters
2. run lexical search
3. run vector search
4. merge candidate lists
5. rerank a small candidate set
6. fetch supporting transcript evidence

Reciprocal Rank Fusion is a good initial merge strategy.

A more expensive reranker should only operate on the top candidate set.

## 7. Evidence Expansion

Once the correct memory item or event is found, fetch nearby transcript evidence.

This gives the answering model context without retrieving an entire session.

---

# Recall Ranking Signals

Ranking should be explainable and testable.

Potential signals:

1. query match
2. structured filters
3. semantic similarity
4. lexical match
5. manual highlight
6. explicit decision / commitment
7. user correction or confirmation
8. temporal fit
9. source quality

Do **not** globally rank recent memories above old memories.

Recency should matter only when the query implies it.

A six-month-old confirmed decision may be more relevant than something said yesterday.

---

# Memory Lifecycle

Memory items need explicit lifecycle states.

Suggested states:

- possible
- confirmed
- corrected
- superseded
- dismissed

Never delete history merely because a newer memory exists.

Example:

```text
Decision A
superseded_by -> Decision B
```

This allows the system to answer both:

> What is the current decision?

and:

> What did we originally decide?

---

# Human Correction

Every generated item should be correctable.

```text
Call Mike tomorrow

[Correct] [Edit] [Not a task]
```

Prefer human-friendly states:

- Confirmed
- Possible
- Needs review

Corrections are stored separately from original transcript evidence.

---

# Speaker Handling

Decided in [ADR-0016](docs/adr/0016-owner-voice-attribution.md): the account owner may enroll
their own voice (explicit consent, embedding kept on the phone only) so turns are labeled Me vs
others. Never build voiceprints for anyone else.

Start with:

- Me
- Speaker 2
- Speaker 3

Allow optional renaming.

Diarization and real-world identity are separate problems.

---

# Processing Versioning

Every derived artifact records how it was produced.

```text
transcription_provider
transcription_model
transcription_version

extraction_model
extraction_prompt_version

embedding_model
embedding_version

memory_schema_version
created_at
```

This allows controlled reprocessing without modifying original evidence.

---

# Simplified Backend

```text
                    ┌──────── Android
                    ├──────── MCP
                    ├──────── CLI
                    └──────── Web
                             |
                             v
                         FastAPI
                        /       \
                       v         v
                PostgreSQL    Temporary R2
                       |
                       v
                    Worker
                       |
                       v
                  AI Providers
```

One backend owns:

- authorization
- sessions
- uploads
- processing state
- memory extraction
- search
- retrieval
- evidence assembly
- Ask My Memory

A database-backed job table is sufficient for the first beta.

---

# Technology Stack

## Android

- Kotlin
- Jetpack Compose
- Coroutines / Flow
- Room / SQLite
- WorkManager
- Android Keystore
- native microphone APIs
- foreground service
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
- PostgreSQL full-text search
- pgvector only when semantic retrieval is introduced

## Authentication

- Supabase Auth or another managed provider

Authentication should not block local Phase 0 development.

## Temporary Audio

- Cloudflare R2
- private bucket
- short-lived signed access
- lifecycle deletion as a safety net

## AI

Start with:

- one transcription provider
- one extraction / answer model
- one embedding model only when semantic search is introduced

Use thin provider adapters rather than a large provider-plugin framework.

## Hosting

Initial target:

- Railway API
- Railway worker
- Supabase PostgreSQL
- Cloudflare R2

## Observability

Early:

- Sentry
- structured logs

Later:

- OpenTelemetry when distributed tracing becomes useful

Never log raw audio, full transcripts, access tokens, or private prompt contents in ordinary telemetry.

---

# Data Model

Keep the schema relational and evidence-centered. Rules that apply to every table
(ADR-0006, ADR-0009, ADR-0004 amendment):

- every user-owned row carries `user_id`, from the first migration
- capture-layer IDs are client-generated UUIDv7; server-derived IDs are server UUIDv7
- time-bearing rows store `occurred_at` (UTC), `timezone`, `utc_offset_minutes`, `local_date`
- derived rows record how they were produced (see Processing Versioning)
- foreign keys to user-owned parents cascade on delete

## Identity and access

| Table | Purpose / key columns |
| --- | --- |
| `users` | account; `status`, `created_at`, `deleted_at` (deletion job state) |
| `devices` | registered phones and watches; `user_id`, `kind`, `last_seen_at` |
| `api_keys` | Phase 5/10: `user_id`, `key_hash`, `prefix`, `scopes`, `last_used_at`, `revoked_at` |
| `audit_log` | sensitive access (MCP/API tool calls, exports, deletions); no content, only references |

## Capture layer (Level 1 inputs, created on device)

| Table | Key columns |
| --- | --- |
| `recording_sessions` | `id`, `user_id`, `device_id`, `kind` (`day` / `quick_note`), `started_at`, `ended_at`, `status` |
| `audio_segments` | `id`, `session_id`, `sequence`, `occurred_at`, `duration_ms`, `sha256`, `byte_size`, `codec`, `upload_state`, `storage_key`, `retention_expires_at` |
| `capture_gaps` | `session_id`, `started_at`, `ended_at`, `problem` — interruptions reported by the device, so gaps are data, not silence |
| `highlights` | `id`, `session_id`, `occurred_at`, `source` (`phone` / `watch` / `notification`), optional `note` |

Device-only lifecycle (`WRITING`, `SEALED`, …; ADR-0007) stays in Room and is not
mirrored on the server.

## Evidence (Level 1)

| Table | Key columns |
| --- | --- |
| `transcript_segments` | `id`, `audio_segment_id`, `revision`, `is_active`, `started_at`, `ended_at`, `text`, `speaker_label`, `confidence`, transcription provider/model/version |

Rows are never updated except `is_active` when a newer transcription revision replaces
them. Full-text index on `text`.

## Derived memory (Levels 2–4)

| Table | Key columns |
| --- | --- |
| `events` | `id`, `session_id`, `started_at`, `ended_at`, `title`, `summary`, extraction version |
| `event_segments` | `event_id`, `transcript_segment_id` |
| `memory_items` | `id`, `user_id`, `event_id`, `type`, `title`, `body`, `owner_entity_id`, `lifecycle_state`, `review_state`, `occurred_at`, `local_date`, `due_at`, `superseded_by_id`, extraction version |
| `memory_item_evidence` | `memory_item_id`, `transcript_segment_id`, optional char span — many-to-many |
| `memory_item_corrections` | `memory_item_id`, `action` (`confirm` / `edit` / `dismiss` / `supersede`), `patch`, `created_at` |
| `entities`, `entity_aliases`, `entity_mentions` | normalized people/projects/places; mentions link to segments and items |
| `daily_summaries` | navigation only, never evidence; `local_date`, source item IDs, generator version |
| `embeddings` | only when Gate E passes; linked to events and memory items |

Memory item types: `decision`, `action`, `commitment`, `idea`, `fact`, `question`.
Highlights are user input, stored in the capture layer, and boost ranking.

## Operations and commercial

| Table | Purpose |
| --- | --- |
| `processing_jobs` | durable jobs: `stage`, `idempotency_key` (unique), `state`, `attempts`, `lease_expires_at`, `next_attempt_at`, `last_error` |
| `usage_counters` | metering per user/period (recorded, transcribed minutes, tokens, API calls) |
| `entitlements` | Phase 10: server-authoritative plan state |
| `export_jobs` | Phase 8/10: self-service export requests, TTL, delivered object key |

---

# Database Indexing

Add indexes based on real query patterns.

Likely early indexes:

```text
(user_id, occurred_at)
(user_id, type, occurred_at)
(recording_session_id, started_at)
(event_id, started_at)
(entity_id, occurred_at)
```

Use PostgreSQL full-text indexes for searchable text.

When pgvector is introduced, start with exact search for small datasets and measure recall before adding approximate indexes.

Approximate vector indexes trade some retrieval recall for speed, so they should be introduced deliberately.

---

# Query API

All clients use the same backend contracts.

Initial endpoints:

```text
/sessions
/uploads
/transcripts
/highlights
/events
/memory
/summary
/health
/search
/ask
```

Example:

```http
POST /v1/ask
```

Request:

```json
{
  "question": "What did I decide about Kafka yesterday?"
}
```

Response:

```json
{
  "answer": "You decided to use the new consumer implementation.",
  "evidence": [
    {
      "event_id": "evt_123",
      "transcript_id": "tr_456",
      "timestamp": "2026-09-22T10:42:12-05:00"
    }
  ]
}
```

---

# Retrieval Efficiency Rules

## Never send full history by default

Retrieve a bounded candidate set.

## Filter before vector search

User, date, entity, type, and context filters reduce cost and improve precision.

## Embed useful units

Prefer event and memory embeddings over thousands of tiny transcript embeddings.

## Rerank narrowly

Run expensive reranking only over a small candidate set.

## Fetch evidence last

First locate the likely memory, then expand nearby transcripts.

## Cache stable navigation views

Daily timelines and summaries can be cached because they are read frequently and change infrequently after processing completes.

## Keep evidence authoritative

Cached summaries and generated answers can always be recreated.

---

# Reliability Rules

## Local first

Recording never depends on backend availability.

## Stable IDs

Sessions and segments receive stable IDs on-device.

## Idempotency

Retries must not create duplicate transcripts or memories.

## Checksums

Validate audio integrity.

## Explicit acknowledgement

Delete recoverable local audio only after required server acknowledgement.

## No silent failure

Expose capture interruptions.

## Bounded retries

Repeated backend failures become visible.

---

# Privacy and Security

This application can capture highly sensitive conversations.

## Device

- encrypted temporary audio
- Android Keystore protected keys
- explicit microphone permission
- visible recording state
- immediate pause / stop
- deletion controls

## Backend

- TLS
- private object storage
- short-lived signed URLs
- authentication on private endpoints
- authorization on every user resource
- secrets outside source control
- no access tokens in logs
- no raw transcripts in ordinary logs
- audio retention jobs

## AI Providers

Users should know when audio or transcript data is sent to third-party processing providers.

Do not claim end-to-end encryption when a cloud processor needs plaintext data.

Before any third-party voice reaches a provider (Gate B), confirm the provider's terms:
no training on customer data, zero or bounded retention, a DPA, and processing region.

## Untrusted Content (prompt injection)

Transcripts contain whatever anyone near the phone said. Treat transcript text as
**untrusted input** everywhere it meets a model or an agent:

- extraction prompts delimit transcript text as data; model output is schema-validated
  and can only create memory items that cite evidence, never trigger actions
- Ask My Memory answers are generated from a bounded evidence package, with no tools
- MCP/API results return transcript text as clearly quoted, bounded fields so a user's
  own agent can distinguish memory content from instructions

## MCP / API

- user-scoped authorization
- least-privilege access
- read-only MCP tools initially
- bounded result sizes
- no raw bulk transcript export unless explicitly requested and authorized
- third-party access uses per-user API keys: hashed at rest, scoped read-only by default, rate-limited, revocable
- user-initiated full self-export (transcripts, memories, and raw audio while retention holds) is a supported right; see TODO.md Phase 8 / Phase 10

## Recording Consent

- visible recording state
- easy pause
- consent guidance
- no covert-recording UX

---

# Cost Controls

Track:

- recorded minutes
- uploaded minutes
- transcribed minutes
- transcription cost
- extraction tokens
- answer tokens
- embedding usage
- storage
- retry cost

Important:

```text
Recorded time != Transcribed time != LLM context
```

Each stage should reduce unnecessary data.

```text
Audio
  ↓
Measured speech detection
  ↓
Transcript
  ↓
Event grouping
  ↓
Structured extraction
  ↓
Small searchable memory
  ↓
Targeted retrieval
  ↓
Small answer context
```

This is both cheaper and better for recall than repeatedly giving an LLM large transcript histories.

## Unit economics are an architecture input

A subscription product that records all day lives or dies on cost per user-day. Rough
shape (assumptions, not quotes): 10 recorded hours/day at hosted-ASR list prices of
roughly $0.006/min is about $3.60/day, or ~$100/month per user before any LLM cost. The
same day at a 25% speech ratio with a lower-cost batch provider can be an order of
magnitude cheaper. So:

- Phase 0 measures the **speech-active ratio** on real days (VAD in shadow mode: logged,
  not filtering), because it sets the transcription bill.
- Gate B requires a per-user-day cost model built from those measurements.
- On-device transcription stays a candidate if it changes the cost or privacy picture.

---

# Scaling Strategy

## Stage 0 — Personal Prototype

- Android
- local capture
- battery and reliability testing

## Stage 1 — Early Beta

- FastAPI
- PostgreSQL
- temporary R2
- one worker
- one transcription provider
- one LLM provider
- Wear OS

## Stage 2 — Recall Validation

- event grouping
- entities
- full-text search
- structured retrieval
- MCP
- CLI
- semantic search only if needed

## Stage 3 — Product Growth

Introduce when measurements justify them:

- pgvector / HNSW
- stronger async queue
- multiple workers
- caching
- quotas
- billing
- autoscaling

## Stage 4 — Larger Scale

Possible later changes:

- separate transcription workers
- separate AI workers
- managed durable queue
- database partitioning
- read replicas
- regional data placement
- dedicated search service only if PostgreSQL becomes a measured bottleneck

---

# Repository Structure

```text
AI-Daily-Memory/
├── apps/
│   ├── android/
│   └── wear/
│
├── backend/
│   ├── app/
│   │   ├── api/
│   │   ├── capture/
│   │   ├── memory/
│   │   ├── retrieval/
│   │   └── processing/
│   ├── migrations/
│   └── tests/
│
├── interfaces/
│   ├── mcp/
│   └── cli/
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

Use Architecture Decision Records for decisions that are expensive to reverse.

| ADR | Decision |
| --- | --- |
| [0001](docs/adr/0001-android-first-modular-monolith.md) | Android-first client, modular-monolith backend |
| [0002](docs/adr/0002-offline-first-capture.md) | Offline-first capture |
| [0003](docs/adr/0003-temporary-raw-audio.md) | Raw audio is temporary by default |
| [0004](docs/adr/0004-evidence-backed-memory.md) | Evidence-backed memory (+ deletion/revision amendment) |
| [0005](docs/adr/0005-single-memory-query-service.md) | Single Memory Query Service |
| [0006](docs/adr/0006-identity-time-and-idempotency.md) | Client UUIDv7 IDs, capture-time time model, idempotency keys |
| [0007](docs/adr/0007-local-audio-segments-and-encryption.md) | Append-safe encrypted audio segments |
| [0008](docs/adr/0008-watch-control-protocol.md) | Watch protocol; watch shares the phone applicationId |
| [0009](docs/adr/0009-backend-sole-database-client.md) | Backend is the only DB client; user scoping from day one |
| [0010](docs/adr/0010-microphone-service-lifecycle.md) | Mic foreground-service lifecycle and background-start limits |
| [0011](docs/adr/0011-quick-note-and-markers.md) | Quick Note and highlights are markers inside a session |
| [0012](docs/adr/0012-daily-rhythm.md) | Daily rhythm: sleep schedule, quiet times, memory days, daily report |
| [0013](docs/adr/0013-opt-in-local-audio-retention.md) | Transcripts by default; opt-in, local-only audio keeping |
| [0014](docs/adr/0014-where-memory-lives.md) | memory on the phone by default; cloud modes opt-in; API/MCP needs a cloud mode |
| [0015](docs/adr/0015-speech-gating-and-night-processing.md) | transcribe speech only; VAD + clip upload at night on the charger |
| [0016](docs/adr/0016-owner-voice-attribution.md) | on-device owner voice (Me vs others) for action-item attribution |

---

# Testing Strategy

## Capture Tests

- state machine
- segment lifecycle
- interruption recovery
- process death
- storage pressure
- background behavior
- watch communication

## Physical Endurance Tests

Measure:

- battery
- thermal behavior
- CPU
- memory
- storage
- audio gaps
- microphone routing
- recovery

## Fault Injection

Simulate:

- offline device
- slow connection
- partial upload
- backend timeout
- corrupted audio
- duplicate upload
- worker crash
- provider timeout
- low storage

## Memory Extraction Evaluation

Maintain a human-reviewed evaluation set for:

- decisions
- explicit tasks
- possible tasks
- commitments
- ideas
- highlights

## Recall Evaluation

Recall quality must be measured separately from extraction quality.

Create test questions from known captured days and measure:

- did the correct event enter the candidate set?
- did the correct evidence rank near the top?
- did the answer cite the correct source?
- did structured filters resolve correctly?
- did temporal queries resolve correctly?
- did entity aliases resolve correctly?
- did the system abstain when evidence was insufficient?

Track retrieval metrics such as Recall@K and ranking quality on a small controlled benchmark before tuning vector indexes.

---

# Engineering Targets

Validation targets:

- no silent capture failure
- no permanent loss of successfully persisted local segments in controlled tests
- no user-visible duplicate transcript segments
- recover interrupted sessions when usable audio remains
- predictable battery impact
- predictable processing cost
- every derived memory traces back to evidence
- corrections never overwrite evidence
- retrieval answers are based on bounded evidence
- Ask My Memory can return source references
- recall quality is benchmarked, not judged only by demos

---

# Development Roadmap

## Phase 0 — Capture Reliability

Android recording only.

Exit:

> A normal workday can be captured reliably with acceptable battery, thermal, storage, and recovery behavior.

## Phase 1 — Upload + Transcription

Add cloud processing and transcript persistence.

Exit:

> Every acknowledged upload becomes a transcript or enters a visible recoverable failure state.

## Phase 2 — Daily Timeline + Structured Memory

Add:

- event grouping
- highlights
- decisions
- actions
- ideas
- commitments
- corrections
- daily summary

Exit:

> The timeline consistently surfaces things worth remembering.

## Phase 3 — Wear OS

Add reliable remote control, highlights, Quick Note, and health status.

Exit:

> The watch reliably controls the phone in normal daily use.

## Phase 4 — Recall Engine

Add:

- temporal query parsing
- structured SQL retrieval
- entity aliases
- full-text search
- evidence expansion
- recall benchmark

Exit:

> Known questions from prior days retrieve the correct evidence reliably.

## Phase 5 — MCP + CLI

Expose the recall engine without bypassing it.

Exit:

> External agents and terminal users can safely query the same memory system.

## Phase 6 — Semantic Recall

Only if Phase 4 shows lexical / structured retrieval gaps.

Add:

- embeddings
- pgvector
- hybrid retrieval
- Reciprocal Rank Fusion
- small-candidate reranking

Exit:

> Semantic retrieval measurably improves benchmark recall without unacceptable latency or cost.

## Phase 7 — Long-Term Memory

Potential:

- weekly summaries
- project memory
- recurring commitments
- durable entity relationships
- memory consolidation

No graph database unless relational modeling becomes a measured limitation.

## Phase 8 — Production Hardening

- account lifecycle
- export
- deletion
- retention settings
- quotas
- billing if needed
- security review
- backup / recovery testing
- load testing

## Phase 9 — iOS + Apple Watch

Only after the Android product and recall engine are proven.

---

# MVP Non-Goals

Do not build initially:

- iOS
- Apple Watch
- standalone watch recording
- permanent audio archive
- voice biometrics
- automatic calendar mutations
- automatic email sending
- autonomous agents
- graph database
- dedicated vector database
- Redis unless job volume needs it
- Kafka
- Kubernetes
- multiple provider routing
- complex microservices
- embeddings for every transcript fragment
- long-term memory graph
- separate retrieval logic per client

---

# Architecture Decision Gates

## Gate A — Continuous Capture

Proceed only if battery, thermal, reliability, and audio quality are acceptable.

## Gate B — Cloud Processing

Proceed only if transcription is useful, reliable, and economically understood.

## Gate C — Structured Memory

Proceed only if extracted decisions, actions, and commitments are measurably useful and correctable.

## Gate D — Recall

Before semantic search, prove structured + lexical retrieval and measure its gaps.

## Gate E — Semantic Search

Add embeddings only if they measurably improve recall.

## Gate F — Approximate Vector Indexes

Use them only when exact vector search latency requires it and benchmark the recall tradeoff.

## Gate G — Infrastructure Expansion

Add queues, replicas, caching, service splitting, or dedicated search only after metrics show the need.

---

# Definition of Success

The first meaningful product milestone is:

> Start AI Daily Memory in the morning, live a normal day, and later retrieve a useful decision, idea, commitment, action item, or highlighted moment that would otherwise have been forgotten — with evidence showing where it came from.

The longer-term success criterion is stronger:

> Months later, ask a natural question through Android, MCP, CLI, API, or web and reliably retrieve the correct memory without scanning or sending the user's entire history to an LLM.

That is the architecture target.
