package com.apercallc.aidailymemory.ui.memory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.data.DailyReportEntity
import com.apercallc.aidailymemory.data.MemoryItemCorrectionEntity
import com.apercallc.aidailymemory.data.MemoryItemEntity
import com.apercallc.aidailymemory.data.SessionEventOverrideEntity
import com.apercallc.aidailymemory.data.SessionEventOverrideKind
import com.apercallc.aidailymemory.data.SessionRow
import com.apercallc.aidailymemory.recording.schedule.MemoryDays
import com.apercallc.aidailymemory.recording.session.EventGrouper
import com.apercallc.aidailymemory.recording.session.PartOfDay
import com.apercallc.aidailymemory.recording.session.SessionBoundaryOverride
import com.apercallc.aidailymemory.recording.session.SessionSpan
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
                        val correctionsDao = container.database.memoryCorrections()
                        val transcriptDao = container.database.localTranscripts()
                        val processingDao = container.database.processingState()
                        val overrides = container.database.sessionEventOverrides().all()
                            .associateBy { it.sessionId }

                        val sessions = rows.map { row ->
                            val sessionItems = itemsDao.forSession(row.sessionId)
                            val corrections = correctionsDao.forItems(sessionItems.map { it.id })
                                .associateBy { it.itemId }
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
                                summary = buildSummary(sessionItems, corrections),
                                items = sessionItems.map { it.toDisplayItem(corrections[it.id]) },
                                transcriptLines = sessionLines,
                                audioDeleted = procState?.audioDeleted == true,
                                freedBytes = 0,
                                // Filled in per day below, once every session in the day is known.
                                joinedWithOlderNeighbor = false,
                            )
                        }

                        val grouped = rows.zip(sessions) { row, session -> row.memoryDay to (row to session) }
                            .groupBy({ it.first }, { it.second })

                        emit(grouped.map { (day, dayRowsAndSessions) ->
                            val boundaries = eventBoundaries(dayRowsAndSessions.map { it.first }, overrides)
                            MemoryDay(
                                dayLabel = formatDayLabel(day),
                                dateLabel = day,
                                sessions = dayRowsAndSessions.map { (row, session) ->
                                    session.copy(joinedWithOlderNeighbor = boundaries[row.sessionId] == true)
                                },
                            )
                        })
                    }.flowOn(Dispatchers.IO)
                }
                .map<List<MemoryDay>, MemoryLoadState> { MemoryLoadState.Content(it) }
                .onStart { emit(MemoryLoadState.Loading) }
                .catch {
                    logFailure(TAG, "memory_load_failed", it)
                    emit(MemoryLoadState.Failed)
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MemoryLoadState.Loading)

    fun retry() {
        reloadRequests.update { it + 1 }
    }

    /**
     * One day's generated report, for the report screen (TODO.md "Add an in-app report
     * screen and share reports as Markdown"). Null while no report has been generated yet for
     * that memory day (e.g. before extraction finishes, or when summaries aren't configured).
     */
    fun report(memoryDay: String): Flow<DailyReportEntity?> =
        container.database.dailyReports().observeForDay(memoryDay)

    /** Hides an item from the timeline without rewriting the extracted row (dismiss, ADR-0019). */
    fun dismiss(itemId: String) = viewModelScope.launch {
        container.database.memoryItems().dismiss(itemId, now().toEpochMilli())
        reloadRequests.update { it + 1 }
    }

    /**
     * Supersedes an item's displayed text with a correction, stored apart from the extracted
     * row it corrects (AGENTS.md: derived AI output is correctable; evidence/extraction is
     * never rewritten in place).
     */
    fun correct(itemId: String, correctedText: String) = viewModelScope.launch {
        container.database.memoryCorrections().insert(
            MemoryItemCorrectionEntity(
                id = container.ids.next().toString(),
                itemId = itemId,
                correctedText = correctedText,
                correctedAtEpochMs = now().toEpochMilli(),
            ),
        )
        reloadRequests.update { it + 1 }
    }

    /**
     * Toggles one session's grouping with its immediate predecessor (TODO.md "Event grouping":
     * support event-boundary corrections). Joined -> forced apart; apart -> forced together.
     * Has no effect when called for the day's chronologically first session, which has no
     * predecessor to correct against.
     */
    fun toggleEventBoundary(sessionId: String, currentlyJoined: Boolean) = viewModelScope.launch {
        val kind = if (currentlyJoined) {
            SessionEventOverrideKind.FORCE_SPLIT
        } else {
            SessionEventOverrideKind.FORCE_JOIN
        }
        container.database.sessionEventOverrides().upsert(
            SessionEventOverrideEntity(sessionId, kind, now().toEpochMilli()),
        )
        reloadRequests.update { it + 1 }
    }

    /** Clears a session's boundary correction, returning it to the automatic grouping. */
    fun clearEventBoundaryOverride(sessionId: String) = viewModelScope.launch {
        container.database.sessionEventOverrides().clear(sessionId)
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

    private fun buildSummary(items: List<MemoryItemEntity>, corrections: Map<String, MemoryItemCorrectionEntity>): String {
        if (items.isEmpty()) return ""
        return items.joinToString(". ") { corrections[it.id]?.correctedText ?: it.text }
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

    /**
     * For one day's sessions: which are grouped with the session immediately before them in
     * time (TODO.md "Event grouping"). A day's chronologically first session is never a key
     * here — it has no predecessor to be joined with.
     */
    private fun eventBoundaries(
        dayRows: List<SessionRow>,
        overrides: Map<String, SessionEventOverrideEntity>,
    ): Map<String, Boolean> {
        if (dayRows.size <= 1) return emptyMap()
        val spans = dayRows.map { SessionSpan(it.sessionId, it.startedAtEpochMs, it.capturedMs) }
        val boundaryOverrides = overrides.mapNotNull { (sessionId, entity) ->
            when (entity.override) {
                SessionEventOverrideKind.FORCE_JOIN -> sessionId to SessionBoundaryOverride.FORCE_JOIN
                SessionEventOverrideKind.FORCE_SPLIT -> sessionId to SessionBoundaryOverride.FORCE_SPLIT
                else -> null
            }
        }.toMap()
        val groups = EventGrouper.group(spans, boundaryOverrides)
        val groupIndex = groups.flatMapIndexed { index, group -> group.sessionIds.map { it to index } }.toMap()
        val chronological = dayRows.sortedBy { it.startedAtEpochMs }
        return chronological.zipWithNext().associate { (previous, current) ->
            current.sessionId to (groupIndex[current.sessionId] == groupIndex[previous.sessionId])
        }
    }

    companion object {
        private const val TAG = "MemoryViewModel"
        private const val DAYS_SHOWN = 14L
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun MemoryItemEntity.toDisplayItem(correction: MemoryItemCorrectionEntity?) = MemoryDisplayItem(
    id = id,
    kind = kind,
    text = correction?.correctedText ?: text,
    confidence = confidence,
    quote = quote,
    owner = owner,
    due = due,
    sessionId = sessionId,
    isCorrected = correction != null,
)