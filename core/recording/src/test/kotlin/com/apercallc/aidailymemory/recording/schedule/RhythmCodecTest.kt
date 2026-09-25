package com.apercallc.aidailymemory.recording.schedule

import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RhythmCodecTest {
    private val rhythm = DailyRhythm(
        sleep = listOf(
            DailyWindow(LocalTime.of(23, 0), LocalTime.of(7, 0), DailyWindow.WEEKNIGHTS),
            DailyWindow(LocalTime.of(0, 30), LocalTime.of(9, 15), setOf(DayOfWeek.SATURDAY)),
        ),
        quiet = listOf(
            QuietWindow(
                id = "q-1",
                label = "Lunch | café & gym = 100% ☕",
                window = DailyWindow(
                    LocalTime.of(12, 0),
                    LocalTime.of(13, 30),
                    setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY),
                ),
                enabled = false,
            ),
        ),
        endDayAtBedtime = false,
        morningReminder = false,
    )

    @Test
    fun `round-trips every field, including awkward labels`() {
        assertEquals(rhythm, RhythmCodec.decode(RhythmCodec.encode(rhythm)))
        assertEquals(DailyRhythm.None, RhythmCodec.decode(RhythmCodec.encode(DailyRhythm.None)))
    }

    @Test
    fun `format is stable and readable`() {
        val text = RhythmCodec.encode(DailyRhythm(sleep = listOf(rhythm.sleep.first())))

        assertEquals(
            "v1\nend_day=true\nreminder=true\nsleep=23:00-07:00@MON,TUE,WED,THU,SUN",
            text,
        )
    }

    @Test
    fun `tolerates blank lines and surrounding whitespace`() {
        assertEquals(DailyRhythm.None, RhythmCodec.decode("\n  v1  \n\nend_day=true\n"))
    }

    @Test
    fun `anything it cannot read exactly is rejected, never half-applied`() {
        val bad = listOf(
            "",
            "v2",
            "v1\nnope",
            "v1\nend_day=maybe",
            "v1\ncolor=blue",
            "v1\nsleep=23:00@MON",
            "v1\nsleep=25:00-07:00@MON",
            "v1\nsleep=23:00-07:00@XYZ",
            "v1\nsleep=23:00-07:00@",
            "v1\nquiet=a|b|12:00-13:00@MON",
            "v1\nquiet=a|b|12:00-13:00@MON|2",
            "v1\nquiet=%20|b|12:00-13:00@MON|1",
            // Same start day twice violates the DailyRhythm invariant.
            "v1\nsleep=23:00-07:00@MON\nsleep=22:00-06:00@MON",
        )
        bad.forEach { text ->
            assertFailsWith<IllegalArgumentException>(text) { RhythmCodec.decode(text) }
        }
    }
}
