package com.apercallc.aidailymemory.platform

import android.content.Context
import android.os.PowerManager
import com.apercallc.aidailymemory.capture.KeepAwake

/**
 * Partial wake lock with a hard timeout, released as soon as the wait ends or is cancelled.
 * The timeout means a bug can cost at most [maxMs] of CPU, never a drained battery.
 */
class PartialWakeLock(context: Context, private val tag: String) : KeepAwake {
    private val power = context.getSystemService(PowerManager::class.java)

    override suspend fun <T> whileWaiting(maxMs: Long, block: suspend () -> T): T {
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
        lock.setReferenceCounted(false)
        lock.acquire(maxMs)
        try {
            return block()
        } finally {
            if (lock.isHeld) lock.release()
        }
    }
}
