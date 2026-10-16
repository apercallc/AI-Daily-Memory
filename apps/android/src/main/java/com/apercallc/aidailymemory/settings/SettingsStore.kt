package com.apercallc.aidailymemory.settings

import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.apercallc.aidailymemory.recording.appearance.ThemeMode
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.RhythmCodec
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import com.apercallc.aidailymemory.recording.storage.MemoryRetention
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User settings for the daily rhythm and audio retention. Backed by SharedPreferences, which are
 * excluded from backup and device transfer like all app data (data_extraction_rules.xml).
 *
 * Writes publish immediately; reads never throw. A stored schedule that can't be read is set
 * aside (not deleted) and the app runs with no schedule, which only ever means *more* manual
 * control, never recording someone didn't ask for.
 */
class SettingsStore(private val prefs: SharedPreferences) {
    private val _rhythm = MutableStateFlow(readRhythm())
    val rhythm: StateFlow<DailyRhythm> = _rhythm.asStateFlow()

    private val _retention = MutableStateFlow(readRetention())
    val retention: StateFlow<AudioRetention> = _retention.asStateFlow()

    private val _memoryRetention = MutableStateFlow(readMemoryRetention())
    val memoryRetention: StateFlow<MemoryRetention> = _memoryRetention.asStateFlow()

    fun setRhythm(rhythm: DailyRhythm) {
        prefs.edit {
            putString(KEY_RHYTHM, RhythmCodec.encode(rhythm))
            remove(KEY_RHYTHM_UNREADABLE)
        }
        _rhythm.value = rhythm
    }

    /** A saved schedule couldn't be read and none is active; the user should be told. */
    val hadUnreadableRhythm: Boolean get() = prefs.contains(KEY_RHYTHM_UNREADABLE)

    private val _themeMode = MutableStateFlow(ThemeMode.parse(prefs.getString(KEY_THEME, null)))

    /** Light, dark, or follow the phone (the default). */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    private val _onboardingDone = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_DONE, false))

    /** The first-run walkthrough has been finished or skipped. */
    val onboardingDone: StateFlow<Boolean> = _onboardingDone.asStateFlow()

    /** Whether the walkthrough has ever been marked finished or skipped (or skipped on upgrade). */
    val onboardingRecorded: Boolean get() = prefs.contains(KEY_ONBOARDING_DONE)

    /** The one-time check for a phone updated from an earlier version has run. */
    var upgradeChecked: Boolean
        get() = prefs.getBoolean(KEY_UPGRADE_CHECKED, false)
        set(value) = prefs.edit { putBoolean(KEY_UPGRADE_CHECKED, value) }

    fun setOnboardingDone(done: Boolean) {
        prefs.edit { putBoolean(KEY_ONBOARDING_DONE, done) }
        _onboardingDone.value = done
    }

    fun setRetention(retention: AudioRetention) {
        prefs.edit { putString(KEY_RETENTION, retention.name) }
        _retention.value = retention
    }

    fun setMemoryRetention(retention: MemoryRetention) {
        prefs.edit { putString(KEY_MEMORY_RETENTION, retention.name) }
        _memoryRetention.value = retention
    }

    /** Last instant the rhythm was checked; catch-up only looks at changes after it. */
    var lastRhythmCheckEpochMs: Long?
        get() = prefs.getLong(KEY_LAST_CHECK, NONE).takeIf { it != NONE }
        set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_CHECK) else putLong(KEY_LAST_CHECK, value)
        }

    private fun readRhythm(): DailyRhythm {
        val raw = prefs.getString(KEY_RHYTHM, null) ?: return DailyRhythm.None
        return try {
            RhythmCodec.decode(raw)
        } catch (_: IllegalArgumentException) {
            Log.e(TAG, "rhythm_unreadable")
            prefs.edit {
                putString(KEY_RHYTHM_UNREADABLE, raw)
                remove(KEY_RHYTHM)
            }
            DailyRhythm.None
        }
    }

    private fun readRetention(): AudioRetention {
        val raw = prefs.getString(KEY_RETENTION, null) ?: return AudioRetention.Default
        return AudioRetention.entries.firstOrNull { it.name == raw } ?: run {
            // Unknown value: fall back to the privacy-preserving default.
            Log.e(TAG, "retention_unreadable")
            AudioRetention.Default
        }
    }

    private fun readMemoryRetention(): MemoryRetention {
        val raw = prefs.getString(KEY_MEMORY_RETENTION, null) ?: return MemoryRetention.Default
        return MemoryRetention.entries.firstOrNull { it.name == raw } ?: run {
            Log.e(TAG, "memory_retention_unreadable")
            MemoryRetention.Default
        }
    }

    companion object {
        const val FILE_NAME = "settings"
        private const val TAG = "SettingsStore"
        private const val KEY_RHYTHM = "daily_rhythm"
        private const val KEY_RHYTHM_UNREADABLE = "daily_rhythm_unreadable"
        private const val KEY_RETENTION = "audio_retention"
        private const val KEY_MEMORY_RETENTION = "memory_retention"
        private const val KEY_LAST_CHECK = "rhythm_last_check_epoch_ms"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ONBOARDING_DONE = "onboarding_done"
        private const val KEY_UPGRADE_CHECKED = "upgrade_checked"
        private const val NONE = Long.MIN_VALUE
    }
}
