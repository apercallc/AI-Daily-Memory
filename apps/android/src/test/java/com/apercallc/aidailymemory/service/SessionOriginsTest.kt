package com.apercallc.aidailymemory.service

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.wear.RecordingOrigin
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class SessionOriginsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("origins-test", Context.MODE_PRIVATE)

    @Before
    fun clear() = prefs.edit { clear() }

    @Test
    fun `a session remembers which device started it, across restarts`() {
        SessionOrigins(prefs).record(SessionId("a"), RecordingOrigin.WATCH)

        assertEquals(RecordingOrigin.WATCH, SessionOrigins(prefs).originOf(SessionId("a")))
    }

    @Test
    fun `an unknown or missing session has no origin`() {
        val origins = SessionOrigins(prefs)

        assertNull(origins.originOf(null))
        assertNull(origins.originOf(SessionId("never-seen")))
    }

    @Test
    fun `a refused start does not overwrite the winner's origin`() {
        val origins = SessionOrigins(prefs)
        origins.record(SessionId("winner"), RecordingOrigin.WATCH)
        // The phone's own start lost the race: its entry is recorded first and never matches.
        origins.record(SessionId("loser"), RecordingOrigin.PHONE)

        assertEquals(RecordingOrigin.WATCH, origins.originOf(SessionId("winner")))
    }

    @Test
    fun `forgetting a refused start leaves the winner alone`() {
        val origins = SessionOrigins(prefs)
        origins.record(SessionId("winner"), RecordingOrigin.PHONE)
        origins.record(SessionId("refused"), RecordingOrigin.WATCH)

        origins.forget(SessionId("refused"))

        assertNull(origins.originOf(SessionId("refused")))
        assertEquals(RecordingOrigin.PHONE, origins.originOf(SessionId("winner")))
    }

    @Test
    fun `only the most recent few sessions are kept`() {
        val origins = SessionOrigins(prefs)
        (1..6).forEach { origins.record(SessionId("s$it"), RecordingOrigin.PHONE) }

        assertNull(origins.originOf(SessionId("s1")))
        assertEquals(RecordingOrigin.PHONE, origins.originOf(SessionId("s6")))
    }

    @Test
    fun `corrupt stored entries are skipped, not trusted`() {
        prefs.edit { putString("entries", "=PHONE,x=ALIEN,garbage,ok=WATCH") }

        val origins = SessionOrigins(prefs)

        assertEquals(RecordingOrigin.WATCH, origins.originOf(SessionId("ok")))
        assertNull(origins.originOf(SessionId("x")))
    }
}
