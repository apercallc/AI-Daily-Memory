# Privacy — what we claim, and what we don't

This is the citable basis for every public privacy statement (landing page, store
listings, support answers). If a claim isn't backed here, don't make it. Sources:
SECURITY.md, ADR-0003, ADR-0007, ADR-0013, ADR-0014, ADR-0015, ADR-0017, ADR-0018.

## What we can say today

**Recording is always visible.** A persistent notification and on-screen indicator exist
whenever capture runs. Pause and stop are one tap away from the phone, the watch, or the
notification. Hiding recording state violates the product's own rules (AGENTS.md rule 1).

**Audio is encrypted on the phone.** Segments use Tink streaming AEAD (AES-256-GCM) under
an Android Keystore key; backups and device transfer exclude all app data.

**Nothing is retained server-side by default.** In `phone_only` mode the server does not keep
memory after processing. Transient cloud processing is still required by design when a provider
is enabled: speech clips leave the phone, transcripts are sealed back to the phone's key, and
server-side plaintext is temporary (ADR-0014). Today transcription and extraction are both
disabled by default, so uploads are refused until an operator enables them after review.

**Cloud processing is temporary.** When enabled, clips upload at night, on Wi-Fi, while
charging (ADR-0015). Temporary objects are deleted after the verified transcript is stored,
on dead-letter, and by a retention sweep; the bucket also has a lifecycle backstop.

**Transcripts are sealed to the phone's key.** In phone modes, transcript lines are
sealed with HPKE (X25519/HKDF-SHA256/AES-256-GCM) to the uploading device's key; the
server stores ciphertext it cannot read (ADR-0014).

**Local deletion needs acknowledgement.** Audio becomes deletable only after its
transcript is stored on the phone; the phone re-uploads from kept audio if a delivery
expires or fails — no silent loss.

**Evidence is immutable; AI output is correctable.** Transcripts are never rewritten;
extracted items carry confidence and cite evidence; dismissals exist, edits/supersession
are in progress.

## What we must NOT claim

- **End-to-end encryption.** Any cloud transcription mode requires a provider to process
  plaintext audio. We say "temporary and opt-in", never "E2E encrypted".
- **Physical flash erasure.** Deletion is logical deletion plus key hygiene; we state the
  limits honestly (SECURITY.md).
- **AI accuracy.** No published evaluation exists yet (TODO.md "AI evaluation" is
  unchecked). Extraction ships disabled by default partly for this reason (ADR-0019).
- **"On-device transcription" / "never leaves your phone"** as a blanket statement —
  true only for phone-only mode, which is the default, not the only mode.
- **Legality of recording.** Consent law varies by jurisdiction (all-party-consent
  states, workplace policy). The all-party-consent legal review is still open
  (ADR-0018). We provide visibility and pause controls; users are responsible for
  lawful use.

## Open items before public release (TODO.md Phase 8)

Published retention/deletion matrix, privacy data-flow diagram, data inventory,
self-service export, and full account deletion. This document tracks them; the landing
page (website/) already avoids depending on any of the unchecked items.
