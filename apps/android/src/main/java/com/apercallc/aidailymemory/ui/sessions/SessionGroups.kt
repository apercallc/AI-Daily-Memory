package com.apercallc.aidailymemory.ui.sessions

import com.apercallc.aidailymemory.data.SessionRow
import com.apercallc.aidailymemory.recording.session.SessionTitle
import com.apercallc.aidailymemory.recording.session.SessionTitles
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

data class SessionItem(
    val sessionId: String,
    val title: SessionTitle,
    /** In the zone the session was recorded in, so times read as the user lived them. */
    val start: ZonedDateTime,
    /** Null while the session is still open. */
    val end: ZonedDateTime?,
    val capturedMs: Long,
    val customTitle: String?,
)

data class DayGroup(val day: LocalDate, val sessions: List<SessionItem>) {
    val capturedMs: Long get() = sessions.sumOf { it.capturedMs }
}

object SessionGroups {
    /**
     * Groups rows by memory day, newest day first, sessions in time order within a day (the
     * order their automatic titles are numbered in).
     *
     * @param openSessionId the session still recording or paused, shown without an end time.
     */
    fun group(rows: List<SessionRow>, openSessionId: String?): List<DayGroup> =
        rows.groupBy { it.memoryDay }
            .map { (day, dayRows) ->
                val ordered = dayRows.sortedBy { it.startedAtEpochMs }
                val starts = ordered.map { it.start().toLocalTime() }
                val custom = ordered.withIndex()
                    .mapNotNull { (i, row) -> row.customTitle?.let { i to it } }
                    .toMap()
                val titles = SessionTitles.titlesFor(starts, custom)
                DayGroup(
                    day = LocalDate.parse(day),
                    sessions = ordered.mapIndexed { i, row ->
                        SessionItem(
                            sessionId = row.sessionId,
                            title = titles[i],
                            start = row.start(),
                            end = row.lastActivityEpochMs
                                ?.takeIf { row.sessionId != openSessionId }
                                ?.let { Instant.ofEpochMilli(it).atZone(row.zone()) },
                            capturedMs = row.capturedMs,
                            customTitle = row.customTitle,
                        )
                    },
                )
            }
            .sortedByDescending { it.day }

    private fun SessionRow.start(): ZonedDateTime = Instant.ofEpochMilli(startedAtEpochMs).atZone(zone())

    /** A zone id this device doesn't know (older tz database) falls back to the device zone. */
    private fun SessionRow.zone(): ZoneId = try {
        ZoneId.of(zoneId)
    } catch (_: DateTimeException) {
        ZoneId.systemDefault()
    }
}
