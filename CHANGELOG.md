# Changelog

All notable changes to AI Daily Memory. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Dates are UTC.

## [Unreleased]

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
