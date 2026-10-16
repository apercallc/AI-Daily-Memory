package com.apercallc.aidailymemory.settings

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.recording.appearance.ThemeMode
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.DailyWindow
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import java.time.LocalTime
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("settings-test", Context.MODE_PRIVATE)

    @Before
    fun clear() = prefs.edit { clear() }

    @Test
    fun `defaults are no schedule and transcripts only`() {
        val store = SettingsStore(prefs)

        assertEquals(DailyRhythm.None, store.rhythm.value)
        assertEquals(AudioRetention.TRANSCRIPT_ONLY, store.retention.value)
        assertEquals(com.apercallc.aidailymemory.recording.storage.MemoryRetention.KEEP_UNTIL_DELETED, store.memoryRetention.value)
        assertNull(store.lastRhythmCheckEpochMs)
        assertFalse(store.hadUnreadableRhythm)
    }

    @Test
    fun `settings persist across instances and publish immediately`() {
        val rhythm = DailyRhythm(sleep = listOf(DailyWindow(LocalTime.of(22, 30), LocalTime.of(6, 30))))
        val store = SettingsStore(prefs)

        store.setRhythm(rhythm)
        store.setRetention(AudioRetention.KEEP_7_DAYS)
        store.lastRhythmCheckEpochMs = 1234

        assertEquals(rhythm, store.rhythm.value)
        val reloaded = SettingsStore(prefs)
        assertEquals(rhythm, reloaded.rhythm.value)
        assertEquals(AudioRetention.KEEP_7_DAYS, reloaded.retention.value)
        assertEquals(1234L, reloaded.lastRhythmCheckEpochMs)

        reloaded.lastRhythmCheckEpochMs = null
        assertNull(SettingsStore(prefs).lastRhythmCheckEpochMs)
    }

    @Test
    fun `an unreadable schedule is set aside, reported, and cleared by saving a new one`() {
        prefs.edit { putString("daily_rhythm", "v9\ngarbage") }

        val store = SettingsStore(prefs)

        assertEquals(DailyRhythm.None, store.rhythm.value)
        assertTrue(store.hadUnreadableRhythm)
        // Kept for support, never silently lost.
        assertEquals("v9\ngarbage", prefs.getString("daily_rhythm_unreadable", null))

        store.setRhythm(DailyRhythm.None)
        assertFalse(store.hadUnreadableRhythm)
    }

    @Test
    fun `an unknown retention value falls back to the privacy default`() {
        prefs.edit { putString("audio_retention", "KEEP_FOREVER_AND_EVER") }

        assertEquals(AudioRetention.TRANSCRIPT_ONLY, SettingsStore(prefs).retention.value)
    }

    @Test
    fun `memory retention persists across instances and an unknown value falls back to the default`() {
        val store = SettingsStore(prefs)

        store.setMemoryRetention(com.apercallc.aidailymemory.recording.storage.MemoryRetention.KEEP_90_DAYS)

        assertEquals(
            com.apercallc.aidailymemory.recording.storage.MemoryRetention.KEEP_90_DAYS,
            SettingsStore(prefs).memoryRetention.value,
        )

        prefs.edit { putString("memory_retention", "KEEP_FOREVER_AND_EVER") }
        assertEquals(
            com.apercallc.aidailymemory.recording.storage.MemoryRetention.KEEP_UNTIL_DELETED,
            SettingsStore(prefs).memoryRetention.value,
        )
    }

    @Test
    fun `the theme follows the phone until chosen, and a choice persists`() {
        val store = SettingsStore(prefs)
        assertEquals(ThemeMode.SYSTEM, store.themeMode.value)

        store.setThemeMode(ThemeMode.DARK)

        assertEquals(ThemeMode.DARK, store.themeMode.value)
        assertEquals(ThemeMode.DARK, SettingsStore(prefs).themeMode.value)
    }

    @Test
    fun `an unreadable stored theme falls back to following the phone`() {
        prefs.edit { putString("theme_mode", "NEON") }

        assertEquals(ThemeMode.SYSTEM, SettingsStore(prefs).themeMode.value)
    }

    @Test
    fun `the walkthrough shows until it is finished once`() {
        val store = SettingsStore(prefs)
        assertFalse(store.onboardingDone.value)

        store.setOnboardingDone(true)

        assertTrue(store.onboardingDone.value)
        assertTrue(SettingsStore(prefs).onboardingDone.value)
    }

    @Test
    fun `the update check is remembered so a restart cannot repeat it`() {
        val store = SettingsStore(prefs)
        assertFalse(store.upgradeChecked)
        assertFalse(store.onboardingRecorded)

        store.upgradeChecked = true

        assertTrue(SettingsStore(prefs).upgradeChecked)
        assertFalse(SettingsStore(prefs).onboardingRecorded)
    }
}
