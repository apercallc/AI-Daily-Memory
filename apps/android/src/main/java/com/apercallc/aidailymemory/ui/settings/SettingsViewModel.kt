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
import com.apercallc.aidailymemory.net.LinkInput
import java.security.GeneralSecurityException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
        val previous = settings.rhythm.value
        settings.setRhythm(rhythm)
        viewModelScope.launch {
            try {
                container.rhythm.onRhythmChanged(previous)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Saved already; the service's next check or the alarm applies it.
                logFailure(TAG, "rhythm_apply_failed", e)
            }
        }
    }

    fun updateRetention(retention: AudioRetention) = settings.setRetention(retention)

    private val saveFailed = MutableStateFlow(false)

    val account: StateFlow<AccountUi> = combine(
        container.links.link,
        container.links.rejected,
        saveFailed,
    ) { link, rejected, failed ->
        AccountUi(host = link?.let { hostOf(it.baseUrl) }, rejected = rejected, saveFailed = failed)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        AccountUi(host = null, rejected = false),
    )

    /** Validates, stores the token encrypted, and schedules uploads. Returns what to show. */
    fun connect(url: String, token: String): LinkInput {
        val input = LinkInput.parse(url, token)
        if (input !is LinkInput.Valid) return input
        saveFailed.value = try {
            container.links.save(input.link)
            container.uploadScheduler.schedule()
            false
        } catch (e: GeneralSecurityException) {
            // Keystore unusable: nothing is stored in the clear; tell the user.
            logFailure(TAG, "account_link_save_failed", e)
            true
        }
        return input
    }

    fun disconnect() {
        container.links.clear()
        container.uploadScheduler.cancel()
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L

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
