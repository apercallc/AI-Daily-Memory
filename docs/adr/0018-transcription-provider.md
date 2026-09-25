# ADR-0018: Deepgram as the MVP Transcription Provider

- Status: Accepted (technical integration only — see Consequences)
- Date: 2026-09-25

## Context

TODO.md's Phase 1 exit gate and AGENTS.md rule 1 require choosing one transcription provider
behind the existing `TranscriptionProvider` seam (`app/transcription.py`) before real audio can
be processed. `app/config.py` has kept `transcription_provider: Literal["disabled"]` since Phase
1 started, and the worker registers no handler while it is `disabled` — uploads are refused
(`503 processing_unavailable`) rather than accepted with nowhere to go (ADR-0003).

Requirements from TODO.md and AGENTS.md: timestamps, provider/model metadata, per-word or
per-span confidence, multi-speaker handling (evaluation still owed — see Consequences), a stable
production SDK or a documented stable REST contract (not alpha/beta), and provider terms that
cover training-data use, retention, a DPA, and region.

## Decision

Wire **Deepgram**'s prerecorded REST endpoint (`nova-2` model) in as the MVP provider
(`app/providers.py: DeepgramProvider`), talked to directly over `httpx` rather than through a
vendor SDK:

- `httpx` is already a repo dependency (used for the upload client's tests); adding a provider
  SDK would mean auditing a second HTTP stack for one integration.
- Deepgram's REST contract is stable and versioned independently of any SDK release cadence.
- One request returns diarized speaker labels, word-level timestamps, and per-word confidence
  in the shapes `TranscriptResult`/`TranscribedSpan` already model — no response reshaping logic
  beyond parsing JSON.

`aac-lc-adts` (the only codec the phone produces, ADR-0007) is passed through directly; Deepgram
decodes AAC natively, so no server-side re-encode is needed. Timeouts and 5xx/429 responses map
to a retryable `JobError` (the existing bounded-backoff path handles retry); 4xx and malformed
responses are non-retryable and dead-letter with an operator-visible reason, matching how every
other provider failure in `transcription.py` is already handled.

`Settings.transcription_provider` gained a second literal value, `"deepgram"`, alongside
`"disabled"`, plus `deepgram_api_key` and `deepgram_model`. **The shipped default stays
`disabled`.** An operator who sets `TRANSCRIPTION_PROVIDER=deepgram` without
`DEEPGRAM_API_KEY` gets a clear `SystemExit` at worker startup, not a silent no-op.

## Consequences

- The technical half of Gate B is done: a real provider is one environment variable away for a
  developer or an internal test, and it is exercised by tests using a fake HTTP transport.
- The two reviews TODO.md separately lists under "Transcription" are **not** done by this ADR
  and are not things a coding change can complete:
  - **Provider terms review** — whether Deepgram's actual contract terms (training-data use,
    retention window, DPA, data region) meet AGENTS.md rule 5 has not been verified against a
    signed agreement. Nothing in this codebase should be read as having confirmed those terms.
  - **Recording-consent legal review** — the all-party-consent jurisdiction question (AGENTS.md,
    "Security and privacy rules") is unrelated to which provider is chosen and remains open.
  - **Per-user-day cost model** — Phase 0's shadow VAD measurements (ADR-0015) exist for this,
    but no cost model has been built from them yet.
  Until an operator completes those and deliberately sets `transcription_provider=deepgram` in a
  reviewed environment, production stays `disabled` exactly as before this ADR.
- "Evaluate noisy/distant speech" and "evaluate multiple speakers" (TODO.md) still require a
  labeled real-audio set this ADR does not create.
- If Deepgram's terms don't clear review, the seam this ADR wires through (`TranscriptionProvider`
  Protocol) means swapping providers is a new `app/providers.py` class and a new `Literal` value,
  not a redesign.
