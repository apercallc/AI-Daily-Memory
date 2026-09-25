package com.apercallc.aidailymemory.recording.recovery

/**
 * Where the persisted journal's last entry was written, in terms of the boot it happened in.
 *
 * @property bootId platform boot identifier at write time, or null if it could not be read.
 * @property elapsedRealtimeMs monotonic clock at write time (resets to zero on reboot).
 */
data class BootStamp(val bootId: String?, val elapsedRealtimeMs: Long) {
    init {
        require(elapsedRealtimeMs >= 0) { "elapsedRealtimeMs must not be negative" }
        require(bootId == null || bootId.isNotBlank()) { "bootId must not be blank" }
    }
}

/** Decides whether a restart was a process restart or a device reboot. */
object RestartClassifier {
    /**
     * A reboot is proven by a different boot id or by the monotonic clock going backwards
     * (it only resets on reboot). Otherwise it is the same boot, so the process restarted.
     *
     * With no boot id on either side, the monotonic clock alone decides. It can miss a reboot
     * only if the device has now been up longer than it had been at the last write, and then
     * the user still sees an interruption, just with the less specific cause.
     */
    fun classify(persisted: BootStamp, current: BootStamp): RestartCause {
        val bootIdsDiffer = persisted.bootId != null &&
            current.bootId != null &&
            persisted.bootId != current.bootId
        val clockWentBack = current.elapsedRealtimeMs < persisted.elapsedRealtimeMs
        return if (bootIdsDiffer || clockWentBack) {
            RestartCause.DEVICE_REBOOTED
        } else {
            RestartCause.PROCESS_RESTARTED
        }
    }
}
