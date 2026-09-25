# ADR-0019: Structured Extraction and Daily Report Generation (Phase 2 MVP)

- Status: Accepted (technical integration only — see Consequences, same posture as ADR-0018)
- Date: 2026-09-25

## Context

Phase 2 needs: grouping transcript segments into events, extracting decisions/actions/
commitments/ideas/questions with evidence and confidence, storing corrections separately from
evidence, and building the daily report (`app/reports/daily.py`, already built) from all of it.

Extraction can only ever run where the server holds a plaintext transcript. `app/transcription.py`
already enforces that: phone-only and reports-cloud accounts get their transcript sealed to the
phone's device key and never see server-side plaintext (ADR-0014); only `full_cloud` accounts
persist a plaintext `Transcript`/`TranscriptSegment`. So this ADR's scope is `full_cloud` only.
Extraction for the other two modes, if it ever exists, has to run on the phone.

## Decision

**Event grouping** reuses the client-assigned `session_id` already on every `AudioUpload`
(ADR-0006): no new grouping table. A session's transcript is its clips' segments, upload-ordered
then segment-ordered.

**Extraction** (`app/extraction.py`) is a job stage, same shape as transcription: a
`TranscriptionProvider`-like `ExtractionProvider` Protocol, a `JobError`-raising handler
(`ExtractHandler`), and `validate()` gating everything a provider returns before it's trusted.
Transcript text is untrusted model input (README "Untrusted Content"): it's delimited inside the
prompt, the provider gets no tool access, and every field of the response is schema- and
range-checked — an invalid evidence index, an invented commitment field on a non-commitment item,
an out-of-enum kind or confidence, all dead-letter the job rather than writing partial data.

**Provider**: Claude (Anthropic's Messages API), called directly over `httpx` — the same
reasoning as ADR-0018's Deepgram choice: one stable REST contract, no extra SDK. The prompt asks
for a JSON array with no prose; a response that isn't exactly that is `invalid_extraction_result`
and never partially persisted.

**Schema** (migration 0004): `memory_items` (one row per extracted item; `memory_item_evidence`
many-to-many to `transcript_segments`, matching TODO.md's own naming) and `daily_reports` (the
assembled `DailyReport` contract, stored as JSONB, one row per user per memory day). A session's
`memory_items` are replaced outright on every extraction run — simple, always-consistent, but it
means a rerun also clears any earlier dismissal from that session (see Consequences).

**Report generation** (`app/reports/build.py`) assembles a `DailyReport` from `memory_items` plus
`audio_uploads` coverage data, and is called at the end of every extraction run for that item's
memory day, immediately after the transcribe job's success enqueues the extract job
(`app/transcription.py`, idempotency key `extract:{session_id}` — one extraction per session; a
later clip in the same session adds evidence but doesn't retrigger a rerun). There is no
bedtime/wake-time scheduler here: the backend doesn't know the phone's sleep schedule (ADR-0012
is phone-local), so "generate at bedtime, deliver at wake" isn't implemented — a report exists as
soon as there's something to extract, at `GET /v1/reports/{memory_day}`.

**Human correction**: only "dismiss" is implemented (`memory_items.dismissed_at`). Edit,
supersede, and storing a correction separately from the evidence it corrects (ADR-0004's own
requirement) are not — see Consequences.

`Settings.extraction_provider` defaults to `disabled`, same posture as `transcription_provider`.

## Consequences — what this ADR does not deliver

- **No gold-dataset evaluation.** TODO.md's "AI evaluation" section (precision/recall on
  decisions, tasks, commitments; false-positive tasks/commitments treated as high-severity) does
  not exist. This is the reason `extraction_provider` stays `disabled` by default: unlike a wrong
  transcript word, a fabricated commitment is a product-level lie about what someone promised.
- **Two placeholders in every report**, both because the upload protocol doesn't carry data the
  phone already computes locally: reports render in UTC (no IANA zone is synced from the phone),
  and `memory_day` is the UTC calendar date of a session's first clip, not the real sleep-midpoint
  memory day (ADR-0012). `SessionSummary.title` is a fixed placeholder for the same reason (the
  phone's own session titles aren't synced).
- **Not populated**: headline/summary text, people, topics, coverage gaps, and highlights. Each
  needs either a separate summarization pass or phone-local data this build doesn't have.
- **A rerun clears prior dismissals** for that session — extraction is idempotent-by-replacement,
  not idempotent-by-diff. Editing an item's text, superseding a decision, and storing a
  correction apart from its evidence are not implemented.
- **No Android report screen.** The report is retrievable over the API; nothing in the app
  renders it yet, and there's no "share as Markdown" flow (`render_markdown` already exists and
  is unused by the app).
- **Late evidence never retriggers extraction.** A clip that finishes transcribing after its
  session's extract job already ran sits un-extracted until something else re-enqueues that
  session (nothing does, today).
