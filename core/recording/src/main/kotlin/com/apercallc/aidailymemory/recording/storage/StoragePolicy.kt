package com.apercallc.aidailymemory.recording.storage

enum class StorageLevel {
    OK,

    /** Warn the user early, while there is still time to act. */
    LOW,

    /** Stop capture cleanly now, before writes start failing mid-segment. */
    CRITICAL,

    /** Free space could not be read. Never treated as OK. */
    UNKNOWN,
}

data class StorageHealth(
    val level: StorageLevel,
    /** Estimated recording time left before the hard stop. Null when free space is unknown. */
    val recordingMinutesLeft: Long?,
    /** Unacknowledged audio exceeds its budget, usually because uploads are stuck. */
    val pendingOverBudget: Boolean,
) {
    val mustStopCapture: Boolean get() = level == StorageLevel.CRITICAL
}

/**
 * Storage-pressure thresholds (TODO.md Phase 0 "Storage pressure").
 *
 * Unacknowledged audio is never deleted to make room (see SegmentLifecycle). Under pressure,
 * the only safe moves are to warn, stop capture, and let uploads drain.
 */
data class StoragePolicy(
    val warningFreeBytes: Long,
    val hardStopFreeBytes: Long,
    val maxPendingBytes: Long,
    val estimatedBytesPerHour: Long,
) {
    init {
        require(hardStopFreeBytes > 0) { "hardStopFreeBytes must be positive" }
        require(warningFreeBytes > hardStopFreeBytes) {
            "warningFreeBytes must be greater than hardStopFreeBytes"
        }
        require(maxPendingBytes > 0) { "maxPendingBytes must be positive" }
        require(estimatedBytesPerHour > 0) { "estimatedBytesPerHour must be positive" }
    }

    /** @param freeBytes usable free bytes, or null if the platform query failed. */
    fun evaluate(freeBytes: Long?, pendingBytes: Long): StorageHealth {
        require(pendingBytes >= 0) { "pendingBytes must not be negative" }
        val pendingOverBudget = pendingBytes > maxPendingBytes
        if (freeBytes == null || freeBytes < 0) {
            return StorageHealth(StorageLevel.UNKNOWN, null, pendingOverBudget)
        }
        val level = when {
            freeBytes <= hardStopFreeBytes -> StorageLevel.CRITICAL
            freeBytes <= warningFreeBytes || pendingOverBudget -> StorageLevel.LOW
            else -> StorageLevel.OK
        }
        val headroom = (freeBytes - hardStopFreeBytes).coerceAtLeast(0)
        val minutesLeft = headroom * MINUTES_PER_HOUR / estimatedBytesPerHour
        return StorageHealth(level, minutesLeft, pendingOverBudget)
    }

    companion object {
        private const val MINUTES_PER_HOUR = 60L
        private const val MIB = 1024L * 1024L

        /**
         * Provisional defaults. Replace with Phase 0 measurements (docs/performance/). Sized
         * for about 24 kbit/s Opus (~11 MiB/hour), with enough headroom to finish a segment
         * and keep the database writable.
         */
        val Provisional = StoragePolicy(
            warningFreeBytes = 1024 * MIB,
            hardStopFreeBytes = 200 * MIB,
            maxPendingBytes = 2048 * MIB,
            estimatedBytesPerHour = 11 * MIB,
        )
    }
}
