package com.apercallc.aidailymemory.ui.ask

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.data.MemoryCorrectionDao
import com.apercallc.aidailymemory.data.MemoryItemDao
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.recall.LocalRecall
import com.apercallc.aidailymemory.recording.recall.RecallAnswer
import com.apercallc.aidailymemory.recording.recall.RecallItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** One question, one answer (ADR-0030): there is no thread, so a new question replaces the old answer. */
sealed interface AskState {
    data object Idle : AskState
    data object Working : AskState
    data class Answered(val question: String, val answer: RecallAnswer) : AskState
}

class AskViewModel(
    private val memoryItems: MemoryItemDao,
    private val corrections: MemoryCorrectionDao,
    private val today: () -> LocalDate = LocalDate::now,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow<AskState>(AskState.Idle)
    val state: StateFlow<AskState> = _state.asStateFlow()

    fun ask(rawQuestion: String) {
        val question = rawQuestion.trim().take(MAX_QUESTION_CHARS)
        if (question.isEmpty() || _state.value == AskState.Working) return
        _state.value = AskState.Working
        viewModelScope.launch {
            val answer = withContext(io) { LocalRecall.answer(question, load(), today()) }
            _state.value = AskState.Answered(question, answer)
        }
    }

    fun clear() {
        if (_state.value != AskState.Working) _state.value = AskState.Idle
    }

    /** The person's undismissed items with their latest correction applied to the wording. */
    private suspend fun load(): List<RecallItem> {
        val items = memoryItems.allOpen()
        val corrected = items.chunked(CORRECTION_BATCH)
            .flatMap { corrections.forItems(it.map { item -> item.id }) }
            .associate { it.itemId to it.correctedText }
        return items.mapNotNull { item ->
            val day = try {
                LocalDate.parse(item.memoryDay)
            } catch (e: DateTimeParseException) {
                logFailure(TAG, "ask_unreadable_memory_day", e, warning = true)
                return@mapNotNull null
            }
            RecallItem(
                id = item.id,
                day = day,
                kind = item.kind,
                text = corrected[item.id] ?: item.text,
                owner = item.owner,
                counterpart = item.counterpart,
                due = item.due,
                direction = item.direction,
            )
        }
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AskViewModel(container.database.memoryItems(), container.database.memoryCorrections()) as T
    }

    companion object {
        const val MAX_QUESTION_CHARS = 300
        /** Stays under SQLite's 999 bound-variable limit. */
        private const val CORRECTION_BATCH = 500
        private const val TAG = "AskViewModel"
    }
}
