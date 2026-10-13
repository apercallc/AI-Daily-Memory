# ADR-0020: On-Device Processing, Phone-Only Storage, Optional Sync

- Status: Accepted — supersedes ADR-0014 (cloud processing assumption), ADR-0018 (server-side
  transcription), ADR-0019 (server-side extraction). ADR-0003 (temporary audio), ADR-0004
  (evidence-backed memory), ADR-0007 (local audio segments), ADR-0015 (speech gating) remain in
  force; this ADR changes where processing runs and where memory is stored, not how audio is
  captured or how evidence works.
- Date: 2026-10-12
- Note: the code drifted from this ADR in 0.3.0 (hosted processing). ADR-0026 puts it back.

## Context

The product is open source, completely free, and privacy-first. The previous architecture
(ADR-0014) assumed cloud processing: even in "On this phone" mode, audio clips were uploaded to
the server, transcribed there, and the encrypted transcript was sent back. That requires a hosted
backend, provider API keys we pay for, and a server that touches user audio — all of which
contradict the product direction.

The owner wants:

1. **No cost to anyone.** No hosted backend we pay to run, no provider keys we pay per call. The
   user may optionally supply their own API keys for higher quality, but the default is free.
2. **User data stays on the device.** Audio is captured, transcribed, and then deleted. The
   transcript and derived memory live on the phone. Nothing leaves unless the user explicitly
   enables sync.
3. **API integration.** Users may optionally sync structured memory to an endpoint they control. They can configure a self-hosted API target or leave sync disabled.

## Decision

### 1. Processing moves to the phone

Transcription and extraction run on the device, not on a server. The phone calls providers
directly with user-supplied keys (if any) or uses on-device models (free, offline). The backend's
`TranscriptionProvider` and `ExtractionProvider` protocols are ported to Kotlin interfaces in
`core/recording` (pure Kotlin, no Android dependencies). The Android app provides concrete
implementations.

- **Default: on-device models.** Free, offline, no API key needed. Quality and battery must be
  benchmarked on the target device matrix before this ships as the only option.
- **Optional: user-supplied API keys.** The user enters their own Deepgram / OpenAI / Anthropic
  key in Settings. The phone calls the provider directly. The user bears the cost; we never see
  the key or the data.
- **No provider keys we hold.** The server never calls an AI provider. The existing backend
  provider seams (ADR-0018, ADR-0019) are superseded; the code remains as a reference
  self-hosted option for users who want it.

### 2. Audio is deleted after transcription

The data lifecycle is:

```
Record audio → VAD: find speech, cut clips → Transcribe clips → Delete audio →
Extract memory items from transcript → Build daily report → Store summary + transcript on phone
```

- Audio is deleted as soon as its transcript is persisted locally. This is the same invariant
  ADR-0014 established ("local audio is deleted only after its transcript is persisted"), but
  now the transcript is produced on-device, so deletion is immediate — no server round-trip.
- The transcript is kept. It is the evidence behind every summary, decision, and action item
  (ADR-0004). The user can expand any summary to read the exact words that were said, with
  evidence links pointing to the precise transcript moment.
- The summary and structured memory items (decisions, actions, commitments, ideas) are the
  primary stored artifact and the primary thing the user sees.

### 3. Phone is the sole memory store (no hosted backend)

- No accounts, no cloud storage, no server-side database by default.
- All memory data (transcripts, memory items, daily reports) lives in the phone's local
  Room/SQLite database, already in credential-encrypted storage.
- The existing transcript encryption (TranscriptCipher, Keystore-backed AEAD) remains. The
  difference is that the phone produces the transcript itself rather than receiving it from a
  server.
- The Railway backend is not required for the product to function. It remains as an optional
  self-hosted sync target (see below).

### 4. Optional sync to a user-configured endpoint

A sync mechanism pushes structured memory out of the phone, nightly, on Wi-Fi + charging:

- **What gets synced:** daily reports + structured memory items (decisions, actions,
  commitments, ideas) with short evidence quotes. Never audio. Raw transcript is not synced
  unless the user explicitly enables "full transcript sync" (off by default).
- **Transcript query access:** synced transcript lines require the separate `transcript:read` scope;
  default API keys carry only `memory:read`.
- **Where it goes:** a user-configured URL + auth token, set in Settings. It can be a
  self-hosted instance of the existing FastAPI backend or another API endpoint that accepts
  the documented sync payload.
- **Direction:** phone pushes out. The sync target does not push back or control the phone.
- **Failure handling:** sync failures are visible to the user (like upload failures today) and
  retried with bounded backoff. The phone's local memory is always the source of truth.

### 5. The existing backend's new role

The FastAPI backend (ADR-0018, ADR-0019, the upload protocol, the job queue, the query API)
becomes an optional self-hosted component:

- A user who wants cross-device API access can run their own instance and point the phone's
  sync at it. The backend stores synced memory and serves queries.
- The FastAPI backend is optional and is not needed for phone-only use.
- The existing provider seams in the backend are not deleted — they remain as a reference for
  self-hosters who prefer server-side processing — but they are no longer the product's path.

## Consequences

- **Simpler for the user:** no account, no server, no cost. Install the app, start recording.
- **Harder for the phone:** on-device transcription and extraction must be benchmarked for
  quality, latency, memory, and battery on the target device matrix. If on-device models are
  not good enough, the user-supplied-key path is the quality escape hatch.
- **No cross-device queries without sync.** A user who does not configure sync can only query
  memory on the phone itself. This is the trade-off for no backend.
- **Phone loss loses memory** unless sync was configured. The app should say this plainly at
  onboarding, as ADR-0014 already required.
- **The upload protocol and server-side processing code paths go dormant.** They are not
  deleted (self-hosters may use them), but the default product path does not use them. The
  Android app's upload job, clip uploader, and delivery receiver become inactive unless the
  user configures a sync target that requires the upload protocol.
- **Evidence links still work.** Memory items cite transcript spans by index and timestamp. The
  transcript is on the phone; expanding a summary shows the cited span. If the user syncs
  memory items through the API, evidence quotes (≤300 chars) travel with them, but the full
  transcript stays on the phone unless the user enables full transcript sync.

## Alternatives considered

- **Keep cloud processing (ADR-0014 as-is):** rejected — requires a hosted backend, provider
  keys we pay for, and user audio touching our server. Contradicts "free and private."
- **Phone as a separate relay protocol:** rejected (ADR-0014 already rejected this — availability,
  latency, battery).
- **On-device only, no sync at all:** rejected — users may want cross-device API access. Sync is optional.

## Open questions

- Which on-device transcription model to default to (Whisper.cpp, ONNX Runtime, Android
  SpeechRecognizer) — a benchmark decision, not an architecture decision.
- Whether to offer a free-tier cloud API (e.g. Gemini free tier) as a built-in option — depends
  on terms and rate limits, which need review before shipping.
- Sync payload format: the existing daily-report JSON contract
  (`contracts/daily-report.v1.schema.json`) is the natural unit; confirm it carries enough
  evidence context for API clients without requiring the full transcript.