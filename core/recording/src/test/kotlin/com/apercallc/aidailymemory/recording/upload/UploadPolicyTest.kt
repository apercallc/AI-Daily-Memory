package com.apercallc.aidailymemory.recording.upload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UploadPolicyTest {
    @Test
    fun `progress with more work runs again now`() {
        assertEquals(NextUpload.NOW, UploadPolicy.next(received = 3, remaining = 10, stoppedBy = null, crashed = false))
    }

    @Test
    fun `nothing left means done`() {
        assertEquals(NextUpload.NONE, UploadPolicy.next(3, 0, null, crashed = false))
        assertEquals(NextUpload.NONE, UploadPolicy.next(0, 0, null, crashed = false))
    }

    @Test
    fun `work left without progress backs off instead of spinning`() {
        assertEquals(NextUpload.BACKOFF, UploadPolicy.next(0, 4, null, crashed = false))
    }

    @Test
    fun `transient stops back off even after progress`() {
        for (stop in listOf(UploadStop.OFFLINE, UploadStop.SERVER, UploadStop.QUOTA)) {
            assertEquals(NextUpload.BACKOFF, UploadPolicy.next(5, 5, stop, crashed = false), stop.name)
        }
    }

    @Test
    fun `a refused token signs out and not being linked stops`() {
        assertEquals(NextUpload.SIGN_OUT, UploadPolicy.next(0, 5, UploadStop.UNAUTHORIZED, crashed = false))
        assertEquals(NextUpload.NONE, UploadPolicy.next(0, 5, UploadStop.NOT_LINKED, crashed = false))
    }

    @Test
    fun `a crash always backs off, whatever else happened`() {
        assertEquals(NextUpload.BACKOFF, UploadPolicy.next(0, 0, null, crashed = true))
        assertEquals(NextUpload.BACKOFF, UploadPolicy.next(0, 0, UploadStop.UNAUTHORIZED, crashed = true))
    }

    @Test
    fun `transcripts still being made mean check back later, uploads first`() {
        assertEquals(NextUpload.LATER, UploadPolicy.next(0, 0, null, crashed = false, awaiting = 3))
        assertEquals(NextUpload.NOW, UploadPolicy.next(2, 1, null, crashed = false, awaiting = 3))
    }

    @Test
    fun `negative counts are rejected`() {
        assertFailsWith<IllegalArgumentException> { UploadPolicy.next(-1, 0, null, false) }
        assertFailsWith<IllegalArgumentException> { UploadPolicy.next(0, -1, null, false) }
        assertFailsWith<IllegalArgumentException> { UploadPolicy.next(0, 0, null, false, awaiting = -1) }
    }
}
