package com.apercallc.aidailymemory.platform

import android.content.ContentResolver
import android.os.SystemClock
import android.provider.Settings
import com.apercallc.aidailymemory.recording.recovery.BootStamp
import com.apercallc.aidailymemory.recording.time.CaptureTime
import java.time.Instant
import java.time.ZoneId

/**
 * The one place that reads device clocks, so every captured record is stamped the same way
 * (ADR-0006). The boot id is the platform boot counter, which is readable without a permission
 * and cannot change while this process is alive.
 */
class DeviceClock(contentResolver: ContentResolver) {
    private val bootId: String? =
        Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
            .takeIf { it >= 0 }
            ?.toString()

    fun elapsedRealtimeMs(): Long = SystemClock.elapsedRealtime()

    fun now(): CaptureTime = CaptureTime.of(
        occurredAt = Instant.now(),
        timezone = ZoneId.systemDefault(),
        elapsedRealtimeMs = elapsedRealtimeMs(),
        bootId = bootId ?: UNKNOWN_BOOT,
    )

    fun bootStamp(): BootStamp = BootStamp(bootId, elapsedRealtimeMs())

    companion object {
        /** Stored when the boot counter is unreadable; read back as "unknown" (null). */
        const val UNKNOWN_BOOT = "unknown"

        fun bootStampOf(time: CaptureTime): BootStamp = BootStamp(
            bootId = time.bootId.takeUnless { it == UNKNOWN_BOOT },
            elapsedRealtimeMs = time.elapsedRealtimeMs,
        )
    }
}
