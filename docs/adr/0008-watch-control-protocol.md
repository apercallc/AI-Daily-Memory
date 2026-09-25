# ADR-0008: Watch Control Protocol and App Identity

- Status: Accepted (implementation is Phase 3)
- Date: 2026-09-25

## Context

The watch is a controller that must show **acknowledged phone state**, never assumed
state (AGENTS.md). Two constraints shape this:

- The Wear OS Data Layer only delivers between a phone app and a watch app that have the
  **same applicationId and the same signing certificate**. The scaffold originally used
  `com.apercallc.aidailymemory.wear` for the watch — Data Layer traffic would never have
  arrived. An applicationId cannot be changed after the first Play release.
- Bluetooth links drop and messages can be delayed or repeated.

## Decision

### App identity

- Watch `applicationId` = phone `applicationId` = `com.apercallc.aidailymemory`, with the
  same `.debug` suffix in debug builds. Kotlin `namespace` stays `...aidailymemory.wear`.
- Both apps are signed with the same key. Release `versionCode`s must not collide
  (use distinct ranges per form factor when publishing).

### Protocol

- **Watch → phone:** `MessageClient` command `{command_id (UUID), command, sent_at}`.
  `command` is a `RecordingCommand` (`Start`, `Pause`, `Resume`, `Stop`) or a
  capture-layer action (`Highlight`, `QuickNote`). Watches never send `CaptureSignal`s.
- **Phone → watch:** `DataClient` item holding the latest snapshot
  `{revision, status, session_started_at, problem, capture_health}`. Data items are
  durable and delivered on reconnect; the watch renders only this snapshot.
- **Acknowledgement:** the phone replies to each `command_id` with the reducer's outcome
  (`Applied` / `Ignored` / `Rejected`) and the resulting `revision`. Repeated
  `command_id`s return the cached outcome.
- **Staleness:** the watch discards snapshots with a `revision` lower than the one it has
  shown. The reducer only ever increments `revision`, and the phone restores it from the
  persisted transition journal after process death or reboot. It never restarts at 0, so
  ordering is total across restarts. If the journal is lost (app data cleared), the phone
  sends a snapshot flagged `reset`, and the watch accepts it unconditionally.
- **Session scoping:** capture signals carry the `sessionId`. The reducer rejects a late
  signal from a previous session instead of applying it to the current one.
- **Timeout UX:** after a timeout without acknowledgement the watch shows "Not confirmed"
  and the last acknowledged state. It never flips the UI optimistically.

## Consequences

- The Wear module will depend on `core/recording` for shared types.
- Background start limits on the phone apply to watch-initiated `Start` (ADR-0010).
