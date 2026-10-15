package com.apercallc.aidailymemory.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.recording.storage.StoragePolicy
import java.io.File
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class StorageMonitorTest {
    @Test
    fun `queries existing parent when segment directory does not exist`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val existingParent = File(context.cacheDir, "storage-monitor-${UUID.randomUUID()}")
        assertTrue(existingParent.mkdirs())
        val missingDirectory = File(existingParent, "segments")
        var queriedPath: File? = null
        val monitor = StorageMonitor(missingDirectory, StoragePolicy.Provisional) { path: File ->
            queriedPath = path
            123L
        }

        try {
            assertFalse(missingDirectory.exists())
            assertEquals(123L, monitor.freeBytes())
            assertEquals(existingParent, queriedPath)
            assertFalse(missingDirectory.exists())
        } finally {
            existingParent.deleteRecursively()
        }
    }
}
