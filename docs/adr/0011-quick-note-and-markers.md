# ADR-0011: Quick Note and Highlight Are Markers Inside a Session

- Status: Accepted
- Date: 2026-09-25

## Context

TODO.md Phase 0 left the Quick Note model open: a marked window inside an active session,
or a short standalone session. Highlight had the same shape of question. Both are one tap on
the phone, the notification, or (Phase 3) the watch, and both must stay evidence-backed
(ADR-0004): a marker that points at audio which was never captured would be a false memory.

## Decision

- **Both are markers, not sessions.** A marker is a row (`capture_marker`) with a client
  UUIDv7, the session id, its kind, a `CaptureTime`, and a window relative to the tap on the
  monotonic clock. It holds no audio; the evidence stays in the session's segments.
- **Windows:** Highlight looks back (30 s before, 5 s after): you highlight what was just
  said. Quick Note looks ahead (60 s after): you tap, then speak. Values are provisional and
  are metadata, so changing them later needs no migration of evidence.
- **Only while really capturing.** Markers are refused unless the session is RECORDING
  *and* capture health is not critical (the presenter's `canMark`). No marker during a
  pause, an interruption, or a stall.
- **Debounce:** a second marker of the same kind within 2 s is acknowledged as a duplicate
  (double taps, watch retries), compared only within one boot.
- Quick Note while idle does **not** start a session. Starting capture is always an explicit
  "Start" (recording must never begin as a side effect of another control).

## Consequences

- Phase 2 event grouping treats a Quick Note window as a strong event boundary hint and a
  Highlight as a salience signal; both cite the segments overlapping their window.
- The watch sends `Highlight`/`QuickNote` as capture-layer actions (ADR-0008); the phone
  answers with the marker decision, never an optimistic success.
- If users want "record a note right now" from idle, that is a new decision (a short
  session with its own consent affordance), not a change to this marker model.
