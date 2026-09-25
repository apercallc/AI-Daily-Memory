# ADR-0006: Identity, Time, and Idempotency Model

- Status: Accepted
- Date: 2026-09-25

## Context

Capture is offline-first (ADR-0002), so the phone creates sessions, segments, highlights,
and quick notes long before the backend sees them. Retries, duplicate uploads, and
reprocessing must never create duplicate evidence. Recall (README "Temporal Filtering")
depends on `occurred_at`, `local_date`, and `timezone` — but those can only be recorded
correctly **at capture time**. They cannot be reconstructed in Phase 4.

## Decision

### Identifiers

- Every capture-layer record (recording session, audio segment, highlight, quick note,
  device) gets a **client-generated UUIDv7** on the device when it is created.
  UUIDv7 is time-ordered, which keeps PostgreSQL B-tree inserts local.
- The server accepts client IDs as the primary key, after an ownership check: an ID that
  already exists under another user is a conflict, never a merge.
- Server-derived records (transcript segments, events, memory items, jobs) use
  server-generated UUIDv7.
- The recording reducer never generates IDs; callers pass them in
  (`RecordingCommand.Start(sessionId)`), which keeps `core/recording` deterministic.

### Idempotency keys

| Operation | Idempotency key |
| --- | --- |
| Segment upload / finalize | `(session_id, segment_sequence)` + SHA-256 of the plaintext audio |
| Processing stage | `(stage, input_id, processor_version)` |
| Highlight / quick note sync | the client UUIDv7 |
| Client command (phone ↔ watch) | `command_id` (see ADR-0008) |

A replay with the same key and same checksum returns the original result. The same key
with a **different** checksum is a hard error, surfaced to the operator — never silently
overwritten.

### Time

Every captured record stores, at capture time:

- `occurred_at` — UTC instant from the wall clock
- `utc_offset_minutes` and IANA `timezone` — per record, not per session (sessions can
  cross timezones while travelling)
- `local_date` — derived on device from the above
- `elapsed_realtime_ms` + `boot_id` — monotonic device clock for ordering and gap
  detection that is immune to wall-clock jumps (NTP sync, manual changes)

Audio segments additionally carry a monotonic `sequence` per session. A gap in sequence
numbers or in monotonic time is a capture gap and must be visible in capture health.

## Consequences

- Retries are safe end-to-end without server round-trips during capture.
- Temporal recall can work on Phase 0 data; nothing needs backfilling.
- UUIDv7 requires a small generator on Android (not in `java.util.UUID`); it lives in the
  app layer, not the reducer.
- The server must treat client IDs as untrusted input: validate format and ownership.
