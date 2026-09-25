package com.apercallc.aidailymemory.platform

import android.os.StatFs
import android.util.Log
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import com.apercallc.aidailymemory.recording.storage.StoragePolicy
import java.io.File

/** Reads free space where segments live and applies [StoragePolicy]. */
class StorageMonitor(private val directory: File, private val policy: StoragePolicy) {
    /** Usable free bytes, or null when the platform query fails (never treated as OK). */
    fun freeBytes(): Long? = try {
        StatFs(directory.path).availableBytes
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "storage_query_failed", e)
        null
    }

    fun evaluate(pendingBytes: Long): StorageHealth =
        policy.evaluate(freeBytes(), pendingBytes.coerceAtLeast(0))

    private companion object {
        const val TAG = "StorageMonitor"
    }
}
