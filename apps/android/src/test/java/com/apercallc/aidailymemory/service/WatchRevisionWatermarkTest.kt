package com.apercallc.aidailymemory.service

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The watermark is what makes a deleted-newest-session journal rollback visible after restart. */
@RunWith(RobolectricTestRunner::class)
class WatchRevisionWatermarkTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("watch-watermark-test", Context.MODE_PRIVATE)

    @Before
    fun clear() = prefs.edit { clear() }

    @Test
    fun `an install that never published has no watermark`() {
        val watermark = WatchRevisionWatermark(prefs)
        watermark.load()

        assertNull(watermark.lastPublished)
    }

    @Test
    fun `the watermark survives process boundaries and never moves backwards`() {
        val first = WatchRevisionWatermark(prefs)
        first.load()
        first.published(42)

        // New process: reads what the last one published.
        val second = WatchRevisionWatermark(prefs)
        second.load()
        assertEquals(42L, second.lastPublished)

        // A snapshot older than the watermark does not lower it.
        second.published(30)
        assertEquals(42L, second.lastPublished)

        // A newer one does, and persists.
        second.published(50)
        assertEquals(50L, WatchRevisionWatermark(prefs).apply { load() }.lastPublished)
    }

    @Test
    fun `the first published revision starts the watermark from anything`() {
        val watermark = WatchRevisionWatermark(prefs)
        watermark.load()

        // Revision 0 happens on a fresh install before any transition.
        watermark.published(0)
        assertEquals(0L, watermark.lastPublished)

        watermark.published(1)
        assertEquals(1L, WatchRevisionWatermark(prefs).apply { load() }.lastPublished)
    }

    @Test
    fun `an instance that has not loaded reports nothing even after a previous publish`() {
        WatchRevisionWatermark(prefs).apply {
            load()
            published(42)
        }

        // A reader that skipped load() would see no watermark at all — which is why AppContainer
        // loads on first access: a snapshot built from an unloaded watermark could miss a
        // journal rollback and then be discarded by the watch as stale.
        assertNull(WatchRevisionWatermark(prefs).lastPublished)
    }
}
