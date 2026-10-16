package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportMarkdownTest {
    private fun report(
        items: List<ReportItem> = emptyList(),
        headline: String = "2 decisions",
        summary: String = "Decided: ship Friday",
        sessions: Int = 2,
    ) = DailyReport(
        memoryDay = "2026-10-15",
        zoneId = "UTC",
        headline = headline,
        summary = summary,
        items = items,
        coverage = ReportCoverage(recordedMs = 3_600_000, speechMs = 1_800_000, transcribedMs = 1_800_000, sessions = sessions, gaps = emptyList()),
        generatedAtEpochMs = 0L,
        extractionModel = "user-key:claude",
        extractionVersion = 1,
    )

    private fun item(
        kind: String,
        text: String,
        quote: String? = null,
        owner: String? = null,
        due: String? = null,
        direction: String? = null,
        counterpart: String? = null,
    ) = ReportItem(kind, text, "likely", quote, owner, due, direction, counterpart, "s1", null)

    @Test
    fun `renders the day, headline, summary and coverage as a markdown document`() {
        val md = renderMarkdown(report())

        assertEquals(
            "# Memory for 2026-10-15\n\n**2 decisions**\n\nDecided: ship Friday\n\nRecorded 60 min across 2 sessions.\n",
            md,
        )
    }

    @Test
    fun `groups items under a heading per kind, in a fixed order`() {
        val md = renderMarkdown(
            report(
                items = listOf(
                    item("idea", "Try standing desks"),
                    item("decision", "Ship Friday"),
                    item("action", "Email the client", owner = "Sam", due = "2026-10-20"),
                ),
            ),
        )

        assertTrue(md.contains("## Decisions\n- Ship Friday\n"))
        assertTrue(md.contains("## Action items\n- Email the client (Sam, due 2026-10-20)\n"))
        assertTrue(md.contains("## Ideas\n- Try standing desks\n"))
        assertTrue(md.indexOf("## Decisions") < md.indexOf("## Action items"))
        assertTrue(md.indexOf("## Action items") < md.indexOf("## Ideas"))
    }

    @Test
    fun `an item's evidence quote is included as a blockquote`() {
        val md = renderMarkdown(report(items = listOf(item("decision", "Ship Friday", quote = "let's ship Friday"))))

        assertTrue(md.contains("- Ship Friday\n  > let's ship Friday\n"))
    }

    @Test
    fun `an action item with only a due date omits the owner parenthesis comma`() {
        val md = renderMarkdown(report(items = listOf(item("action", "File taxes", due = "2026-10-20"))))

        assertTrue(md.contains("- File taxes (due 2026-10-20)\n"))
    }

    @Test
    fun `a commitment the person owes names who it is owed to`() {
        val md = renderMarkdown(
            report(items = listOf(item("commitment", "Send the deck", direction = "i_owe", counterpart = "Sam"))),
        )

        assertTrue(md.contains("- Send the deck (I owe Sam)\n"))
    }

    @Test
    fun `a commitment owed to the person names who owes it`() {
        val md = renderMarkdown(
            report(items = listOf(item("commitment", "Send the deck", direction = "owed_to_me", counterpart = "Sam"))),
        )

        assertTrue(md.contains("- Send the deck (Sam owes this)\n"))
    }

    @Test
    fun `coverage is rendered in whole minutes`() {
        val md = renderMarkdown(report())

        assertTrue(md.contains("Recorded 60 min across 2 sessions."))
    }

    @Test
    fun `a single session uses the singular word`() {
        val md = renderMarkdown(report(sessions = 1))

        assertTrue(md.contains("Recorded 60 min across 1 session."))
    }

    @Test
    fun `a day with no items renders without an items section`() {
        val md = renderMarkdown(report(items = emptyList()))

        assertTrue(!md.contains("##"))
    }
}
