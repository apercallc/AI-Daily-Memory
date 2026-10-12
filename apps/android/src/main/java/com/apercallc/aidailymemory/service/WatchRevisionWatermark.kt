package com.apercallc.aidailymemory.service

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Highest recording revision this install has ever published to a watch.
 *
 * The persisted value is what makes a journal that went backwards visible across process
 * boundaries (see [WatchSnapshotFactory.needsReset]): deleting the newest session removes its
 * journal rows, so after process death the restored revision can be lower than what a watch
 * has already seen. Comparing the two is what turns that into a `reset` snapshot the watch
 * accepts unconditionally instead of discarding (ADR-0008).
 *
 * [load] runs once at process start, off the main thread; [published] writes only when the
 * revision grows, so an ordinary day of recording costs at most one write per transition.
 */
class WatchRevisionWatermark(private val prefs: SharedPreferences) {
    /** Highest published revision, or null while this install has published none. */
    @Volatile var lastPublished: Long? = null
        private set

    /** Reads the persisted value once; safe to call again. */
    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        lastPublished = prefs.getLong(KEY_LAST_PUBLISHED, NONE).takeIf { it != NONE }
    }

    /** Records [revision] once a watch may have seen it. Never moves backwards. */
    @Synchronized
    fun published(revision: Long) {
        val previous = lastPublished
        if (previous != null && revision <= previous) return
        lastPublished = revision
        prefs.edit { putLong(KEY_LAST_PUBLISHED, revision) }
    }

    private var loaded = false

    private companion object {
        const val KEY_LAST_PUBLISHED = "last_published_revision"
        const val NONE = Long.MIN_VALUE
    }
}