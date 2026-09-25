package com.apercallc.aidailymemory.recording.storage

/**
 * How long recorded audio stays on the phone once its transcript is safe (ADR-0013).
 *
 * The product keeps transcripts, not recordings: [TRANSCRIPT_ONLY] is the default. Keeping audio
 * is opt-in, costs phone storage, and is local only: cloud audio is always deleted after
 * transcription (ADR-0003). No setting ever deletes audio before the server has acknowledged
 * it (SegmentLifecycle).
 */
enum class AudioRetention(val keepDays: Int?) {
    /** Deleted after the short recovery window that follows a successful upload. */
    TRANSCRIPT_ONLY(keepDays = 0),
    KEEP_7_DAYS(keepDays = 7),
    KEEP_30_DAYS(keepDays = 30),

    /** Kept until the user deletes it, or storage runs out (capture then stops; nothing is purged). */
    KEEP_UNTIL_DELETED(keepDays = null),
    ;

    /** Extra time to keep audio after acknowledgement, or null to never delete automatically. */
    val keepForMs: Long? get() = keepDays?.let { it * DAY_MS }

    /**
     * Phone storage this setting needs for kept audio at a steady daily recording volume, or
     * null when it grows without bound.
     */
    fun estimatedBytes(bytesPerHour: Long, recordedHoursPerDay: Double): Long? {
        require(bytesPerHour > 0) { "bytesPerHour must be positive" }
        require(recordedHoursPerDay in 0.0..24.0) { "recordedHoursPerDay must be 0..24" }
        val days = keepDays ?: return null
        // TRANSCRIPT_ONLY still holds about a day while uploads and transcription finish.
        val heldDays = days.coerceAtLeast(1)
        return (bytesPerHour * recordedHoursPerDay * heldDays).toLong()
    }

    companion object {
        val Default = TRANSCRIPT_ONLY
        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
