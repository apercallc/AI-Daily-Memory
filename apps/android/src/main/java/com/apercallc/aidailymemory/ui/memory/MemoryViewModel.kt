package com.apercallc.aidailymemory.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.data.MemoryItemEntity
import com.apercallc.aidailymemory.recording.schedule.MemoryDays
import com.apercallc.aidailymemory.recording.session.PartOfDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** ViewModel for the daily memory timeline. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemoryViewModel(
    private val container: AppContainer,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {

    private val timeFmt = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val dateFmt = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)

    val days: StateFlow<List<MemoryDay>?> = container.database.sessions()
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

                    val title = row.customTitle ?: run {
                        val time = Instant.ofEpochMilli(row.startedAtEpochMs)
                            .atZone(ZoneId.of(row.zoneId)).toLocalTime()
                        PartOfDay.of(time).name.lowercase().replaceFirstChar { it.uppercase() }
                    }

                    MemorySession(
                        sessionId = row.sessionId,
                        title = title,
                        startTime = formatTime(row.startedAtEpochMs, row.zoneId),
                        durationText = formatDuration(row.capturedMs),
                        summary = buildSummary(sessionItems),
                        items = sessionItems.map { it.toDisplayItem() },
                        transcriptLines = sessionLines,
                        audioDeleted = procState?.audioDeleted == true,
                        freedBytes = 0,
                    )
                }

                val grouped = rows.zip(sessions) { row, session -> row.memoryDay to session }
                    .groupBy({ it.first }, { it.second })

                val memoryDays = grouped.map { (day, daySessions) ->
                    MemoryDay(
                        dayLabel = formatDayLabel(day),
                        dateLabel = day,
                        sessions = daySessions,
                    )
                }
                emit(memoryDays)
            }.flowOn(Dispatchers.IO)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private fun memoryToday(): LocalDate =
        MemoryDays.dayOf(now(), zone(), container.settings.rhythm.value)

    private fun formatTime(epochMs: Long, zoneId: String): String = try {
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(zoneId)).toLocalTime().format(timeFmt)
    } catch (_: Exception) {
        ""
    }

    private fun formatDuration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "Under 1 min"
            minutes < 60 -> "$minutes min"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    private fun buildSummary(items: List<MemoryItemEntity>): String {
        if (items.isEmpty()) return ""
        return items.joinToString(". ") { it.text }
    }

    private fun formatDayLabel(day: String): String = try {
        val date = LocalDate.parse(day)
        val today = memoryToday()
        when (date) {
            today -> "Today"
            today.minusDays(1) -> "Yesterday"
            else -> date.format(dateFmt)
        }
    } catch (_: Exception) {
        day
    }

    companion object {
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