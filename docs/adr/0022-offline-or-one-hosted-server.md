# ADR-0022: Offline, or One Hosted Server

- Status: Accepted; decisions 1 and 2 amended by ADR-0023 (own server under Advanced)
- Date: 2026-10-13
- Amends: ADR-0020 (optional sync to a user-configured endpoint) and the account-linking part of
  ADR-0014. Processing on the phone with the user's own provider keys stays, as an advanced
  option. Evidence, retention, encryption and consent rules are unchanged.

## Context

The app had three separate places to type a server: the account (address + token), a sync
endpoint (address + token), and per-service processing addresses. A new user could not tell which
one mattered, and nothing in the app could create the token the account screen asked for. Every
address was also a place a recording-derived payload could be sent to, which is a privacy surface
that does not need to exist.

## Decision

1. A phone is either **Offline** or **Connected**. Offline sends nothing anywhere. Connected talks
   to exactly one server, `https://aidailymem.apercallc.com` (`HostedServer`, core/recording).
2. There is no server-address field. `LinkInput.parse` takes only a token and always builds the
   link to the hosted address. A link stored by an earlier version that points anywhere else is
   dropped on load and its token is never sent (`LinkProblem.OTHER_SERVER`).
3. Connecting uses the existing `/v1/signup` and `/v1/login` endpoints (email + password, 12+
   characters) or a pasted token. The token is verified with `/v1/me` before it is stored.
4. Daily-report sync has no address or token of its own: it posts to the hosted `/v1/sync` with
   the connected account's token, and does nothing while offline. Reports written before the
   account was connected stay on the phone unless the person chose to include the backlog.
5. Processing and sync are on by default; both do nothing without somewhere to go. Turning them
   off is one tap. Two defaults stay off on purpose: "also process speech recorded before I
   connected" and the own-provider path, because each sends data the person has not chosen to
   send.
6. "Test connection" calls `/v1/me` and reports one of five outcomes as a toast. Settings
   changes confirm with a "Saved" toast.
7. The theme is Match my phone (default), Light, or Dark.

## Recording from two devices

The microphone is always the phone's (ADR-0008 stands; the watch is a remote). One session is
open at a time, so Start from both devices at once resolves in the state machine: one is applied,
the other is refused and explained (`RejectionExplainer`). Every session carries the device it was
started from (`RecordingOrigin`), shown on both screens. Pause, resume and stop work from either
device, and the phone says when its watch did something.

## Voice-only analysis

`VoiceVad` (detector `voice-v1`) replaces the energy gate as the analyzer. A frame counts only if
it is above the noise floor, repeats at a human pitch, is not a held tone, and rises and falls at
syllable pace. Silence, steady noise, held or repeated instrument notes, and rumble are dropped
before transcription. Analyzer version changes re-run on segments whose audio still exists.
These are rules of thumb tested on synthetic audio only; they need labeled real recordings before
their accuracy is claimed (ADR-0015 Gate B).

## Consequences

- A user running their own server can no longer point the app at it. The backend code remains
  open source; a fork can change `HostedServer`.
- The number of places the app can send data to drops from four to one, which shrinks the privacy
  review.
- Password sign-in puts a password in the app briefly; it is held in memory only, never saved or
  put in instance state, and sent only to the hosted address over HTTPS.
