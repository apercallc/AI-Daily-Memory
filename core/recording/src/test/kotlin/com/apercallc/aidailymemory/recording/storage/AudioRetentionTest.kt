package com.apercallc.aidailymemory.recording.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AudioRetentionTest {
    private val mib = 1024L * 1024

    @Test
    fun `transcript only is the default and keeps nothing extra`() {
        assertEquals(AudioRetention.TRANSCRIPT_ONLY, AudioRetention.Default)
        assertEquals(0, AudioRetention.TRANSCRIPT_ONLY.keepForMs)
        assertEquals(7 * 24 * 60 * 60 * 1000L, AudioRetention.KEEP_7_DAYS.keepForMs)
        assertNull(AudioRetention.KEEP_UNTIL_DELETED.keepForMs)
    }

    @Test
    fun `storage estimates scale with days kept and hours recorded`() {
        // 15 MiB/h, 10 h/day.
        assertEquals(150 * mib, AudioRetention.TRANSCRIPT_ONLY.estimatedBytes(15 * mib, 10.0))
        assertEquals(1050 * mib, AudioRetention.KEEP_7_DAYS.estimatedBytes(15 * mib, 10.0))
        assertEquals(4500 * mib, AudioRetention.KEEP_30_DAYS.estimatedBytes(15 * mib, 10.0))
        assertNull(AudioRetention.KEEP_UNTIL_DELETED.estimatedBytes(15 * mib, 10.0))
        assertEquals(0, AudioRetention.KEEP_30_DAYS.estimatedBytes(15 * mib, 0.0))
    }

    @Test
    fun `estimate inputs are validated`() {
        assertFailsWith<IllegalArgumentException> { AudioRetention.KEEP_7_DAYS.estimatedBytes(0, 1.0) }
        assertFailsWith<IllegalArgumentException> { AudioRetention.KEEP_7_DAYS.estimatedBytes(1, 25.0) }
        assertFailsWith<IllegalArgumentException> { AudioRetention.KEEP_7_DAYS.estimatedBytes(1, -1.0) }
    }
}
