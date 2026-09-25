# Edge-Case Catalog

Every failure mode we know about, the chosen behavior, and where that behavior lives.
**Status:** ✅ implemented and tested · 📐 decided (ADR/design), not built yet · ❓ open.

Guiding rules: never pretend audio was captured; never delete audio the server doesn't
have; a privacy pause always wins; every failure is visible and has a recovery path.

## 1. Capture (phone)

| Edge case | Behavior | Where | Status |
| --- | --- | --- | --- |
| Duplicate command (watch retry, double tap) | `Ignored`, acknowledged with current state | `RecordingStateMachine` | ✅ |
| Command invalid in current state | `Rejected`, state unchanged, logged; never crashes the service | `RecordingStateMachine` | ✅ |
| Late engine callback from a previous session | `Rejected` (signals are session-scoped) | `RecordingStateMachine` | ✅ |
| `Start` for a new session while one is active | `Rejected`, not silently merged | `RecordingStateMachine` | ✅ |
| Start fails (permission, mic busy, background start blocked) | `StartFailed(problem)` → IDLE with a visible reason | reducer + presenter | ✅ (logic) |
| Mic lost mid-recording (call, other app, route change) | `Interrupted(problem)` → RECOVERING → RECORDING, or back to INTERRUPTED | reducer | ✅ (logic) |
| User pauses during recovery | Pause wins; capture never auto-resumes after a privacy pause | reducer | ✅ |
| Mic silently blocked (delivers zeros) | Sustained exact-zero run → `MICROPHONE_BLOCKED` critical | `DigitalSilenceDetector`, health | ✅ (logic) |
| "Recording" but nothing being saved (stuck encoder, dead thread) | No sealed segment within segment length + grace → `NOT_CAPTURING` headline | `CaptureHealthEvaluator`, presenter | ✅ |
| Resume right after a long pause | Stall timer runs from whichever is newer: entering RECORDING or the last seal | health evaluator | ✅ |
| Process death while recording | On start: `Interrupted(PROCESS_RESTARTED)`, gap visible, no auto-restart | `StartupRecovery` | ✅ (logic) |
| Reboot while recording | `Interrupted(DEVICE_REBOOTED)` + "tap to resume" notification | `StartupRecovery`, ADR-0010 | ✅ logic / 📐 notification |
| Process death during STARTING / STOPPING | Becomes failed start / completed stop | `StartupRecovery` | ✅ |
| Corrupt persisted state (non-idle state without a session) | Refused on load (`RecordingState` invariant); the persistence layer fails loudly | `RecordingState.init` | ✅ |
| Crash mid-segment | Append-safe container; loss bounded to one segment; reported as a gap | ADR-0007 | 📐 |
| Zero-byte, zero-duration, or malformed-checksum segment | QUARANTINED with its defect, never uploaded, counted as a gap | `SegmentLifecycle` | ✅ |
| Missing, duplicate, or overlapping segments; hole inside continuous capture | Classified gap (`MISSING_SEGMENTS`, `DUPLICATE_SEQUENCE`, `OVERLAP`, `TIME_DISCONTINUITY`, `REBOOT`) | `GapDetector` | ✅ |
| Pause/resume boundaries | Not gaps (`continuesPrevious = false`) | `GapDetector` | ✅ |
| Low storage | Warn at threshold; stop capture cleanly at the hard stop, before writes fail | `StoragePolicy`, health | ✅ (logic) |
| Free space unreadable | `UNKNOWN`, treated as a warning, never OK | `StoragePolicy` | ✅ |
| Uploads stuck for days | Pending bytes over budget → warning. Unacknowledged audio is **never** deleted to make room | `StoragePolicy`, `SegmentLifecycle` | ✅ |
| Device locked all day | Keystore key without a user-auth requirement | ADR-0007 | 📐 |
| Before first unlock after reboot | No capture (credential-encrypted storage unavailable) | ADR-0007 | 📐 |
| OEM battery killers (aggressive vendors) | Detect via restart journal; guide the user to exemptions; never claim continuity | TODO Phase 0 | 📐 |
| Background start from watch refused | `StartFailed(FOREGROUND_START_NOT_ALLOWED)` + phone notification | ADR-0010 | 📐 |

## 2. Time and identity

