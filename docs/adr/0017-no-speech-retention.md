# ADR-0017: No-Speech Segments Are Retention-Eligible Immediately

- Status: Accepted — amends ADR-0013 (opt-in local audio retention)
- Date: 2026-09-25

## Context

ADR-0015's clip planner classifies each sealed segment once its speech analysis is available:
`PLANNED` (has speech clips to upload), `NO_SPEECH` (no speech regions at all), `EMPTY`
(speech regions existed but produced zero uploadable clips — e.g. every region was filtered as
too short), or `UNREADABLE` (the segment couldn't be decrypted to plan).

`SegmentLifecycle.RetentionDelete` only accepts a segment from `ACKNOWLEDGED`, and a segment
only reaches `ACKNOWLEDGED` after every one of its planned clips has a stored transcript
(`ClipDao.segmentsWithAllTranscripts`, `ClipDao.fullyUploaded` — both filter
`clip_plan.outcome = 'PLANNED'`). A `NO_SPEECH` or `EMPTY` segment has no `PLANNED` clip row, so
it never matches either query. It stays `SEALED` forever, exempt from the user's `AudioRetention`
setting, and is only removed by explicit user deletion or the phone running out of storage. This
was flagged as an open decision in `ClipUploader.planClips` when the planner shipped.

## Decision

A segment classified `NO_SPEECH` or `EMPTY` carries no evidence obligation: nothing about it was
ever going to be uploaded or transcribed, so there is nothing for the recovery window to protect
against and no server round trip to wait for. As soon as the planner reaches that verdict, the
segment is acknowledged immediately and locally, using its own already-verified checksum as the
proof of integrity that `SegmentEvent.ServerAcknowledged` requires — the same event a real server
acknowledgement uses, so it becomes subject to `AudioRetention` on the same terms as transcribed
audio (including the recovery window floor, since the reducer doesn't distinguish why a segment
reached `ACKNOWLEDGED`).

`UNREADABLE` segments are excluded: the phone could not classify them, so silently acknowledging
one risks discarding real speech that was simply inaccessible at analysis time (Keystore hiccup,
transient I/O error). They stay `SEALED`, are retried, and already surface as an upload problem
in settings (`ClipDao.observeProblems`).

## Consequences

- A day with little or no speech no longer accumulates unbounded local audio; it is governed by
  the same `AudioRetention` setting as everything else (transcripts-only by default deletes it
  after the recovery window; longer settings keep it that long).
- No new `SegmentEvent` was needed — `ServerAcknowledged` already expresses "this phone is done
  needing the bytes"; a local no-speech verdict is one more way to reach that state honestly.
- The recovery window still applies, so a segment misclassified as `NO_SPEECH` (a VAD false
  negative) is not deleted for at least the recovery window, during which speech analysis or a
  reprocessing pass could in principle catch it. No such reprocessing pass exists yet; a VAD
  false negative today is a genuine, if rare, evidence gap. This is accepted for now because the
  detector is tuned to prefer more audio over less (ADR-0015) and re-running analysis on already
  no-speech segments is not a measured need.
