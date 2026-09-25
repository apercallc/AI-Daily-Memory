package com.apercallc.aidailymemory.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.OffState
import com.apercallc.aidailymemory.recording.schedule.RhythmAction
import com.apercallc.aidailymemory.recording.schedule.RhythmPlanner
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The next thing the schedule will do, for the "Next: …" line. */
data class NextChange(val action: RhythmAction, val at: ZonedDateTime, val quietLabel: String?)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings

    val rhythm: StateFlow<DailyRhythm> = settings.rhythm
    val retention: StateFlow<AudioRetention> = settings.retention
    val hadUnreadableRhythm: Boolean get() = settings.hadUnreadableRhythm

    /** Phone storage per recorded hour at the current capture settings. */
    val bytesPerHour: Long get() = container.storageBytesPerHour

    fun updateRhythm(rhythm: DailyRhythm) {
        settings.setRhythm(rhythm)
        viewModelScope.launch {
            try {
                container.rhythm.onRhythmChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Saved already; the service's next check or the alarm applies it.
                logFailure(TAG, "rhythm_apply_failed", e)
            }
        }
    }

    fun updateRetention(retention: AudioRetention) = settings.setRetention(retention)

    companion object {
        private const val TAG = "SettingsViewModel"

        fun nextChange(rhythm: DailyRhythm, now: Instant, zone: ZoneId): NextChange? =
            RhythmPlanner.nextAction(rhythm, now, zone)?.let { (transition, action) ->
                NextChange(
                    action = action,
                    at = transition.at.atZone(zone),
                    quietLabel = (transition.to as? OffState.Quiet)?.label,
                )
            }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(container) as T
    }
}
