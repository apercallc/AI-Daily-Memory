package com.apercallc.aidailymemory.platform

import android.os.StatFs
import android.util.Log
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import com.apercallc.aidailymemory.recording.storage.StoragePolicy
import java.io.File

/** Reads free space where segments live and applies [StoragePolicy]. */
class StorageMonitor(
    private val directory: File,
    private val policy: StoragePolicy,
    private val queryUsableBytes: (File) -> Long = { StatFs(it.path).availableBytes },
) {
    /** Usable free bytes, or null when the platform query fails (never treated as OK). */
    fun freeBytes(): Long? {
        val existingPath = directory.nearestExistingAncestor() ?: run {
            Log.w(TAG, "storage_path_missing")
            return null
        }
        return try {
            queryUsableBytes(existingPath)
        } catch (e: IllegalArgumentException) {
            logFailure(TAG, "storage_query_failed", e, warning = true)
            null
        }
    }

    fun evaluate(pendingBytes: Long): StorageHealth =
        policy.evaluate(freeBytes(), pendingBytes.coerceAtLeast(0))

    private companion object {
        const val TAG = "StorageMonitor"
    }
}

internal fun File.nearestExistingAncestor(): File? {
    var candidate: File? = this
    while (candidate != null && !candidate.exists()) {
        candidate = candidate.parentFile
    }
    return candidate
}
