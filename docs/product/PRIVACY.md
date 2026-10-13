# Privacy — what we claim, and what we don't

This is the citable basis for every public privacy statement (landing page, store
listings, support answers). If a claim isn't backed here, don't make it. Sources:
SECURITY.md, ADR-0003, ADR-0004, ADR-0017, ADR-0018, ADR-0024, ADR-0026, ADR-0027.

## What we can say today

**Recording is always visible.** A persistent notification and on-screen indicator exist
whenever capture runs. Pause and stop are one tap away from the phone, the watch, or the
notification. Hiding recording state violates the product's own rules (AGENTS.md rule 1).

**Audio is processed on the phone.** The phone app transcribes locally and does not upload
recordings to this service. Audio segments are encrypted with Tink streaming AEAD under an
Android Keystore key; backups and device transfer exclude app data. If the user enables
Summaries, the AI provider configured by the user may receive transcript text. If the service
operator enables provider-backed Ask, the question and relevant retrieved note content may be
sent to the service's configured provider.

**Notes sync is controlled by the user.** When the phone is linked and sync is enabled, the
service stores notes, session titles and start times, and structured highlights. Transcript
text is not synced by default. The user can turn transcript sync on; turning it off removes
transcripts already stored by the service while keeping the note and its highlights. The web
app only searches transcript text when the account has transcript access.

**Removing a note removes the account copy.** Removing a note from the web app deletes its
server-side note, highlights, and transcript and records a deletion marker so the phone will
not sync that note back. The phone's local copy is not removed by this action. Account deletion
removes the account's server-side data; self-service export is also available.

**Evidence is immutable; derived output is correctable.** Transcript evidence is not rewritten.
Users can correct or dismiss derived highlights. Deletion and retention remove evidence and
the data derived from it.

**Error diagnostics are limited.** The backend, Android/Wear apps, and website report
exception types, stack locations, release, and environment to separate Sentry projects in
Sentry's US region. Before sending, SDK hooks remove error messages, request URLs and
bodies, account identifiers, user/device contexts, breadcrumbs, local variables, and
source-code context. The mobile apps also disable screenshots, view hierarchies, tracing,
profiling, replay, and automatic session tracking. The website fetches its error-only SDK
from Sentry's CDN; the SDK request exposes the visitor's network address to that CDN. Sentry
may also attach the sender's IP address to a received error event even when the SDK removes
user context. The Sentry project-level setting to prevent IP-address storage has not been
verified, so web error reporting is not privacy-verified for production. An error event is
sent only when a JavaScript error occurs. The authentication throttle keeps request client
addresses in bounded process memory only; it does not log them or store them in the database.
In production, it uses Railway's edge-overwritten `X-Real-IP` only when explicitly enabled.
Private-network requests are not covered by that trust rule. These reports do not include
audio, transcripts, or memory content.

## What we must NOT claim

- **End-to-end encryption.** Provider-backed summaries may send text to the provider selected
  by the user. Provider-backed Ask may send the question and relevant retrieved notes to the
  service's configured provider. Do not describe those requests as E2E encrypted.
- **Physical flash erasure.** Deletion is logical deletion plus key hygiene; we state the
  limits honestly (SECURITY.md).
- **AI accuracy.** No published evaluation exists yet (TODO.md "AI evaluation" is unchecked).
- **"Never leaves your phone"** as a blanket statement. Audio remains on-device in the
  current phone workflow, while optional provider-backed features can transmit text.
- **Legality of recording.** Consent law varies by jurisdiction (all-party-consent
  states, workplace policy). The all-party-consent legal review is still open
  (ADR-0018). We provide visibility and pause controls; users are responsible for
  lawful use.

## Open items before public release (TODO.md Phase 8)

The published retention/deletion matrix, privacy data-flow diagram, and data inventory
remain release gates. Self-service export and account deletion are available in the web app.
The landing page (website/) must continue to match these claims.
