# Privacy data-flow diagram

Where captured audio and the data derived from it actually travels. This is the release gate
`docs/product/PRIVACY.md` and `TODO.md` Phase 8 call the "privacy data-flow diagram." Every
arrow below is backed by a file reference; an arrow that isn't currently live in the shipped
app is marked **(disabled by default)** rather than omitted, so this stays honest about what
*could* flow, not just what a default install does today.

## 1. The default path: everything stays on the phone (ADR-0026)

```
 microphone
     |
     v
 AudioCaptureEngine --> encrypted segment (Tink AEAD, Keystore key)      [apps/android/.../capture]
     |
     v
 VoiceVad / SpeechAnalyzer  -- classifies speech regions, charging-time   [apps/android/.../analysis]
     |
     v
 ProcessingPipeline.transcribe()                                         [apps/android/.../processing]
     |-- free on-device model (Vosk, OnDeviceTranscriptionProvider)   <-- default
     |-- OR the user's own transcription API key (ApiKeyTranscriptionProvider) --> USER'S CHOSEN PROVIDER
     |                                                                        (direct phone-to-provider;
     |                                                                         never through our server)
     v
 local transcript lines (TranscriptCipher-encrypted, capture.db)
     |
     v
 ProcessingPipeline.extract() -- OPTIONAL, off unless configured
     |-- none configured --> transcript stands alone, no summary       <-- default
     |-- OR the user's own extraction API key (ApiKeyExtractionProvider) --> USER'S CHOSEN PROVIDER
     v
 memory items + daily report (capture.db)
     |
     v
 Timeline / Report screens, Wear watch face (acknowledged state only)
```

Audio never leaves this box in the default install. Nothing server-side exists until the user
connects an account.

## 2. Optional: notes sync (ADR-0014, ADR-0020/0026)

```
 phone's memory items / reports / note metadata
     |
     v
 POST /v1/sync  (notes + metadata; NEVER audio; transcript text only if "sync full
     |            transcripts" is on)                                    [backend/app/api/sync.py]
     v
 Postgres: memory_items, daily_reports, notes, note_folders              [backend/app/db/models.py]
     |
     +--> transcript_segments/transcripts, ONLY if transcript sync is on and the account
     |    is NOT phone_only/reports_cloud (see 2a below for the phone_only case)
     v
 Web app (GET /v1/memory/*, /v1/ask) and the CLI (interfaces/cli/adm.py) via a scoped,
 rate-limited bearer token/API key
```

### 2a. If `storage_mode` keeps memory on the phone (ADR-0014)

Even with sync on, a transcript for a `phone_only` or `reports_cloud` account is sealed with
HPKE to the phone's own public key (`DeviceKey`) before it's stored:

```
 transcript  --HPKE seal to phone's own key-->  SealedDelivery.ciphertext (server cannot decrypt)
     |
     v
 phone fetches + acknowledges + deletes the sealed delivery row
```

The server holds ciphertext it cannot read, not a plaintext copy.

## 3. Optional: a legacy client uploads raw audio for server-side processing (disabled by default)

This path exists only for REST clients that were never the shipped phone app, or an older phone
app version; the shipped app does not use it (ADR-0026 superseded it). It is kept working for
existing data, not offered to new clients as a default.

```
 client  --POST /v1/uploads, PUT to presigned URL-->  object storage: users/<id>/audio/*.aac
     |                                                       (temporary; ADR-0003)
     v
 POST /v1/uploads/{id}/complete  --enqueues transcribe job-->  app/jobs.py queue
     v
 app/worker.py claims the job
     |
     v
 app.transcription — TranscriptionProvider  **(disabled by default — Settings.transcription_provider
     |                                          defaults to "disabled"; app/providers.py)**
     |    if an operator deliberately enables one (e.g. DeepgramProvider): audio bytes go to
     |    that third-party API over HTTPS
     v
 Transcript / TranscriptSegment rows (plaintext, server-side) -- immutable evidence (ADR-0004)
     |
     v
 app.extraction — ExtractionProvider  **(disabled by default — same module/settings)**
     |    if enabled: transcript text goes to that third-party API
     v
 MemoryItem rows
     |
     v
 audio object deleted immediately after the transcript commits (app/retention.py)
```

Turning either provider on additionally needs reviews the code alone can't satisfy: a
per-user-day cost model, provider terms (no training on customer data, bounded retention, a
DPA, region) and the all-party-consent legal review for transcription; a human-graded gold
dataset for extraction (TODO.md "AI evaluation" — still open). See `app/providers.py`'s module
docstring.

## 4. Optional: `POST /v1/ask` (ADR-0024)

```
 question + today's date  -->  POST /v1/ask
     |
     v
 app/recall.py: PostgreSQL full-text search + structured date/kind query over the
 CALLER'S OWN memory_items only (never cross-user)
     |
     v
 evidence_only_answer()  -- lists the matching items; abstains if nothing matches  <-- always, today
     |
     v
 IF an operator deliberately enables a model-backed answer phase (off by default; no such
 provider path ships today after ADR-0028 removed the dormant `ClaudeAnswerProvider`):
     the matched items' text would be sent to that operator-configured model to phrase an
     answer. This does not exist in the current codebase; recorded here as the one additional
     flow the architecture reserves room for, not as something currently live.
```

## 5. Error reporting (Sentry)

```
 unhandled exception (phone, Wear, website, backend)
     |
     v
 Sentry SDK hook strips: error messages, request URLs/bodies, account ids, user/device
 context, breadcrumbs, local variables, source context                   [app/sentry.py, SDK init]
     |
     v
 Sentry (US region) -- exception type, stack location, release, environment only
```

Never included: audio, transcript text, memory item text, or email addresses.

## 6. Account export and deletion

```
 GET /v1/export  -->  streamed JSON: account, memory items (+ corrections), notes/folders,
                       daily reports, transcripts the server holds, sign-in metadata, clip list
                       (NEVER secrets, NEVER audio bytes — see DATA_INVENTORY.md)

 DELETE /v1/me   -->  object-storage prefix deleted, then the users row deleted
                       (cascades every user_id-scoped table) -- see RETENTION_AND_DELETION.md
```

## What this diagram is not

It does not cover the Wear OS control protocol (watch commands to the phone never carry audio
or memory content — ADR-0008) or the website's own client-side error reporting detail (covered
in `docs/product/PRIVACY.md`'s "Error reports" section). Those have no additional privacy
surface beyond what's stated there.
