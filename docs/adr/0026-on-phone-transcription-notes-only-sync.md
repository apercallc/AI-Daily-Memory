# ADR-0026: Transcription on the Phone, Notes-Only Sync, Free

- Status: Accepted
- Date: 2026-10-13
- Amends: ADR-0022 and ADR-0023 (the connected phone no longer uploads speech clips for server-side
  processing), ADR-0014 (the account's storage mode no longer does anything for the phone),
  ADR-0025 (billing stays built but switched off). Restores ADR-0020 as the rule: the phone does
  the processing.

## Context

ADR-0020 said transcription and extraction run on the phone, and that no server pays for provider
calls. The code had drifted from it. A phone with an account connected uploaded speech clips to the
hosted server, which transcribed them with provider keys we would pay for and sent sealed
transcripts back. The phone itself had no free transcription: its only options were "disabled" or a
person's own Deepgram key. The hosted server also carried a Stripe free/Pro split meant to cover
those costs.

## Decision

1. **Audio never leaves the phone.** The clip uploader, delivery receiver, device-key registration
   and the storage-mode setting are removed from the app. The only network traffic is sign-in and
   token checks, note sync, the one-time model download, and calls the person configures with their
   own keys.
2. **Free transcription on the phone.** The default is the Vosk small English model (Apache-2.0),
   downloaded once on request. It is pinned by size and SHA-256, fetched over https without
   redirects, unpacked with a path and size guard, and installed only after both checks pass. Until
   it is installed, recordings wait on the phone; nothing is lost.
3. **Own transcription key is optional.** A person can send audio to their own Deepgram-style
   service instead. That is the one case where audio leaves the phone, and it goes straight to the
   provider they chose.
4. **Summaries are optional and need the person's key.** There is no free summary engine. With none
   configured the session keeps its transcript and the day's report is marked transcript only
   (`none:transcript-only`). No rule-based pseudo-summary is generated.
5. **The server stores notes and serves the API.** Sync sends daily reports and items, and
   transcript lines only when the person turns that on. The server never calls a transcription or
   extraction provider; those settings stay `disabled`. New uploads return 503 and old installs'
   queued uploads fail visibly.
6. **Everything is free.** Billing code stays in the repository but is off unless Stripe is
   configured, and the web app hides the plan card when it is off. The privacy page and terms say so.
   Ask keeps its per-day limit as abuse protection, not as a plan.

## Consequences

- The privacy claim gets simpler and is checkable: there is no audio upload code to review.
- Accuracy is that of a small model: English only, noticeably weaker than a cloud service on
  accents, noise and far-field speech. It is covered by unit tests with a fake recognizer; it has not yet been run
  against the real model on a phone or measured on real recordings (ADR-0015 Gate B). A larger model or another language would be a new
  download, not a rewrite.
- Roughly 40 MB download, ~70 MB on disk, and extra APK size for the native recognizer libraries.
  Transcription runs while charging, so a long day finishes overnight.
- Transcript-only people get nothing from Ask, which searches extracted items. Searching transcripts
  on the server is a later change.
- Server-side upload, transcription and delivery code is now dead for new apps. It stays for
  self-hosters and old installs until a later cleanup; the unused local tables (`clip_plan`,
  `clip_upload`, `transcript_delivery`) stay in the schema and are read only to protect sessions
  an older version had already uploaded.
