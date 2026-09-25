package com.apercallc.aidailymemory.recording.schedule

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Whether the schedule wants recording at a given moment. Sleep outranks quiet. */
sealed interface OffState {
    data object Available : OffState
    data class Quiet(val windowId: String, val label: String) : OffState
    data object Sleeping : OffState
}

/** What the recorder should do when the schedule changes state. */
enum class RhythmAction {
    /** Pause for a quiet window, or for bedtime when the day isn't ended at bedtime. */
    PAUSE,

    /** Resume after a schedule pause. The reducer ignores it after a manual pause. */
    RESUME,

    /** Bedtime: stop the session and close the day. */
    END_DAY,

    /** Wake time after the day was ended: suggest starting (never start by itself). */
    WAKE_REMINDER,
}

data class RhythmTransition(val at: Instant, val from: OffState, val to: OffState) {
    /** The action for this change under [rhythm], or null when nothing needs doing. */
    fun actionFor(rhythm: DailyRhythm): RhythmAction? = when {
        to == OffState.Sleeping ->
            if (rhythm.endDayAtBedtime) RhythmAction.END_DAY else pauseUnlessAlreadyOff()
        from == OffState.Sleeping -> when {
            rhythm.endDayAtBedtime ->
                RhythmAction.WAKE_REMINDER.takeIf { rhythm.morningReminder }
            to == OffState.Available -> RhythmAction.RESUME
            // Waking straight into a quiet window: stay paused until it ends.
            else -> null
        }
        to is OffState.Quiet -> pauseUnlessAlreadyOff()
        // Quiet (or sleep without ending the day) → available.
        else -> RhythmAction.RESUME
    }

    private fun pauseUnlessAlreadyOff(): RhythmAction? =
        RhythmAction.PAUSE.takeIf { from == OffState.Available }
}

/**
 * Pure schedule arithmetic over wall-clock windows (ADR-0012). All functions take the zone
 * explicitly, so time-zone changes and DST are just inputs.
 */
object RhythmPlanner {
    /** Far enough to find the next weekly occurrence of any window. */
    private const val SEARCH_DAYS = 8L

    fun stateAt(rhythm: DailyRhythm, at: Instant, zone: ZoneId): OffState {
        val date = at.atZone(zone).toLocalDate()
        // A window lasts under 24 h, so only occurrences starting yesterday or today can
        // contain `at`.
        val candidates = listOf(date.minusDays(1), date)
        val sleeping = rhythm.sleep.any { window ->
            candidates.any { day -> window.occurrenceStartingOn(day, zone)?.contains(at) == true }
        }
        if (sleeping) return OffState.Sleeping
        val quiet = rhythm.quiet.filter { it.enabled }.firstOrNull { quiet ->
            candidates.any { day -> quiet.window.occurrenceStartingOn(day, zone)?.contains(at) == true }
        }
        return quiet?.let { OffState.Quiet(it.id, it.label) } ?: OffState.Available
    }

    /** Every state change in (fromExclusive, toInclusive], in time order. */
    fun transitionsBetween(
        rhythm: DailyRhythm,
        fromExclusive: Instant,
        toInclusive: Instant,
        zone: ZoneId,
    ): List<RhythmTransition> {
        if (!toInclusive.isAfter(fromExclusive)) return emptyList()
        val boundaries = boundaryCandidates(
            rhythm = rhythm,
            firstDate = fromExclusive.atZone(zone).toLocalDate().minusDays(1),
            lastDate = toInclusive.atZone(zone).toLocalDate(),
            zone = zone,
        ).filter { it.isAfter(fromExclusive) && !it.isAfter(toInclusive) }

        val transitions = mutableListOf<RhythmTransition>()
        var previous = stateAt(rhythm, fromExclusive, zone)
        for (boundary in boundaries) {
            val current = stateAt(rhythm, boundary, zone)
            if (current != previous) transitions += RhythmTransition(boundary, previous, current)
            previous = current
        }
        return transitions
    }

    /** The next state change strictly after [after], or null if the rhythm has no windows. */
    fun nextTransition(rhythm: DailyRhythm, after: Instant, zone: ZoneId): RhythmTransition? =
        transitionsBetween(rhythm, after, after.plus(Duration.ofDays(SEARCH_DAYS)), zone)
            .firstOrNull()

    /** The next transition that needs an action, with that action. */
    fun nextAction(
        rhythm: DailyRhythm,
        after: Instant,
        zone: ZoneId,
    ): Pair<RhythmTransition, RhythmAction>? =
        transitionsBetween(rhythm, after, after.plus(Duration.ofDays(SEARCH_DAYS)), zone)
            .firstNotNullOfOrNull { t -> t.actionFor(rhythm)?.let { t to it } }

    /**
     * The single action to apply after being away (asleep CPU, dead process) from
     * [fromExclusive] to [toInclusive]. Only the *last* actionable change matters: a quiet
     * window that began and ended while nobody was looking needs no pause now.
     */
    fun catchUpAction(
        rhythm: DailyRhythm,
        fromExclusive: Instant,
        toInclusive: Instant,
        zone: ZoneId,
    ): RhythmAction? = transitionsBetween(rhythm, fromExclusive, toInclusive, zone)
        .mapNotNull { it.actionFor(rhythm) }
        .lastOrNull()

    private fun boundaryCandidates(
        rhythm: DailyRhythm,
        firstDate: LocalDate,
        lastDate: LocalDate,
        zone: ZoneId,
    ): List<Instant> {
        val windows = rhythm.sleep + rhythm.quiet.filter { it.enabled }.map { it.window }
        val result = sortedSetOf<Instant>()
        var date = firstDate
        while (!date.isAfter(lastDate)) {
            for (window in windows) {
                window.occurrenceStartingOn(date, zone)?.let {
                    result += it.start
                    result += it.end
                }
            }
            date = date.plusDays(1)
        }
        return result.toList()
    }
}
