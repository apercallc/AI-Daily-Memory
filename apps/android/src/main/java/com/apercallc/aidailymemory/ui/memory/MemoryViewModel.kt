package com.apercallc.aidailymemory.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.data.MemoryItemEntity
import com.apercallc.aidailymemory.recording.schedule.MemoryDays
import com.apercallc.aidailymemory.recording.session.PartOfDay
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.session.SessionTitle
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

sealed interface MemoryLoadState {
    data object Loading : MemoryLoadState
    data class Content(val days: List<MemoryDay>) : MemoryLoadState
    data object Failed : MemoryLoadState
}

/** ViewModel for the daily memory timeline. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModel(
    private val container: AppContainer,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {

    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

    private val reloadRequests = MutableStateFlow(0)

    val state: StateFlow<MemoryLoadState> = reloadRequests
        .flatMapLatest {
            container.database.sessions()
                .observeSince(memoryToday().minusDays(DAYS_SHOWN).toString())
                .flatMapLatest { rows ->
                    flow {
                        if (rows.isEmpty()) {
                            emit(emptyList())
                            return@flow
                        }
                        val itemsDao = container.database.memoryItems()
                        val transcriptDao = container.database.localTranscripts()
                        val processingDao = container.database.processingState()

                        val sessions = rows.map { row ->
                            val sessionItems = itemsDao.forSession(row.sessionId)
                            val sessionLines = transcriptDao.forSession(row.sessionId)
                            val procState = processingDao.forSession(row.sessionId)

                            val title = row.customTitle?.let { SessionTitle.Custom(it) } ?: run {
                                val time = Instant.ofEpochMilli(row.startedAtEpochMs)
                                    .atZone(zoneOf(row.zoneId)).toLocalTime()
                                SessionTitle.Default(PartOfDay.of(time), null)
                            }

                            MemorySession(
                                sessionId = row.sessionId,
                                title = title,
                                startTime = formatTime(row.startedAtEpochMs, row.zoneId),
                                capturedMs = row.capturedMs,
                                summary = buildSummary(sessionItems),
                                items = sessionItems.map { it.toDisplayItem() },
                                transcriptLines = sessionLines,
                                audioDeleted = procState?.audioDeleted == true,
                                freedBytes = 0,
                            )
                        }

                        val grouped = rows.zip(sessions) { row, session -> row.memoryDay to session }
                            .groupBy({ it.first }, { it.second })

                        emit(grouped.map { (day, daySessions) ->
                            MemoryDay(
                                dayLabel = formatDayLabel(day),
                                dateLabel = day,
                                sessions = daySessions,
                            )
                        })
                    }.flowOn(Dispatchers.IO)
                }
                .map<List<MemoryDay>, MemoryLoadState> { MemoryLoadState.Content(it) }
                .onStart { emit(MemoryLoadState.Loading) }
                .catch { emit(MemoryLoadState.Failed) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MemoryLoadState.Loading)

    fun retry() {
        reloadRequests.update { it + 1 }
    }

    private fun memoryToday(): LocalDate =
        MemoryDays.dayOf(now(), zone(), container.settings.rhythm.value)

    private fun formatTime(epochMs: Long, zoneId: String): String =
        Instant.ofEpochMilli(epochMs).atZone(zoneOf(zoneId)).toLocalTime().format(timeFmt)

    /** A zone id this device doesn't know (older tz database) is reported, then replaced. */
    private fun zoneOf(zoneId: String): ZoneId = try {
        ZoneId.of(zoneId)
    } catch (e: DateTimeException) {
        logFailure(TAG, "memory_zone_unknown", e, warning = true)
        zone()
    }

    private fun buildSummary(items: List<MemoryItemEntity>): String {
        if (items.isEmpty()) return ""
        return items.joinToString(". ") { it.text }
    }

    private fun formatDayLabel(day: String): DayLabel {
        val date = try {
            LocalDate.parse(day)
        } catch (e: DateTimeParseException) {
            logFailure(TAG, "memory_day_unparseable", e, warning = true)
            return DayLabel.Date(day)
        }
        val today = memoryToday()
        return when (date) {
            today -> DayLabel.Today
            today.minusDays(1) -> DayLabel.Yesterday
            else -> DayLabel.Date(date.format(dateFmt))
        }
    }

    companion object {
        private const val TAG = "MemoryViewModel"
        private const val DAYS_SHOWN = 14L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun MemoryItemEntity.toDisplayItem() = MemoryDisplayItem(
    kind = kind,
    text = text,
    confidence = confidence,
    quote = quote,
    owner = owner,
    due = due,
    sessionId = sessionId,
)