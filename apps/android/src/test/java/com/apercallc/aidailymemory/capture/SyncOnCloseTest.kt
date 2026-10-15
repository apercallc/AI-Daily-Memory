package com.apercallc.aidailymemory.capture

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncOnCloseTest {
    @Test
    fun closeSyncFailure_closesFileDescriptorAndPreservesFailure() {
        val file = File.createTempFile("segment-sync-failure", ".part")
        try {
            val output = FileOutputStream(file)
            val descriptor = output.fd
            val failure = IOException("sync failed")

            val thrown = assertFailsWith<IOException> {
                SyncOnClose(output) { throw failure }.close()
            }

            assertSame(failure, thrown)
            assertFalse(descriptor.valid())
        } finally {
            assertTrue(file.delete())
        }
    }

    @Test
    fun closeSuccessFlushesSyncsAndClosesFileDescriptor() {
        val file = File.createTempFile("segment-sync-success", ".part")
        try {
            val output = FileOutputStream(file)
            val descriptor = output.fd
            var syncCalls = 0

            SyncOnClose(output) { syncCalls++ }.close()

            assertEquals(1, syncCalls)
            assertFalse(descriptor.valid())
        } finally {
            assertTrue(file.delete())
        }
    }
}
