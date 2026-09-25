# Edge-Case Catalog

Every failure mode we know about, the chosen behavior, and where that behavior lives.
**Status:** ✅ implemented and tested · 📐 decided (ADR/design), not built yet · ❓ open.

Guiding rules: never pretend audio was captured; never delete audio the server doesn't
have; a privacy pause always wins; every failure is visible and has a recovery path.

## 1. Capture (phone)

| Edge case | Behavior | Where | Status |
| --- | --- | --- | --- |
| Resume after process death or reboot while paused (service gone) | PAUSED → RESUMING; RECORDING only after the engine's `Started`; failure → INTERRUPTED with the session kept | reducer | ✅ |
| Duplicate command (watch retry, double tap) | `Ignored`, acknowledged with current state | `RecordingStateMachine` | ✅ |
| Command invalid in current state | `Rejected`, state unchanged, logged; never crashes the service | `RecordingStateMachine` | ✅ |
| Late engine callback from a previous session | `Rejected` (signals are session-scoped) | `RecordingStateMachine` | ✅ |
| `Start` for a new session while one is active | `Rejected`, not silently merged | `RecordingStateMachine` | ✅ |
| Start fails (permission, mic busy, background start blocked) | `StartFailed(problem)` → IDLE with a visible reason | reducer + presenter, `RecordingService` | ✅ |
| Mic lost mid-recording (call, other app) | `Interrupted(problem)`; bounded automatic recovery (backoff for mic loss, wait for call end); pause cancels it; gives up to a user notification | reducer, `InterruptionRecoveryPolicy`, `RecoveryScheduler`, `AudioCaptureEngine` | ✅ (on-device verification pending) |
| User pauses during recovery | Pause wins; capture never auto-resumes after a privacy pause | reducer | ✅ |
| Mic silently blocked (delivers zeros) or silenced by the platform | 4 s exact-zero run, or `AudioRecordingConfiguration.isClientSilenced` → `Interrupted(MICROPHONE_UNAVAILABLE)` (or `PHONE_CALL` when in a call); silence is never sealed as captured audio beyond the detection window | `DigitalSilenceDetector`, `AudioCaptureEngine` | ✅ (on-device verification pending) |
| "Recording" but nothing being saved (stuck encoder, dead thread) | No sealed segment within segment length + grace → `NOT_CAPTURING` headline | `CaptureHealthEvaluator`, presenter | ✅ |
| Resume right after a long pause | Stall timer runs from whichever is newer: entering RECORDING or the last seal | health evaluator | ✅ |
| Process death while recording | Every process start restores the journal: `Interrupted(PROCESS_RESTARTED)`, gap visible, "tap to resume" notification, no auto-restart; a sticky service restart only reports | `RecordingController`, `RestartClassifier`, `RecordingService` | ✅ |
| Reboot while recording | Boot id / monotonic clock → `Interrupted(DEVICE_REBOOTED)` + "tap to resume" notification after first unlock | `RestartClassifier`, `RestartReceiver`, ADR-0010 | ✅ |
| Process death during STARTING / STOPPING | Becomes failed start / completed stop | `StartupRecovery` | ✅ |
| Corrupt persisted state (non-idle state without a session) | Refused on load (`RecordingState` invariant); the persistence layer fails loudly | `RecordingState.init` | ✅ |
| Crash mid-segment | Row written before bytes; on restart the WRITING row is quarantined `INCOMPLETE` and its torn file removed; loss bounded to one segment and reported | `SegmentStore.abandonUnfinished`, `SegmentLifecycle` | ✅ |
| Zero-byte, zero-duration, or malformed-checksum segment | QUARANTINED with its defect, never uploaded, counted as a gap | `SegmentLifecycle` | ✅ |
| Missing, duplicate, or overlapping segments; hole inside continuous capture | Classified gap (`MISSING_SEGMENTS`, `DUPLICATE_SEQUENCE`, `OVERLAP`, `TIME_DISCONTINUITY`, `REBOOT`) | `GapDetector` | ✅ |
| Pause/resume boundaries | Not gaps (`continuesPrevious = false`) | `GapDetector` | ✅ |
| Low storage | Warn at threshold (health + UI "storage left"); refuse to start and stop at segment rotation at the hard stop, before writes fail; recovery waits for space | `StoragePolicy`, `AudioCaptureEngine`, `RecoveryScheduler` | ✅ (device test pending) |
| Free space unreadable | `UNKNOWN`, treated as a warning, never OK | `StoragePolicy` | ✅ |
| Uploads stuck for days | Pending bytes over budget → warning. Unacknowledged audio is **never** deleted to make room | `StoragePolicy`, `SegmentLifecycle` | ✅ |
| Device locked all day | Keystore key without a user-auth requirement | `SegmentCipher` | ✅ (device test pending) |
| Before first unlock after reboot | App is not direct-boot aware: no component runs before unlock; Start is also blocked by `DEVICE_LOCKED` | manifest, `CapturePreconditions` | ✅ |
| OEM battery killers (aggressive vendors) | Kill shows up as a process-restart interruption; an info card links to battery settings without promising it helps | `RecordingController`, capture screen | ✅ (vendor matrix pending) |
| Keystore unusable (Tink would silently fall back to a cleartext keyset) | Refused: `ENCRYPTION_UNAVAILABLE`, nothing written unencrypted, no phantom segment row | `SegmentCipher`, `SegmentStore` | ✅ |
| Journal's newest row undecodable | Restart IDLE with `UNKNOWN` problem, revision kept monotonic | `JournalCodec`, `RecordingController` | ✅ |
| Journal write fails | Transition not published and no effect runs; failure logged to the operator | `RecordingController` | ✅ |
| Foreground start refused (permission revoked, background) | Follow-up `StartFailed`/`Interrupted`; later effects skipped so the mic never opens without the service | `RecordingService`, `RecordingController` | ✅ |
| Notification permission denied | Recording does not start: the indicator would be invisible | `CapturePreconditions` | ✅ |
| Microphone permanently denied ("don't ask again") | Settings deep link instead of a dead system dialog | `PermissionFlow` | ✅ |
| App updated mid-session | `MY_PACKAGE_REPLACED` wakes the process; treated as a process restart | `RestartReceiver` | ✅ |
| Quick Stop → Start race on the service | `stopSelf(startId)` never stops a newer command | `RecordingService` | ✅ |
| Wedged encoder | Input stall bounded (~1 s) → `Interrupted(UNKNOWN)`; final drain bounded | `AacEncoder` | ✅ |
| Audio route change (headset, Bluetooth) | Capture continues on the new route; change logged for endurance correlation | `AudioCaptureEngine` | ✅ (device test pending) |
| Marker while not capturing, or double tap | Refused / acknowledged once | `MarkerPolicy` (ADR-0011) | ✅ |
| Recording notification blocked, channel disabled, or swiped away (Android 14+) | Mic never opens without a visible indicator (`INDICATOR_HIDDEN`), re-checked while capturing; a dismissed indicator is re-posted | `RecordingService`, `CaptureNotifications`, `CommandReceiver` | ✅ |
| Slow fsync / storage stall during segment rotation | Reader thread never touches the disk; 2 s platform buffer plus a 5 s writer backlog; a full backlog stops capture visibly instead of dropping audio | `AudioCaptureEngine` | ✅ (device measurement pending) |
| Seal fails (close/fsync/rename) or the segment row cannot be written (`SQLiteFullException`) | Capture stops with an interruption; health only counts real seals as progress | `AudioCaptureEngine`, `SegmentStore` | ✅ |
| Unexpected crash inside the capture job | Always reported as `Failed(UNKNOWN)`; never left claiming RECORDING | `AudioCaptureEngine.open` | ✅ |
| Caller cancelled mid-command (service destroyed, recovery wait superseded) | Reduce → journal → effects runs to completion once started | `RecordingController` | ✅ |
| Interruption restored from a dead process (e.g. during a call) | Never retried automatically; the user resumes | `RecoveryScheduler` | ✅ |
| Command while the service cannot enter the foreground (permission revoked, background) | The service is not started (that would crash); the command runs detached and reports the problem | `RecordingService.send` | ✅ |
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
| Corrupt persisted segment (ACKNOWLEDGED without ack time) | Refused at load (`AudioSegment` invariant) | `AudioSegment.init` | ✅ |
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
| Credentials or transcript fields in logs | Redacted by normalized field words (camelCase, dashes), including nested values; query strings never logged | `observability.py` | ✅ |
| Content passed as `%s` log args | Never interpolated: the message template is logged, not the args | `observability.py` | ✅ |
| Literals in traceback source lines | Frames logged as `file:line in func`, without source text | `observability.py` | ✅ |
| Uvicorn's own logs (access log with query strings; "Exception in ASGI application") | Access log disabled (ours replaces it); error logger routed through the redacting formatter | `configure_logging` | ✅ |
| Downstream already set `X-Request-ID` | Replaced, never duplicated | middleware | ✅ |
| Client disconnects mid-request | Logged as 499, not a 500; nothing sent | middleware | ✅ |
| Error codes drifting between Python versions | Fixed status→code table (public contract) | `errors.py` | ✅ |
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

- ✅ Quick Note model: a marker window inside the active session (ADR-0011).
- ❓ Behavior when the user starts a phone call on speaker while recording (legal, per
  jurisdiction). Default: treat it as `PHONE_CALL` interruption and do not capture.
