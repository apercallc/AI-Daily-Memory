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
| Another app holds the microphone for minutes (video recording, voice chat that does not set a call mode) | Treated as MICROPHONE_UNAVAILABLE: retries back off to every 60 s and keep going for about 35 minutes, then a "tap to resume" notification | `InterruptionRecoveryPolicy.Default`, `RecoveryScheduler` | ✅ logic / 📐 device check |
| Incoming call rings, then is answered | Ringing does not interrupt; once answered the platform feeds silence, the session shows "Paused for a phone call" and resumes by itself when the call ends. Call audio is never recorded | `AudioCaptureEngine`, `RecoveryScheduler` | ✅ logic / 📐 device check |
| Person looks for proof the mic is on, away from the app | Ongoing notification with a running timer (only while audio is saved), the Android microphone indicator, and a Quick Settings tile lit only while recording; the tile pauses on tap | `CaptureNotifications`, `RecordingTileService` | ✅ (tile look unit tested) / 📐 device check |
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
| OEM battery killers (aggressive vendors) | Kill shows up as a process-restart interruption; an info card opens this app's App info page so its Battery setting is reachable even when it is absent from the optimizer allow-list | `RecordingController`, capture screen, `BatteryGuidance` | ✅ App-specific Battery settings link for all vendors; Samsung also gets One UI steps (Unrestricted, Sleeping/Deep sleeping apps) |
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
| Recording or route callback registration / teardown fails | The capture job still attempts removal of registered callbacks, recorder stop, and release; cleanup failures are logged after all release actions run | `AudioCaptureEngine` | ✅ (device fault injection pending) |
| Marker while not capturing, or double tap | Refused / acknowledged once | `MarkerPolicy` (ADR-0011) | ✅ |
| Recording notification blocked, channel disabled, or swiped away (Android 14+) | Mic never opens without a visible indicator (`INDICATOR_HIDDEN`), re-checked while capturing; a dismissed indicator is re-posted | `RecordingService`, `CaptureNotifications`, `CommandReceiver` | ✅ |
| Slow fsync / storage stall during segment rotation | Reader thread never touches the disk; 2 s platform buffer plus a 5 s writer backlog; a full backlog stops capture visibly instead of dropping audio | `AudioCaptureEngine` | ✅ (device measurement pending) |
| Seal fails (close/fsync/rename) or the segment row cannot be written (`SQLiteFullException`) | Capture stops with an interruption; health only counts real seals as progress | `AudioCaptureEngine`, `SegmentStore` | ✅ |
| Unexpected crash inside the capture job | Always reported as `Failed(UNKNOWN)`; never left claiming RECORDING | `AudioCaptureEngine.open` | ✅ |
| Caller cancelled mid-command (service destroyed, recovery wait superseded) | Reduce → journal → effects runs to completion once started | `RecordingController` | ✅ |
| Interruption restored from a dead process (e.g. during a call) | Never retried automatically; the user resumes | `RecoveryScheduler` | ✅ |
| Command while the service cannot enter the foreground (permission revoked, background) | The service is not started (that would crash); the command runs detached and reports the problem | `RecordingService.send` | ✅ |
| Background start from watch refused | The command is dispatched detached, the foreground-service failure is reflected in phone state, and no microphone opens without the service | `RecordingService.sendWatchCommand`, `RecordingService.execute` | ✅ (logic; device check pending) |

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
| User deletes a session whose derived memory was already pushed to a sync target | Queued locally (`sync_deletion_queue`) and sent as `deletedSessions` on the next sync; the server purges its memory items and strips the session from stored daily reports (deleting the whole day if nothing else remains) | `SessionDataDeletion`, `SyncManager`, backend `/v1/sync` | ✅ |
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
| Account-deletion object purge / temporary-audio retention never run in production | API process can run the sweep itself (`RETENTION_SWEEP_INTERVAL_SECONDS`), so cleanup still happens without a separately deployed worker | `app.main.retention_sweep_loop` | ✅ |
| Sync item with an out-of-contract kind/confidence/direction, or a commitment missing its direction | 422 with field locations; nothing partially saved | `app/api/sync.py` (`SyncItemIn`, DB CHECK constraints mirrored) | ✅ |

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

