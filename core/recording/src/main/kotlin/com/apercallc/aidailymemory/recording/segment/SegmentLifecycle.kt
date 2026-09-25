package com.apercallc.aidailymemory.recording.segment

import com.apercallc.aidailymemory.recording.SessionId

/** Device-side lifecycle of one local audio segment (ADR-0007). */
enum class SegmentState {
    /** Being written. Not yet captured audio. */
    WRITING,

    /** Closed, fsynced, and checksummed. From here on it counts as captured audio. */
    SEALED,

    /** Bytes uploaded; waiting for the server to verify the checksum and persist it. */
    UPLOADED,

    /** Server confirmed it holds a verified copy. Deletable after the recovery window. */
    ACKNOWLEDGED,

    /** Failed validation when sealed. Reported as a capture gap and never uploaded. */
    QUARANTINED,

    /** Local file removed. Terminal. */
    DELETED,
}

enum class SegmentDefect {
    EMPTY,
    ZERO_DURATION,
    MALFORMED_CHECKSUM,
}

data class AudioSegment(
    val sessionId: SessionId,
    val sequence: Int,
    val state: SegmentState,
    val byteSize: Long = 0,
    val durationMs: Long = 0,
    val sha256: String? = null,
    val acknowledgedAtEpochMs: Long? = null,
    val defect: SegmentDefect? = null,
) {
    init {
        require(sequence >= 0) { "sequence must not be negative" }
        // Fail when corrupt persisted data is loaded, not later inside a reducer.
        require((state == SegmentState.ACKNOWLEDGED) == (acknowledgedAtEpochMs != null)) {
            "acknowledgedAtEpochMs must be set exactly when state is ACKNOWLEDGED"
        }
    }

    companion object {
        fun writing(sessionId: SessionId, sequence: Int) =
            AudioSegment(sessionId = sessionId, sequence = sequence, state = SegmentState.WRITING)
    }
}

sealed interface SegmentEvent {
    data class Seal(val byteSize: Long, val durationMs: Long, val sha256: String) : SegmentEvent
    data object UploadCompleted : SegmentEvent
    data class ServerAcknowledged(val sha256: String, val atEpochMs: Long) : SegmentEvent

    /** Automatic cleanup. Only allowed once the retention policy permits it. */
    data class RetentionDelete(val nowEpochMs: Long) : SegmentEvent

    /** Explicit user deletion (a user right). Allowed in any state except while writing. */
    data object UserDelete : SegmentEvent
}

enum class SegmentRejection {
    INVALID_IN_STATE,
    CHECKSUM_MISMATCH,
    NOT_ACKNOWLEDGED,
    RECOVERY_WINDOW_OPEN,
    STILL_WRITING,
}

sealed interface SegmentTransition {
    val segment: AudioSegment

    data class Applied(override val segment: AudioSegment) : SegmentTransition
    data class Ignored(override val segment: AudioSegment) : SegmentTransition
    data class Rejected(
        override val segment: AudioSegment,
        val reason: SegmentRejection,
    ) : SegmentTransition
}

/**
 * Pure reducer for segment lifecycle. Enforces the reliability invariant from
 * QUALITY_GATES.md: persisted local audio is never deleted automatically before the server
 * acknowledges it and the recovery window has passed. Storage pressure never overrides this.
 * The capture engine stops recording instead (see StoragePolicy).
 */
class SegmentLifecycle(private val recoveryWindowMs: Long) {
    init {
        require(recoveryWindowMs >= 0) { "recoveryWindowMs must not be negative" }
    }

