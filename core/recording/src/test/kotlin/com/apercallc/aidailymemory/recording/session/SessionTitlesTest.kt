package com.apercallc.aidailymemory.recording.session

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionTitlesTest {
    private fun t(hour: Int, minute: Int = 0) = LocalTime.of(hour, minute)

    @Test
    fun `parts of day cover every hour with clear boundaries`() {
        assertEquals(PartOfDay.NIGHT, PartOfDay.of(t(3, 59)))
        assertEquals(PartOfDay.EARLY_MORNING, PartOfDay.of(t(4)))
        assertEquals(PartOfDay.MORNING, PartOfDay.of(t(7)))
        assertEquals(PartOfDay.MORNING, PartOfDay.of(t(11, 59)))
        assertEquals(PartOfDay.AFTERNOON, PartOfDay.of(t(12)))
        assertEquals(PartOfDay.EVENING, PartOfDay.of(t(17)))
        assertEquals(PartOfDay.NIGHT, PartOfDay.of(t(21)))
        assertEquals(PartOfDay.NIGHT, PartOfDay.of(t(0)))
    }

    @Test
    fun `defaults are numbered only when a part of the day repeats, and custom titles win`() {
        val titles = SessionTitles.titlesFor(
            starts = listOf(t(8), t(13), t(15), t(16, 30), t(19)),
            custom = mapOf(3 to "Design review"),
        )

        assertEquals(
            listOf(
                SessionTitle.Default(PartOfDay.MORNING, null),
                SessionTitle.Default(PartOfDay.AFTERNOON, 1),
                SessionTitle.Default(PartOfDay.AFTERNOON, 2),
                SessionTitle.Custom("Design review"),
                SessionTitle.Default(PartOfDay.EVENING, null),
            ),
            titles,
        )
        assertEquals(emptyList(), SessionTitles.titlesFor(emptyList()))
    }

    @Test
    fun `rename input is trimmed, collapsed, and validated`() {
        assertEquals(TitleInput.Valid("Standup with Sam"), SessionTitles.parse("  Standup   with\u00a0Sam "))
        assertEquals(TitleInput.Clear, SessionTitles.parse("   "))
        assertEquals(TitleInput.Clear, SessionTitles.parse(""))
        assertEquals(TitleInput.Valid("x".repeat(60)), SessionTitles.parse("x".repeat(60)))
        assertEquals(TitleInput.TooLong, SessionTitles.parse("x".repeat(61)))
        // Emoji count as one character each, not two UTF-16 units.
        assertEquals(TitleInput.Valid("🎙".repeat(60)), SessionTitles.parse("🎙".repeat(60)))
        assertEquals(TitleInput.InvalidCharacters, SessionTitles.parse("line\nbreak"))
        assertEquals(TitleInput.InvalidCharacters, SessionTitles.parse("evil\u202Etxt.exe"))
    }
}
