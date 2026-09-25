package com.apercallc.aidailymemory.recording.session

import java.time.LocalTime

/** Coarse part of the day a session started in; UI layers localize it. */
enum class PartOfDay {
    EARLY_MORNING,
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT,
    ;

    companion object {
        fun of(time: LocalTime): PartOfDay = when (time.hour) {
            in 4..6 -> EARLY_MORNING
            in 7..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..20 -> EVENING
            else -> NIGHT
        }
    }
}

/**
 * Title shown for a session. A user title always wins; otherwise the title comes from when the
 * session started, numbered only when a day has several in the same part ("Afternoon 2").
 */
sealed interface SessionTitle {
    data class Custom(val text: String) : SessionTitle
    data class Default(val part: PartOfDay, val number: Int?) : SessionTitle
}

/** Result of validating a rename. */
sealed interface TitleInput {
    data class Valid(val title: String) : TitleInput

    /** Blank input: go back to the automatic title. */
    data object Clear : TitleInput

    data object TooLong : TitleInput

    /** Control characters (newlines, tabs, bidi overrides) would break layouts and exports. */
    data object InvalidCharacters : TitleInput
}

object SessionTitles {
    const val MAX_LENGTH = 60

    /**
     * Titles for one day's sessions, in the order given (usually start time).
     *
     * @param starts local start time of each session.
     * @param custom user titles by index, if any.
     */
    fun titlesFor(starts: List<LocalTime>, custom: Map<Int, String> = emptyMap()): List<SessionTitle> {
        val parts = starts.map(PartOfDay::of)
        val totals = parts.groupingBy { it }.eachCount()
        val seen = mutableMapOf<PartOfDay, Int>()
        return parts.mapIndexed { index, part ->
            val number = seen.merge(part, 1, Int::plus)!!
            custom[index]?.let { SessionTitle.Custom(it) }
                ?: SessionTitle.Default(part, number.takeIf { totals.getValue(part) > 1 })
        }
    }

    fun parse(input: String): TitleInput {
        if (input.any { it.isISOControl() || it in BIDI_CONTROLS }) return TitleInput.InvalidCharacters
        val collapsed = input.trim().replace(WHITESPACE, " ")
        return when {
            collapsed.isEmpty() -> TitleInput.Clear
            collapsed.codePointCount(0, collapsed.length) > MAX_LENGTH -> TitleInput.TooLong
            else -> TitleInput.Valid(collapsed)
        }
    }

    // `\s` alone misses Unicode spaces such as the no-break space pasted from chat apps.
    private val WHITESPACE = Regex("[\\s\\p{Z}]+")

    /** Unicode bidirectional overrides and isolates: can visually disguise text. */
    private val BIDI_CONTROLS = setOf(
        '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
        '\u2066', '\u2067', '\u2068', '\u2069',
    )
}
