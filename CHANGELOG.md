# Changelog

All notable changes to AI Daily Memory. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Dates are UTC.

## [Unreleased]

### Removed

- Removed the dormant Stripe billing implementation (checkout, portal, webhook, plan
  entitlement columns) and the operator-funded Ask model path, per ADR-0028. Ask is now
  retrieval-only unconditionally: it was already the fallback, and is now the only behavior.
  `/v1/capabilities` no longer reports `billing` or `ask_model`. A migration drops the
  now-unused `plan`, `stripe_customer_id`, `plan_renews_at`, and `plan_cancels_at_period_end`
  columns from `users`; transactional email and account recovery are unaffected.

### Fixed

- Server-side extraction (the `full_cloud` storage mode) now computes a session's memory day
  from the uploading client's own timezone when it sends one, instead of always converting to
  UTC first — a late-evening or early-morning session no longer silently lands on the wrong
  calendar day. Uploads may now optionally carry a `source_timezone` (IANA zone id).

## [0.4.0] - 2026-10-13

### Changed

- Transcription now runs on the phone with a free model the app downloads once (Vosk small
  English, 40 MB, pinned by SHA-256). Audio is never uploaded: the clip uploader, delivery
  receiver, device-key registration and the storage-mode picker are gone. Summaries are off until
  the person adds their own AI key; without one a day is a transcript only (ADR-0026).
- Settings: Transcription and Summaries are separate choices, each with an optional own key. Today
  asks for the speech model until it is installed.
- Everything is free. Billing stays in the code, off by default; site, privacy and terms say so.
- Website, privacy page and terms describe the phone-only processing and notes-only sync.
- Phone sessions now sync their own titles and start times; a title renamed on the web takes
  precedence. Turning transcript sync off removes transcript text already stored by the service.
- Ask also returns notes whose synced transcript mentions the question, so transcript-only notes
  can be found through Ask.
- Phone design now uses the shared paper, ink and red palette, serif headings, and monospaced
  labels. Tablet layouts use a navigation rail and centered content; the web app adapts its
  navigation and notes library for phone and tablet widths.

### Added

- Notes library in the web app (ADR-0027): every recording is a note you can rename, star, archive
  and file in folders; search titles, summaries and transcripts with highlighted snippets; sort by
  newest, oldest, title or length; select several and move, star or archive them; open a note to
  read its highlights and a searchable transcript, copy it or download it as text. The app now
  opens on Notes; the earlier list is "Items". Today lists the day's notes.
- API: `/v1/notes`, `/v1/notes/{id}`, `/v1/notes/bulk` and `/v1/folders`; folders and note
  organization are included in the export. Migration `0011`.
- Remove a note from the web app. The service deletes its account copy and remembers the session
  id so phone sync cannot restore it. Migration `0012`.
- Web app at `/app/`: create an account or sign in, review each day's memory, search and filter,
  correct a wording or mark an item not relevant, see evidence, ask questions, manage devices and
  API keys, change password, download everything, delete the account (ADR-0024).
- Ask: `POST /v1/ask` answers only from your own memory and says so when there is nothing;
  evidence-only by default, a model optional (`ASK_PROVIDER`).
- API: memory browse and correct endpoints, token listing and revoke, API-key management, password
  change, JSON export, usage metering, `/v1/ready`. Migration `0009`.
- Password reset and email verification (optional, `MAIL_PROVIDER=resend`), Stripe subscriptions with
  server-side plan limits (optional), privacy and terms drafts, `adm` command-line client, and
  `python -m app.admin dead-jobs` / `requeue-job` (ADR-0025).
- The website's nginx proxies `/v1/` to the API, which also fixes the phone app's default server
  (see `docs/product/LAUNCH_GAPS.md` for the deploy steps).

### Fixed

- Error handling pass across the app and backend: speech analysis no longer stops at one bad
  segment; own-provider rate limits and server errors are retried and a rejected key is named;
  failed sessions and upload limits (daily cap, server trouble, offline) are shown with a retry;
  sign-in tells a server problem from a network one; unreadable transcript lines are marked
  instead of hidden; watch and update failures are logged. Backend: database and object-store
  outages return a retryable 503, provider overload is retried, the extraction call has a hard
  timeout, concurrent first syncs no longer fail, and `/v1/ready` reports the database.

## [0.3.0] - 2026-10-13

### Changed

- One server at a time: the app is either Offline or Connected, and the default is
  aidailymem.apercallc.com (ADR-0022, ADR-0023). Sync has no address of its own; it uses the
  connected account. People running their own backend can set its https address under
  Advanced on the account screen (offline only); a saved link to any valid https server is kept.
