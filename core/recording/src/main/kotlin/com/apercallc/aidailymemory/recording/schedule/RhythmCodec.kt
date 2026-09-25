package com.apercallc.aidailymemory.recording.schedule

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeParseException

/**
 * Versioned text form of [DailyRhythm] for local settings storage. Dependency-free and
 * human-readable, so a support dump or a failing test shows the schedule plainly:
 *
 * ```
 * v1
 * end_day=true
 * reminder=true
 * sleep=23:00-07:00@SUN,MON,TUE,WED,THU
 * quiet=<id>|<url-encoded label>|12:00-13:00@MON,TUE|1
 * ```
 */
object RhythmCodec {
    private const val VERSION = "v1"

    fun encode(rhythm: DailyRhythm): String = buildList {
        add(VERSION)
        add("end_day=${rhythm.endDayAtBedtime}")
        add("reminder=${rhythm.morningReminder}")
        rhythm.sleep.forEach { add("sleep=${window(it)}") }
        rhythm.quiet.forEach { q ->
            add("quiet=${enc(q.id)}|${enc(q.label)}|${window(q.window)}|${if (q.enabled) 1 else 0}")
        }
    }.joinToString("\n")

    /**
     * Throws [IllegalArgumentException] for anything it can't read exactly. The caller decides
     * what to do (the settings store surfaces it and falls back to no schedule). A schedule is
     * never half-applied.
     */
    fun decode(text: String): DailyRhythm {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.firstOrNull() == VERSION) { "unsupported rhythm format" }
        var endDay = true
        var reminder = true
        val sleep = mutableListOf<DailyWindow>()
        val quiet = mutableListOf<QuietWindow>()
        for (line in lines.drop(1)) {
            val key = line.substringBefore('=', missingDelimiterValue = "")
            val value = line.substringAfter('=', missingDelimiterValue = "")
            require(key.isNotEmpty()) { "malformed rhythm line" }
            when (key) {
                "end_day" -> endDay = bool(value)
                "reminder" -> reminder = bool(value)
                "sleep" -> sleep += parseWindow(value)
                "quiet" -> {
                    val parts = value.split('|')
                    require(parts.size == 4) { "malformed quiet window" }
                    quiet += QuietWindow(
                        id = dec(parts[0]),
                        label = dec(parts[1]),
                        window = parseWindow(parts[2]),
                        enabled = when (parts[3]) {
                            "1" -> true
                            "0" -> false
                            else -> throw IllegalArgumentException("malformed quiet flag")
                        },
                    )
                }
                else -> throw IllegalArgumentException("unknown rhythm key")
            }
        }
        return DailyRhythm(sleep, quiet, endDay, reminder)
    }

    private fun window(w: DailyWindow): String {
        val days = DayOfWeek.entries.filter { it in w.days }.joinToString(",") { it.name.take(3) }
        return "${w.start}-${w.end}@$days"
    }

    private fun parseWindow(value: String): DailyWindow {
        val times = value.substringBefore('@')
        val days = value.substringAfter('@', missingDelimiterValue = "")
        val (start, end) = times.split('-').also { require(it.size == 2) { "malformed window" } }
        return DailyWindow(time(start), time(end), days.split(',').map(::day).toSet())
    }

    private fun time(value: String): LocalTime = try {
        LocalTime.parse(value)
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("malformed time", e)
    }

    private fun day(value: String): DayOfWeek =
        DayOfWeek.entries.firstOrNull { it.name.take(3) == value }
            ?: throw IllegalArgumentException("malformed day")

    private fun bool(value: String): Boolean = when (value) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("malformed flag")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)

    private fun dec(value: String): String = URLDecoder.decode(value, Charsets.UTF_8)
}