| Edge case | Behavior | Where | Status |
| --- | --- | --- | --- |
| Command lost or delayed | Timeout → "Not confirmed"; the watch keeps the last phone snapshot and retries with the same command ID | `WearControlReducer`, `WearMainActivity` | ✅ (logic; device check pending) |
| Duplicate/reordered command delivery | Recent command IDs are serialized, deduplicated, and return the cached reducer/marker outcome | `WatchCommandBridge`, `CommandAcknowledgements` | ✅ (unit tested) |
| Stale snapshot after reconnect | Lower `revision` discarded; phone republishes state restored from the journal after a restart | `SnapshotRevisionPolicy`, `AppContainer` | ✅ (logic; device check pending) |
| Phone app data cleared (journal lost) | Revision-zero snapshot flagged `reset`, accepted unconditionally | `AppContainer`, `SnapshotRevisionPolicy` | ✅ (logic; device check pending) |
| Watch Start blocked by Android foreground-service rules | Command runs detached; the phone-authored failure state/problem is returned and the microphone never opens without the foreground service | `RecordingService`, `WatchCommandBridge` | ✅ (unit tested; device check pending) |

| Start pressed on the phone and the watch at once | One session wins in the state machine; the other device is told "Already recording" (not a generic failure) | `RecordingStateMachine`, `RejectionExplainer` | ✅ (unit tested; device check pending) |
| Which device started the session | `RecordingOrigin` is recorded before the Start is dispatched, so the first snapshot carries it; a refused start does not overwrite the winner's | `SessionOrigins`, `WatchCommandBridge` | ✅ (unit tested) |
| Watch pauses or stops the phone | The phone shows a one-line "from your watch" message once per applied command (not for redelivered or refused ones) | `WatchCommandBridge.onApplied`, `CaptureViewModel` | ✅ (unit tested) |

## 7. Daily rhythm and sessions (ADR-0012)

| Edge case | Behavior | Where | Status |
| --- | --- | --- | --- |
| Quiet time starts while recording | Paused on time (in-service timer; the CPU is awake for audio) | `RhythmRunner`, service | ✅ |
| Quiet time ends while the phone is asleep | Inexact alarm resumes it a few minutes late; late only ever means less recording | `AndroidRhythmAlarms` | ✅ logic / 📐 device check |
| User pauses during quiet time | Becomes the user's pause; never auto-resumed | reducer | ✅ |
| User resumes during quiet time | Honored; not re-paused (edge-triggered) | reducer, `RhythmPlanner` | ✅ |
| A whole quiet window passes while nobody checked | No action (catch-up applies only the last change) | `RhythmPlanner.catchUpAction` | ✅ |
| Overlapping quiet windows | One pause, one resume | `RhythmPlanner` | ✅ |
| Waking straight into a quiet window | Stays paused until it ends | `RhythmTransition.actionFor` | ✅ |
| Bedtime while recording | Session ends and the day closes; nothing runs overnight | `ScheduledStop` | ✅ |
| Morning | Reminder with Start; never starts capture; skipped if already recording or >2 h late | `RhythmRunner` | ✅ |
| Schedule tick that doesn't apply (idle, already paused) | Ignored, never shown as an error | reducer | ✅ |
| Process death during a schedule pause | Restored as a schedule pause (journal cause) | `JournalCodec` | ✅ |
| Clock set backwards, or time zone change | No replay of past actions; windows follow the new zone from now on | `RhythmRunner`, `RhythmReceiver` | ✅ |
| DST gap/overlap inside a window | Window shifted by the gap, or skipped if it collapses | `DailyWindow` | ✅ |
| Schedule edited so "now" is quiet | Paused immediately | `RhythmRunner.onRhythmChanged` | ✅ |
| Stored schedule unreadable | No schedule (only ever less automation), raw value kept, user told | `SettingsStore` | ✅ |
| 00:30 conversation / 05:30 early start | Previous day / new day (day turns at the sleep midpoint) | `MemoryDays` | ✅ |
| Weekend with no sleep schedule | Day turns at 04:00, not stretched to Monday | `MemoryDays` | ✅ |
| Night-shift sleep (08:00–16:00) | Day turns at its own midpoint | `MemoryDays` | ✅ |
| Schedule changed after the fact | Past sessions keep their memory day | `session_info.memory_day` | ✅ |
| Rename with newlines, bidi overrides, 61+ chars, or pasted no-break spaces | Rejected, or normalized | `SessionTitles` | ✅ |
| Two sessions in one part of the day | "Afternoon", then "Afternoon 2" | `SessionTitles` | ✅ |
| Unknown stored zone (older tz database) | Falls back to device zone instead of crashing the list | `SessionGroups` | ✅ |
| Keep-audio chosen, storage fills | Capture stops; kept audio never purged | `StoragePolicy`, `SegmentLifecycle` | ✅ |
| Report text contains Markdown/HTML/links | Escaped in the export | `app/reports/daily.py` | ✅ |
| Report generated while audio is pending | Marked partial with the pending amount | report contract | ✅ |

