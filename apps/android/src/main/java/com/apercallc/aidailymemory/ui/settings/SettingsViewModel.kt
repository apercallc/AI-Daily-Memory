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
import com.apercallc.aidailymemory.net.AccountLinker
import com.apercallc.aidailymemory.net.ConnectError
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private val linker = AccountLinker(
        links = container.links,
        api = container.accountApi,
        onLinked = container.uploadScheduler::schedule,
        onUnlinked = container.uploadScheduler::cancel,
    )
    private val connecting = MutableStateFlow(false)
    private val connectError = MutableStateFlow<ConnectError?>(null)

    val account: StateFlow<AccountUi> = combine(
        container.links.link,
        container.links.problem,
        connecting,
        connectError,
        container.database.clips().observeProblems(),
    ) { link, problem, busy, error, problems ->
        AccountUi(
            host = link?.let { hostOf(it.baseUrl) },
            problem = problem,
            connecting = busy,
            error = error,
            uploadProblems = problems,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AccountUi())

    /** Verifies the token with the server, then stores it sealed. Off the main thread. */
    fun connect(url: String, token: String, includeBacklog: Boolean) {
        if (connecting.value) return
        connecting.value = true
        connectError.value = null
        viewModelScope.launch {
            connectError.value = withContext(Dispatchers.IO) { linker.connect(url, token, includeBacklog) }
            connecting.value = false
        }
    }

    fun clearConnectError() {
        connectError.value = null
    }

    /** Outlives the screen: the server-side revoke must not be cancelled by leaving it. */
    fun disconnect() {
        container.appScope.launch(Dispatchers.IO) { linker.disconnect() }
    }

    /** Failed clips start over; unreadable segments are read again. */
    fun retryUploads() {
        container.appScope.launch(Dispatchers.IO) {
            container.database.clips().retryProblems()
            container.uploadScheduler.schedule()
        }
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