| Edge case | Behavior | Where | Status |
| --- | --- | --- | --- |
| DST fall-back (01:30 happens twice) | Each record stores its own offset; both land on the same local date | `CaptureTime` | ✅ |
| DST spring-forward | Offset switches at the exact instant | `CaptureTime` | ✅ |
| Travel across time zones mid-session | Offset and local date are per record, not per session | `CaptureTime` | ✅ |
| Half- and quarter-hour zones (India, Nepal) | Stored in minutes | `CaptureTime` | ✅ |
| Wall clock jumps (NTP, manual change) | Durations use the monotonic clock within a boot; no cross-boot comparator (it would be non-transitive and break sorting) | `CaptureTime` | ✅ |
| Many IDs in one millisecond | UUIDv7 counter; overflow borrows the next millisecond | `UuidV7Generator` | ✅ |
| Clock goes backwards while generating IDs | IDs stay strictly increasing | `UuidV7Generator` | ✅ |
| Same client ID from two users | Conflict, never a merge (ownership check) | ADR-0006 | 📐 |

## 3. Upload and sync

| Edge case | Behavior | Where | Status |
| --- | --- | --- | --- |
| Upload retried after success | `Ignored` (idempotent) | `SegmentLifecycle` | ✅ |
| Server ack arrives before the local "uploaded" write (crash between the two) | Ack accepted from SEALED | `SegmentLifecycle` | ✅ |
| Server checksum differs from local | `CHECKSUM_MISMATCH`, local copy kept, re-upload | `SegmentLifecycle` | ✅ |
| Same idempotency key with different bytes | Hard, operator-visible error | ADR-0006 | 📐 |
| Wall clock moves backwards during the recovery window | Window only gets longer, which is safe | `SegmentLifecycle` | ✅ |
| User deletes a segment | Allowed in every state except WRITING (a user right) | `SegmentLifecycle` | ✅ |
| Signed URL expires mid-upload | Request a new URL; the retry is idempotent | Phase 1 | 📐 |
| Offline for days | Queue bounded by `maxPendingBytes`; capture continues until the hard stop | `StoragePolicy` | ✅ (logic) |

## 4. API (backend)

| Edge case | Behavior | Where | Status |
| --- | --- | --- | --- |
| Oversized body with Content-Length | 413 before reading the body | `RequestContextMiddleware` | ✅ |
| Chunked, or Content-Length lies | Real bytes counted; 413 | middleware | ✅ |
| Malformed or negative Content-Length | 400 | middleware | ✅ |
| Validation error containing private text | 422 with field locations and types only; input never echoed | `errors.py` | ✅ |
| Unhandled exception | 500 envelope with request ID; log has type and frames, never the message | middleware, `observability.py` | ✅ |
| Exception after streaming has started | Re-raised (the connection is broken); logged | middleware | ✅ |
| Spoofed or malicious `X-Request-ID` (log injection) | Replaced unless `[A-Za-z0-9._-]{1,64}` | middleware | ✅ |
| Credentials or transcript fields in logs | Redacted by field name; query strings never logged | `observability.py` | ✅ |
| 401 / 405 protocol headers | Preserved (`WWW-Authenticate`, `Allow`) | `errors.py` | ✅ |
| API docs exposure | Off outside development and test | `create_app` | ✅ |
| Cross-user access | Authorization in the query service; cross-user tests for every resource | ADR-0009 | 📐 |
| Hot user or abusive API key | Per-user and per-key rate limits | Phase 5/10 | 📐 |

## 5. Processing, memory, and recall (later phases)

| Edge case | Behavior | Status |
| --- | --- | --- |
| Worker dies holding a job | Lease expiry → job reclaimed (`FOR UPDATE SKIP LOCKED`) | 📐 |
| Provider timeout or 5xx | Bounded exponential backoff with jitter → dead letter with reason | 📐 |
| Provider returns partial or invalid transcript | Stage fails visibly; raw audio retained | 📐 |
| Transcript contains prompt injection | Delimited as data; schema-validated output; no tools at extraction | 📐 |
| Re-transcription with a better model | New revision; one active per segment; evidence links stay valid | 📐 (ADR-0004) |
| User deletes a day | Cascades to items, events, summaries, embeddings, and cached answers | 📐 (ADR-0004/0009) |
| Question with no supporting evidence | Answer abstains | 📐 |
| Name that is a mention vs. a speaker ("John said" vs. John speaking) | Entity mention ≠ speaker identity | 📐 |

## 6. Watch

| Edge case | Behavior | Status |
| --- | --- | --- |
| Command lost or delayed | Timeout → "Not confirmed"; UI never flips optimistically | 📐 (ADR-0008) |
| Stale snapshot after reconnect | Lower `revision` discarded; revision restored from the journal after a restart | 📐 |
| Phone app data cleared (journal lost) | Snapshot flagged `reset`, accepted unconditionally | 📐 |

## Open

- ❓ Quick Note model: a window inside a session, or its own session.
- ❓ Behavior when the user starts a phone call on speaker while recording (legal, per
  jurisdiction). Default: treat it as `PHONE_CALL` interruption and do not capture.