## 8. Found by the codebase scan (fixed, with regression tests)

| Edge case | Behavior now |
| --- | --- |
| Mic "recovers" on a silent buffer and fails again | Only real signal confirms capture; the retry budget resets after a stable minute |
| Journal unreadable at start | Restore completes with a visible problem instead of hanging every command |
| Report consumer hits one error | Keeps running; stale reports from an earlier mic open are dropped |
| Pause/Stop tapped with no service running | Handled without starting a foreground service (no crash path) |
| "Resume" on an alert with no service | Goes through a non-exported activity so the mic starts in the foreground |
| Retry pending while the phone sleeps | Short, timed partial wake lock |
| Crash between the file rename and the SEALED row | Segment re-verified and kept, not orphaned |
| Service destroyed while capturing | Reported as an interruption, not a stale "Recording" |
| "Hide silent notifications" enabled | Indicator channel is default importance (silent sound), so it stays visible |
| Duplicate Start tap | Ignored instead of a false "action not available" |

## 9. Where memory lives, speech, and speakers (ADR-0014/0015/0016)

Speech and speaker cases are listed in ADR-0015 and ADR-0016. This section covers storage and
data rights.

| Edge case | Designed behavior |
| --- | --- |
| Phone lost or reset in "On this phone" mode | Data is gone except Drive exports. Onboarding says so, and the app shows "last saved to Drive" |
| User creates an API key while in "On this phone" | Blocked with an explanation, and offered "Reports in my cloud account" |
| Phone offline longer than the transient hold (7 days) | Bundle is crypto-shredded. The audio is still unacknowledged on the phone and is re-uploaded |
| Crash between bundle download and ack | Ack happens only after verify + persist. The re-download is idempotent by day and bundle ID |
| Switch cloud to phone mid-download | Server deletes only after the phone reports a verified, complete copy |
| Two phones on one account in "On this phone" mode | v1 allows one capture phone per account. A second phone must use a cloud mode |
| Drive token revoked, Drive full, or Drive offline | Export is marked failed, retried with backoff, and the user is notified once. The report stays on the phone |
| User edits the report in Drive | Never read back. Drive is a copy, not the source of truth |
| Subscription lapses | Data already on the phone or in the cloud stays readable and exportable. Only new processing stops, and audio waits under normal retention |
| Processing error mid-day | Job dead-letters with a reason. The phone's audio is still unacknowledged, so the next night retries |
| Transcript text in logs or crash reports | Forbidden by the logging rules. Providers are zero-retention and no-training |
| Other people's voices | No voiceprints, ever. They're anonymous per conversation, and names are text labels |

## 10. Upload and processing (Phase 1 backend)

| Edge case | Behavior now |
| --- | --- |
| Uploads while no transcription provider is enabled (Gate B) | Refused with 503 `processing_unavailable`; audio stays on the phone |
| Same upload id retried | Same answer; a still-pending upload gets a fresh presigned URL |
| Same upload id, different bytes or capture time | 409 `idempotency_conflict`, logged |
| Same clip re-created under a new id (phone lost the old id) | A pending original is replaced; a received one is reported under its id |
| Presigned URL expired | Creating again re-issues it |
| Bytes don't match size or SHA-256 | Object deleted; 409 `upload_integrity_failed`; the phone uploads again |
| Store doesn't report checksums | 503 `integrity_unverifiable`, object kept, operator alerted; no re-upload loop |
| Concurrent creates racing the daily quota | Serialized per user with an advisory lock |
| Worker dies mid-job | Lease expires and another worker reclaims; repeated deaths dead-letter |
| Worker restarted with the same id | Fenced by attempt number, not just worker id |
| Worker clocks disagree | Leases and backoff use the database clock |
| Transcription dead-lettered | Upload marked `failed` with a reason; audio deleted |
| Upload never completed, or unprocessed for 7 days | Retention sweep deletes the audio, then marks `failed` |
| Account deletion with partial S3 failures | `DeleteIncompleteError`; never reported as deleted |
| Users not in full cloud memory | Never transcribed into server storage (bundle path pending, ADR-0014) |

## 11. Phone uploads and sealed delivery (ADR-0014/0015)

