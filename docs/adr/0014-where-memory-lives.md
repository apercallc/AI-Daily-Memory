# ADR-0014: Where Memory Lives — Phone by Default, Cloud Memory Opt-In

- Status: Accepted — amends ADR-0005 (single query service) and ADR-0003 (temporary audio)
- Date: 2026-09-25

## Context

The user wants reports stored on the phone by default, optionally saved to Google Drive, and
also reachable from their own AI through an API key / MCP. These pull in different directions:

- **An API or MCP server can only answer from data it can read.** If transcripts and reports
  live only on the phone (and optionally in the user's Drive), our server has nothing to serve.
- The phone cannot be the server: it is behind carrier NAT, asleep in Doze most of the night,
  and offline often. Relaying queries to the phone (push → phone answers) is slow, unreliable,
  and drains battery.
- Using the user's Drive as our backing store would mean *our server* holding Drive refresh
  tokens and reading their files — more exposure than storing the data ourselves, plus
  Google restricted-scope verification.
- "Phone only" still needs cloud **processing**: good long-form transcription, diarization, and
  extraction do not run well on most phones today. So audio and text do leave the phone,
  transiently, in every mode. The honest promise is "*stored* only on your phone", not
  "never leaves your phone".

## Decision

### Three storage modes (per account, changeable at any time)

| Mode | Kept on our server after processing | API / MCP | Default |
| --- | --- | --- | --- |
| **On this phone** | Nothing but account + billing | Not available | ✅ |
| **Reports in my cloud account** | Daily reports + structured items (actions, decisions, ideas) with short evidence quotes (≤ 300 chars each). Full transcripts stay on the phone. | Read-only: `get_daily_report`, `get_action_items`, `get_decisions`, `search_memory` over reports/items | |
| **Full cloud memory** | Transcripts, events, structured memory (ADR-0004/0005) | Full, incl. `ask_memory` with evidence expansion | |

- **Google Drive export is orthogonal** to the mode and runs **from the phone**: share sheet
  first (no OAuth), then optional nightly auto-save using on-device Google authorization with
  the `drive.file` scope (only files the app created). Our server never holds Drive tokens.
  Drive is a copy, never a source of truth; edits there are not read back.
- **API keys require a cloud mode.** Creating a key while in "On this phone" explains why and
  offers "Reports in my cloud account". This is the consent moment; it is never silent.
- Users in "On this phone" who want their AI to read reports can point the AI's own Google
  Drive connector at the exported folder. We document this; we don't build it.

### Transient processing in "On this phone" mode

```text
phone (night, charging) ──speech clips + speaker spans──▶ processing job
                                                     transcribe → diarize → extract → report
phone ◀──── day bundle, encrypted to the phone's key ───── held until ack (max 7 days)
phone: verify + persist bundle ──ack──▶ server deletes bundle, clips, transcripts
phone: delete local audio (per ADR-0013 retention)
```

- The day's transcripts are held server-side only until the memory day's report is built and
  the phone acknowledges the bundle; hard maximum **7 days**, then crypto-shredded.
- The bundle is encrypted to a per-device public key (Keystore-backed, via Tink), so once
  processing finishes the held copy is unreadable to us.
- **Invariant: local audio is deleted only after its transcript is persisted wherever the
  user's data lives** (the phone in this mode). If the bundle expires before download, the
  audio is still unacknowledged on the phone and is re-uploaded — no silent loss.
- Cross-day context (open action items from earlier days) is sent by the phone as a small
  context packet with the night's job, not kept by the server.
- Providers must be zero-retention / no-training (TODO Phase 1 provider review); logs and
  crash reports never contain transcript text (existing logging rules).

### Mode switches

- Phone → cloud: ask "from now on" or "also upload past reports/transcripts"; uploads are
  idempotent by client IDs (ADR-0006).
- Cloud → phone: the phone downloads everything it lacks, verifies checksums, then the server
  deletes (cascade + object prefix delete, ADR-0009) and reports completion. Never delete first.

### Amendments

- ADR-0005: the Memory Query Service is the only query path **for server-held data**. In "On
  this phone" mode the app reads its local store directly; recall (Phase 4) there means
  on-device full-text search plus a stateless cloud answer call carrying only the selected
  snippets. API/MCP never reach device-only data.
- ADR-0003/0013: unchanged for audio; this ADR adds the transient-transcript hold above.

## Consequences

- Private by default and cheaper to run (no long-term storage per user in the default mode).
- **Phone loss or reset loses "On this phone" data** except what was exported to Drive. The
  app shows "last saved to Drive" and says this plainly at onboarding.
- Two code paths for reading memory (local store vs query service). Kept small by sharing the
  report contract (`contracts/daily-report.v1.schema.json`) as the unit for both.
- v1 supports **one capture phone per account** in "On this phone" mode; multi-device merge is
  a cloud-mode feature.
- Subscription lapse never locks users out of data already on their phone or in the cloud
  (AGENTS rule 5); only new processing stops.

## Alternatives considered

- **Cloud-only (current README):** simplest; everything works through one service. Rejected as
  the *default* per product direction, kept as the full cloud mode.
- **Phone as MCP server via relay:** rejected (availability, latency, battery).
- **Drive as backing store read by our server:** rejected (token custody, scope verification,
  worse privacy than storing ourselves).
- **Fully on-device processing:** not viable for quality and battery on typical phones today;
  revisit when on-device models (e.g. AICore) cover the device matrix.

## Owner decisions (2026-09-25)

1. Onboarding preselects **"On this phone"**; the user can change it at any time.
2. All three modes ship; "Reports in my cloud account" is the recommended mode for API/MCP.
3. Transient hold maximum: **7 days**.
4. Still open: retention for a lapsed cloud-mode account before deletion (needs a published
   policy before Phase 10).
