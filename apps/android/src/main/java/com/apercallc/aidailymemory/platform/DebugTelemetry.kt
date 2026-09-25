package com.apercallc.aidailymemory.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Battery and thermal samples for endurance runs (TODO.md Phase 0), debug builds only.
 * Numbers only, no content; read with `adb logcat -s DebugTelemetry` and record results in
 * docs/performance/. Release builds never run this.
 */
class DebugTelemetry(private val context: Context) {
    val enabled: Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Samples until cancelled (the service cancels it when it stops). */
    suspend fun sampleWhileRunning(intervalMs: Long = DEFAULT_INTERVAL_MS) {
        if (!enabled) return
        val battery = context.getSystemService(BatteryManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        while (true) {
            val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val microAmps = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            Log.d(
                TAG,
                "sample battery_pct=$percent current_ua=$microAmps " +
                    "charging=${battery.isCharging} thermal=${power.currentThermalStatus}",
            )
            delay(intervalMs)
        }
    }

    private companion object {
        const val TAG = "DebugTelemetry"
        const val DEFAULT_INTERVAL_MS = 5 * 60_000L
    }
}
