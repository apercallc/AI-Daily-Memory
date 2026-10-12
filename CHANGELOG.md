# Changelog

All notable changes to AI Daily Memory. Format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/). Dates are UTC.

## [Unreleased]

### Added

- Landing page (`website/index.html`): product explainer, who it's for, privacy,
  honest status, FAQ, and download section ahead of APK auto-releases.
- README diagrams: system architecture, recording state machine, on-phone capture
  pipeline, upload/processing sequence, and storage-mode data flow (mermaid).
- `apps/android/README.md`: first-run walkthrough and private-testing install guide.
- `docs/product/PRIVACY.md`: the citable list of privacy claims we can and cannot make.
- `docs/operations/DEPLOYMENT.md`: runbook incl. deliberate provider enablement.
- Backend: retention-sweep index on `audio_uploads (state, created_at)` (migration 0005).
- MCP server: configuration is environment-only (a stray `.env` in the launching
  client's working directory is no longer read); config test coverage added.

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

### Changed

- README Status, Data Model, Query API, MCP tools, tech stack, repository map, and ADR
  index updated to match the shipped code (ADR-0017–0019 included).
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