    fun reduce(segment: AudioSegment, event: SegmentEvent): SegmentTransition {
        val state = segment.state
        return when (event) {
            is SegmentEvent.Seal -> when (state) {
                SegmentState.WRITING -> SegmentTransition.Applied(seal(segment, event))
                else -> SegmentTransition.Rejected(segment, SegmentRejection.INVALID_IN_STATE)
            }

            SegmentEvent.UploadCompleted -> when (state) {
                SegmentState.SEALED ->
                    SegmentTransition.Applied(segment.copy(state = SegmentState.UPLOADED))
                // Retried upload of something already uploaded or acknowledged.
                SegmentState.UPLOADED, SegmentState.ACKNOWLEDGED ->
                    SegmentTransition.Ignored(segment)
                else -> SegmentTransition.Rejected(segment, SegmentRejection.INVALID_IN_STATE)
            }

            is SegmentEvent.ServerAcknowledged -> acknowledge(segment, event)

            is SegmentEvent.RetentionDelete -> retentionDelete(segment, event.nowEpochMs)

            SegmentEvent.UserDelete -> when (state) {
                SegmentState.WRITING ->
                    SegmentTransition.Rejected(segment, SegmentRejection.STILL_WRITING)
                SegmentState.DELETED -> SegmentTransition.Ignored(segment)
                else -> SegmentTransition.Applied(deleted(segment))
            }
        }
    }

    private fun deleted(segment: AudioSegment) =
        segment.copy(state = SegmentState.DELETED, acknowledgedAtEpochMs = null)

    private fun seal(segment: AudioSegment, event: SegmentEvent.Seal): AudioSegment {
        val defect = when {
            event.byteSize <= 0 -> SegmentDefect.EMPTY
            event.durationMs <= 0 -> SegmentDefect.ZERO_DURATION
            !SHA256_HEX.matches(event.sha256) -> SegmentDefect.MALFORMED_CHECKSUM
            else -> null
        }
        return segment.copy(
            state = if (defect == null) SegmentState.SEALED else SegmentState.QUARANTINED,
            byteSize = event.byteSize,
            durationMs = event.durationMs,
            sha256 = if (defect == null) event.sha256 else null,
            defect = defect,
        )
    }

    private fun acknowledge(
        segment: AudioSegment,
        event: SegmentEvent.ServerAcknowledged,
    ): SegmentTransition = when (segment.state) {
        // An ack may arrive before the local "upload completed" write lands (crash between them).
        SegmentState.SEALED, SegmentState.UPLOADED ->
            if (event.sha256.equals(segment.sha256, ignoreCase = true)) {
                SegmentTransition.Applied(
                    segment.copy(
                        state = SegmentState.ACKNOWLEDGED,
                        acknowledgedAtEpochMs = event.atEpochMs,
                    ),
                )
            } else {
                // The server holds different bytes: keep the local copy and re-upload.
                SegmentTransition.Rejected(segment, SegmentRejection.CHECKSUM_MISMATCH)
            }
        SegmentState.ACKNOWLEDGED -> SegmentTransition.Ignored(segment)
        else -> SegmentTransition.Rejected(segment, SegmentRejection.INVALID_IN_STATE)
    }

    private fun retentionDelete(segment: AudioSegment, nowEpochMs: Long): SegmentTransition =
        when (segment.state) {
            SegmentState.DELETED -> SegmentTransition.Ignored(segment)
            // Quarantined bytes are not usable audio; they can go once reported.
            SegmentState.QUARANTINED ->
                SegmentTransition.Applied(deleted(segment))
            SegmentState.ACKNOWLEDGED -> {
                val acknowledgedAt = checkNotNull(segment.acknowledgedAtEpochMs)
                // If the wall clock went backwards, the window just lasts longer, which is safe.
                if (nowEpochMs - acknowledgedAt >= recoveryWindowMs) {
                    SegmentTransition.Applied(deleted(segment))
                } else {
                    SegmentTransition.Rejected(segment, SegmentRejection.RECOVERY_WINDOW_OPEN)
                }
            }
            else -> SegmentTransition.Rejected(segment, SegmentRejection.NOT_ACKNOWLEDGED)
        }

    private companion object {
        val SHA256_HEX = Regex("^[0-9a-fA-F]{64}$")
    }
}
