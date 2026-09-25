# ADR-0010: Microphone Foreground-Service Lifecycle

- Status: Accepted (verify on target hardware in Phase 0)
- Date: 2026-09-25

## Context

Android restricts microphone access for foreground services that are **started while the
app is in the background** ("while-in-use" restrictions, tightened for `targetSdk` 34+).
Depending on API level, the start either fails with a `SecurityException` or the service
runs but the microphone delivers silence. Starting a microphone FGS from
`BOOT_COMPLETED` is not allowed. Exemptions include user interaction with the app's own
notification and the app having a visible activity.

This directly affects three product flows:

1. **Watch "Start"** when the phone app is not in the foreground.
2. **Watch "Resume"** if pausing had stopped the service.
3. **Resume after reboot** (TODO.md Phase 0).

A silent-audio failure mode would violate "never silently pretend audio was captured".

## Decision

- **Pause keeps the foreground service alive** (microphone closed, notification still
  showing "Paused"). Resume from notification, phone UI, or watch reopens the mic inside
  an already-running service, which is allowed.
- **Stop ends the service.** A later watch `Start` with no running service is attempted;
  if the platform refuses, the phone replies `StartFailed(FOREGROUND_START_NOT_ALLOWED)`
  and posts a high-priority "Tap to start recording" notification. The watch shows that
  honestly.
- **After reboot**, never auto-start capture. Post a "Recording was interrupted by a
  restart — tap to resume" notification; the gap is recorded as an interruption.
- **Silence detection:** the capture engine monitors for digital silence (all-zero
  frames) that indicates a revoked/blocked mic and reports
  `Interrupted(MICROPHONE_UNAVAILABLE)` rather than recording silence as captured audio.
- The notification always carries Pause and Stop actions (recording state is never
  hidden; SECURITY.md).

## Consequences

- Watch-initiated cold start is best-effort; this is a product constraint to communicate,
  not a bug to work around. Verify behavior on the target phone + OnePlus Watch 2 early —
  a Phase 0 spike, not Phase 3, because it shapes the service design.
- A paused session still holds a foreground notification; that is intended (visible state).