- Own AI provider: pick a provider and model from lists (Deepgram for transcription; OpenAI,
  Anthropic, Groq or OpenRouter for the summary). Turning it on pre-fills them, so only the key
  is left, with a tap-to-open link to where each provider issues keys. Custom still takes any
  address and model.
- Memory processing and daily-report sync are on by default (they do nothing offline).
- Settings: Account & server now holds the account, processing and daily reports together;
  bringing your own AI provider moved behind an Advanced switch.
- The recording analyzer keeps human voices only (`voice-v1`): silence, steady noise, held or
  repeated instrument notes and rumble are no longer sent for transcription. Existing segments
  that still have audio are re-analyzed.
- Starting on the phone and watch at the same time now tells the second device why ("Already
  recording"), on both screens.

### Added

- Upgrade note: daily-report sync no longer has its own address or token. It uses the connected
  account, and any sync address saved by an older version is removed.
- First-run walkthrough (four steps, skippable).
- Create account / Sign in inside the app (email + password), a link that opens the site, a
  paste button for tokens, and Test connection with a toast for each outcome.
- Theme: Match my phone (default), Light, Dark.
- The phone and the watch show which device started the session, and the phone says when its
  watch paused, resumed, stopped or started it.

## [0.2.0] - 2026-10-13

### Changed

- Phone: new adaptive launcher icon, fixed indigo dark/light theme (no longer wallpaper colors),
  bottom tab bar (Today, Sessions, Memory, Settings) replacing the three bottom buttons.
- Phone: pulsing red dot while recording; Highlight and Quick Note carry plain-language captions;
  the battery warning is a prominent card with steps and an "Open battery settings" button
  (Samsung-specific steps included).
- Phone: Settings regrouped into schedule, audio, memory processing, account and server, and
  app update. Memory processing and "send memory to your own server" are collapsed with an
  On/Off/Needs setup summary; turning processing on selects your own API key automatically.
- Phone: Sessions, Memory and Transcript get proper empty and loading states with a next step;
  session Rename/Delete moved into a "More options" menu.
- Watch: status dot and headline at the top, marker buttons first, Stop clearly separated,
  consistent severity-colored messages, same icon as the phone.

### Added

- Landing page (`website/index.html`): product explainer, who it's for, privacy,
  honest status, FAQ, and a download section that links to GitHub Releases. Releases are
  built and signed locally; there is no release workflow in CI yet.
- README diagrams: system architecture, recording state machine, on-phone capture
  pipeline, upload/processing sequence, and storage-mode data flow (mermaid).
- `apps/android/README.md`: first-run walkthrough and private-testing install guide.
- `docs/product/PRIVACY.md`: the citable list of privacy claims we can and cannot make.
- `docs/operations/DEPLOYMENT.md`: runbook incl. deliberate provider enablement.
- Backend: retention-sweep index on `audio_uploads (state, created_at)` (migration 0005).

### Fixed

- Backend: concurrent upload-complete race no longer raises on a replaced row
  (re-select + honest 404).
- Backend: argon2 password hashing/verification runs off the event loop.
- Backend: extraction output is range-checked against column and report-contract bounds
  (oversized quote/owner/counterpart/evidence now dead-letters instead of crashing later).
- Backend: daily report build is total — a day that exceeds contract bounds truncates
  deterministically instead of failing; item evidence loads in one query (N+1 removed).
- Android: watch snapshot reset now persists a revision watermark, so a journal that
  went backwards (newest session deleted) is still detected after process restart.
- Android: with no account linked, accumulated local audio no longer surfaces as an
  upload-backlog warning — staying on the phone is the design in phone-only mode.

### Removed

- Removed the optional MCP server, its dependencies, tests, CI job, and setup documentation
  (ADR-0021). External access remains through the REST API.

### Changed

- The Android sync endpoint and settings remain user-configured; no client-specific integration is included (ADR-0021).
- `docs/api/ENDPOINTS.md` now documents signup/login, storage-mode switching, and
  reports endpoints; removed the stale "coming in Phase 5" note for shipped tools.
- `backend/.env.example` lists the Deepgram and extraction provider settings.
- `apps/wear/README.md` reflects the implemented watch rather than the old deferral note.

## Earlier milestones (summary)

- Phase 5 read-only MCP server over the Memory Query API.
- Phase 2 structured extraction + daily report generation, full_cloud only (ADR-0019).
- Deepgram wired as the MVP transcription provider, disabled by default (ADR-0018).
- Storage-mode switching (backend + Android UI), sealed transcript delivery.
- Self-service sign-in: email + password with argon2 and lockout.
- Phase 0/1 capture reliability, upload protocol, job queue, retention sweep.
