package com.apercallc.aidailymemory.ui.sessions

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.capture.SessionDataDeletionResult
import com.apercallc.aidailymemory.data.TranscriptText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.schedule.MemoryDays
import com.apercallc.aidailymemory.schedule.RenameResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Recent sessions grouped by memory day, with renaming. Two weeks keeps the list light. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel(
    private val container: AppContainer,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val _today = MutableStateFlow(memoryToday())

    /**
     * Today as a *memory* day (ADR-0012), so a session at 00:30 before bedtime is still
     * "Today", not "Yesterday".
     */
    val today: StateFlow<LocalDate> = _today.asStateFlow()

    val days: StateFlow<List<DayGroup>?> = _today
        .flatMapLatest { day ->
            container.database.sessions().observeSince(day.minusDays(DAYS_SHOWN).toString())
        }
        .combine(container.controller.state) { rows, state ->
            val open = state.sessionId?.value?.takeIf {
                state.status != RecordingStatus.COMPLETED && state.status != RecordingStatus.IDLE
            }
            SessionGroups.group(rows, open)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _messages = Channel<Int>(Channel.BUFFERED)
    val messages: Flow<Int> = _messages.receiveAsFlow()

    /** Call when the screen becomes visible: the day may have turned while it was hidden. */
    fun refreshToday() {
        _today.value = memoryToday()
    }

    fun rename(sessionId: String, input: String) {
        viewModelScope.launch {
            val message = try {
                messageFor(container.sessions.rename(sessionId, input))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A full or locked database must not crash the app over a title.
                logFailure(TAG, "session_rename_failed", e)
                R.string.rename_failed
            }
            _messages.send(message)
        }
    }

    /** Deletes only local evidence; uploaded audio requires remote deletion first. */
    fun delete(sessionId: String) {
        viewModelScope.launch {
            val message = try {
                when (withContext(Dispatchers.IO) { container.sessionDataDeletion.delete(sessionId) }) {
                    SessionDataDeletionResult.Deleted -> R.string.session_delete_done
                    SessionDataDeletionResult.SessionActive -> R.string.session_delete_active
                    SessionDataDeletionResult.RemoteCopyExists -> R.string.session_delete_remote_copy
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "session_delete_failed", e)
                R.string.session_delete_failed
            }
            _messages.send(message)
        }
    }

    /** A session's delivered transcript, oldest line first, opened off the main thread. */
    fun transcript(sessionId: String): Flow<List<TranscriptText>> =
        container.database.transcripts().observeSession(sessionId)
            .map { lines ->
                lines.map { container.transcriptCipher.open(it) ?: container.transcriptCipher.unreadable(it) }
            }
            .catch { e ->
                // A database error must not end the collector (and the screen) silently.
                logFailure(TAG, "transcript_load_failed", e)
                emit(emptyList())
            }
            .flowOn(Dispatchers.IO)

    private fun memoryToday(): LocalDate =
        MemoryDays.dayOf(now(), zone(), container.settings.rhythm.value)

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
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SessionsViewModel(container) as T
    }

    private companion object {
        const val TAG = "SessionsViewModel"
        const val DAYS_SHOWN = 13L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