| Edge case | Behavior now |
| --- | --- |
| Object storage returns 403 (expired URL, clock skew) | Retried; never treated as a signed-out account |
| Server down or processing disabled | System exponential backoff; the job never spins |
| Account connected after weeks of recording | Only new audio uploads unless the user ticks "also transcribe what's on this phone" |
| Reconnected to another account without the backlog | Clips planned under the earlier consent never upload under the new link |
| Segment unreadable or with no speech | Leaves the queue with a visible outcome; never blocks newer audio |
| Keystore briefly unavailable | Audio treated as "try later", never as corrupt; crash recovery keeps the file |
| Two phones on one account | Each receives only its own transcripts (sealed to the uploading device's key) |
| Stolen device token tries to register its own key | Refused: one key per token for its lifetime |
| Token revoked with transcripts waiting | Its key and sealed copies are removed; the phone re-uploads after reconnecting |
| Disconnect while offline | Local link forgotten at once; the server revoke is kept sealed and retried |
| Transcript expired, failed, or unknown to the server | The phone re-uploads from its kept audio |
| Ciphertext moved onto another clip | Fails to open (upload id bound as HPKE info); never acknowledged |
| Unknown payload version or malformed transcript | Not stored, not acknowledged |
| Audio deletion | Only after every clip of the segment has its transcript on the phone |
| Sync endpoint missing while sync is on | Sync reports failure in settings instead of silently succeeding |
| Report generated while a sync POST is in flight | Sync cursor is the newest `generated_at` sent, so the report goes out next time |
| Processing enabled with incomplete provider settings | Sessions wait and settings list what is missing; nothing is marked FAILED |
| Notifications off, or status channel blocked or set to silent | In-app banner while a session is open or interrupted; the alert notification is never the only signal |
| Watch command retried after the phone process restarted | Recent acknowledgements are persisted, so the original outcome is returned and the command does not run twice |
| Several phones in range of the watch | The nearby node is chosen over a cloud-relayed one |
| Watch command never confirmed | Watch offers Retry (same id) or Dismiss (forgotten, later commands get fresh ids) |
| Update APK from a non-GitHub URL, wrong size, or wrong SHA-256 | Download refused or deleted; installer never sees it |
| Update check fails (offline, rate limit, bad JSON) | Shown as "couldn't check", never as up to date |

## 12. One server and voice-only analysis (ADR-0022)

| Edge case | Behavior | Where | Status |
| --- | --- | --- | --- |
| Phone linked by an older version to a server the user typed | Link dropped on load, token never sent, notice shown | `LinkStore` | ✅ (unit tested) |
| Sync on by default but never connected | Does nothing and is not an error | `SyncManager` | ✅ (unit tested) |
| Connecting must not send what was written before | Reports older than the link time stay on the phone unless the backlog box was ticked | `SyncManager.notBeforeEpochMs` | ✅ (unit tested) |
| Sign-up rate limited or email taken or weak password | Each has its own message next to the field | `AccountLinker`, `AccountSection` | ✅ (unit tested) |
| Test connection with no network, a revoked token, or a server error | One toast per outcome; a refusal does not unlink | `AccountLinker.testConnection` | ✅ (unit tested) |
| Music, held notes, rain, wind, rumble, silence | Not speech; never cut into clips | `VoiceVad` | ✅ (synthetic audio only; real recordings pending) |
| Speech with no pauses, or with background noise | Kept (the pitch moves and the level rises and falls) | `VoiceVad` | ✅ (synthetic audio only) |
| Microphone with a constant DC offset | Removed per frame before measuring loudness | `VoiceVad` | ✅ (unit tested) |

## Open

- ✅ Quick Note model: a marker window inside the active session (ADR-0011).
- ❓ Behavior when the user starts a phone call on speaker while recording (legal, per
  jurisdiction). Default: treat it as `PHONE_CALL` interruption and do not capture.
- ❓ Private device-test workflow: phone Stop now offers Keep/Delete, and local deletion removes
  the session's audio plus derived database rows. Deletion refuses while an upload row or server
  copy exists because no server-side deletion workflow is implemented; keep this visible and
  retryable rather than deleting local evidence while remote data remains. Watch-initiated Stop
  still needs device verification. Acceptance criteria remain tracked in TODO.
- ❓ A watch "report" request during capture must be acknowledged by the phone and cover the
  full session through Stop. Disconnection, rejected background start, or a pending report must
  never be shown as completed.
- ✅ ADR-0020 and ADR-0026 accept on-device transcription/extraction and supersede ADR-0014's
  cloud-processing assumption. Production readiness still depends on the Phase 0 model/device,
  quality, and battery benchmarks; inference failure keeps local audio available for recovery.

## 13. Error handling (whole-app pass)

| Case | Behaviour | Where | Status |
| --- | --- | --- | --- |
| A fault (not just I/O) while analyzing one segment | That segment is marked undecodable and the batch continues; later speech is still analyzed | `SpeechAnalyzer` | ✅ |
| Own provider answers 401/403, 402, 408/425/429, 5xx | Bad key is not retried; 402 or explicit quota exhaustion is terminal; an ordinary 429 is retried; no provider is switched automatically | `ProviderStatus`, `ApiKey*Provider` | ✅ |
| Processing hits an I/O or database error | Session stays pending and is retried, not failed for good | `ProcessingPipeline` | ✅ |
| Transcription provider fails before a transcript is saved | Encrypted local audio remains sealed and available for Retry or a user-selected provider change; no automatic provider switch | `ProcessingPipeline`, `SegmentStore` | ✅ |
| Summary provider fails after transcript persistence | Transcript remains immutable; error reason is shown, Retry reuses it, and turning summaries off before retry produces transcript-only output. Audio retention follows the user's delete-after-transcription setting. | `ProcessingPipeline`, `ProcessingSettingsSection` | ✅ |
| Provider cost is unknown or the session is deleted | Show usage counts but no fabricated dollar total when list price/token counts are missing; prune usage older than 30 days when recording a new sample and remove it with the session. | `ProviderCostEstimator`, `ProviderUsageDao`, `SessionDao` | ✅ |
| Sessions whose processing gave up | Count with a "Try again" button in Memory processing; transcript evidence is retained, while audio follows the configured retention setting | `ProcessingSettingsSection` | ✅ |
| Own provider returns a malformed item, or only malformed items | Dropped items are counted in the log; all-malformed fails visibly instead of producing an empty day | `ApiKeyExtractionProvider` | ✅ |
| Upload stops for the daily limit, server trouble or no connection | Account screen says which, and clears when a run gets through | `UploadStatusStore`, `AccountSection` | ✅ |
| Server answers sign-in with 5xx or 404/405 | "Server had a problem" / "not an AI Daily Memory server" instead of "can't reach" | `AccountLinker` | ✅ |
| Storage-mode call is refused (401) | Phone is signed out of the link instead of offering a pointless retry | `SettingsViewModel` | ✅ |
| Watch snapshot cannot be published | Logged; the "last published" mark does not advance, so the next change publishes again | `WatchDataLayerTransport` | ✅ |
| Watch message is not valid JSON | Logged with its type and dropped; the pending command times out as "Not confirmed" | `WearMainActivity` | ✅ |
| Transcript line cannot be decrypted (Keystore reset) | Shown as "This line can't be read on this device" instead of vanishing | `TranscriptText.unreadable` | ✅ |
| Transcript or memory list fails to load | Logged; transcript shows empty, memory shows its retry state | `SessionsViewModel`, `MemoryViewModel` | ✅ |
| Highlight / Quick Note cannot be saved | Message instead of a crash; recording is unaffected | `CaptureViewModel` | ✅ |
| API key cannot be sealed by the Keystore | Person is told it will not survive a restart | `ProcessingSettings.secretSaveFailed` | ✅ |
| Server sends a garbled deliveries page | Treated as a bad answer (retry later), not a crash | `HttpUploadApi` | ✅ |
| Damaged or non-https pending revoke entry | Dropped, never sent, and the person is told to revoke it on the server | `LinkStore` | ✅ |
| Backend: database down or pool exhausted | 503 `database_unavailable` with Retry-After (was a 500) | `errors.py` | ✅ |
| Backend: object store error on complete, delete or account deletion | 503 `storage_unavailable` / `Retry-After`; logged with an event name | `uploads.py`, `account.py` | ✅ |
| Backend: provider overloaded (529/425) or key rejected (401/403) | 529/425 retried; bad key named `provider_auth_failed`, not retried | `providers.py` | ✅ |
| Backend: extraction provider hangs | Hard timeout, retried as `provider_timeout` | `ExtractHandler` | ✅ |
| Backend: two first syncs for one day | Serialized per user and day instead of a 500 | `api/sync.py` | ✅ |
| Backend: `last_used_at` write fails | Logged; the request still succeeds | `auth.py` | ✅ |
| Backend: readiness | `/v1/ready` checks the database; `/v1/health` stays liveness only | `api/health.py` | ✅ |
| A delivery that can never be opened stays at the front of the poll | Counted and logged only; not yet acknowledged away | `DeliveryReceiver` | ⚠ open |
| A sealed segment rejected by the lifecycle is not a capture problem | Logged only | `SegmentStore` | ⚠ open |
| Dead backend jobs have no operator list or requeue command | Logged with a code | `jobs.py` | ⚠ open |
