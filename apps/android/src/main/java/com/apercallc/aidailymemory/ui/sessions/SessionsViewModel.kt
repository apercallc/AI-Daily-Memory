package com.apercallc.aidailymemory.ui.sessions

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.schedule.RenameResult
import java.time.LocalDate
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Recent sessions grouped by memory day, with renaming. Two weeks keeps the list light. */
class SessionsViewModel(private val container: AppContainer) : ViewModel() {
    val days: StateFlow<List<DayGroup>?> = combine(
        container.database.sessions().observeSince(LocalDate.now().minusDays(DAYS_SHOWN).toString()),
        container.controller.state,
    ) { rows, state ->
        val open = state.sessionId?.value?.takeIf {
            state.status != RecordingStatus.COMPLETED && state.status != RecordingStatus.IDLE
        }
        SessionGroups.group(rows, open)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages: Flow<Int> = _messages.receiveAsFlow()

    fun rename(sessionId: String, input: String) {
        viewModelScope.launch { _messages.send(messageFor(container.sessions.rename(sessionId, input))) }
    }

    @StringRes
    private fun messageFor(result: RenameResult): Int = when (result) {
        RenameResult.SAVED -> R.string.rename_saved
        RenameResult.RESET -> R.string.rename_reset_done
        RenameResult.TOO_LONG -> R.string.rename_too_long
        RenameResult.INVALID_CHARACTERS -> R.string.rename_invalid
        RenameResult.UNKNOWN_SESSION -> R.string.rename_unknown
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SessionsViewModel(container) as T
    }

    private companion object {
        const val DAYS_SHOWN = 13L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
