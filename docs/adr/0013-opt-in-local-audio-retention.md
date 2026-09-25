# ADR-0013: Transcripts by Default, Opt-In Local Audio Keeping

- Status: Accepted — amends ADR-0003 (temporary raw audio)
- Date: 2026-09-25

## Context

ADR-0003 made raw audio temporary. Users mostly want transcripts and summaries, but some want
to re-listen to a moment. Keeping audio costs phone storage and raises privacy exposure, so it
must be a deliberate, informed choice, never a default and never in the cloud.

## Decision

- Setting `AudioRetention`: **Transcripts only** (default), keep **7 days**, keep **30 days**,
  or keep **until I delete it**.
- Kept audio is **local only**. Cloud copies are always deleted after verified transcription
  (ADR-0003 unchanged for the cloud).
- The recovery window after server acknowledgement is a floor no setting shortens, and no
  setting ever deletes audio the server hasn't acknowledged (`SegmentLifecycle`).
- The settings screen shows a storage estimate for each option (at 10 recorded hours a day).
- Under storage pressure, capture stops cleanly; kept audio is never purged automatically.
- Users can always delete kept audio themselves (a user right; `UserDelete`).

## Consequences

- Export (Phase 8/10) includes kept audio while it exists (AGENTS.md rule 5).
- Account deletion removes kept audio with everything else.
- Until Phase 1 upload exists nothing is acknowledged, so audio accumulates locally under any
  setting; the capture screen's "only on this phone" figure makes that visible.
