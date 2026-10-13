package com.apercallc.aidailymemory.service

import android.content.SharedPreferences
import androidx.core.content.edit
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.wear.RecordingOrigin

/**
 * Which device each recent session was started from. The microphone is always the phone's;
 * this only says whose button was pressed, so each screen can say "started from your watch".
 *
 * Recorded *before* the Start command is dispatched: the first snapshot of the new session is
 * published the moment the state changes, and it has to carry the origin already. A start that
 * is then refused (another start won) leaves a stray entry that never matches an open session,
 * so a short history is kept rather than one slot: the refused start must not overwrite the
 * winner's origin.
 */
class SessionOrigins(private val prefs: SharedPreferences) {
    @Synchronized
    fun record(sessionId: SessionId, origin: RecordingOrigin) {
        val entries = (read() + (sessionId.value to origin)).takeLast(CAPACITY)
        prefs.edit { putString(KEY_ENTRIES, entries.joinToString(SEPARATOR) { "${it.first}$PAIR${it.second.name}" }) }
    }

    /** Drops [sessionId] again: its Start was refused, so it must not push a real session out. */
    @Synchronized
    fun forget(sessionId: SessionId) {
        val kept = read().filterNot { it.first == sessionId.value }
        prefs.edit { putString(KEY_ENTRIES, kept.joinToString(SEPARATOR) { "${it.first}$PAIR${it.second.name}" }) }
    }

    /** The origin of [sessionId], or null when none is known (idle, an older install, or evicted). */
    @Synchronized
    fun originOf(sessionId: SessionId?): RecordingOrigin? =
        sessionId?.let { id -> read().lastOrNull { it.first == id.value }?.second }

    private fun read(): List<Pair<String, RecordingOrigin>> =
        prefs.getString(KEY_ENTRIES, null)
            ?.split(SEPARATOR)
            ?.mapNotNull { entry ->
                val id = entry.substringBefore(PAIR, missingDelimiterValue = "")
                val origin = RecordingOrigin.parse(entry.substringAfter(PAIR, missingDelimiterValue = ""))
                if (id.isEmpty() || origin == null) null else id to origin
            }
            .orEmpty()

    companion object {
        const val FILE_NAME = "session_origins"
        private const val KEY_ENTRIES = "entries"
        private const val CAPACITY = 4
        private const val SEPARATOR = ","
        private const val PAIR = "="
    }
}
