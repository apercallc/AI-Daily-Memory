package com.apercallc.aidailymemory.recording.recall

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalRecallTest {
    // A Wednesday in mid-month, so every window is unambiguous.
    private val today = LocalDate.of(2026, 10, 14)

    private fun item(
        id: String,
        text: String,
        kind: String = "idea",
        daysAgo: Long = 0,
        owner: String? = null,
        counterpart: String? = null,
        direction: String? = null,
        due: String? = null,
    ) = RecallItem(id, today.minusDays(daysAgo), kind, text, owner, counterpart, due, direction)

    private val items = listOf(
        item("d-today", "Decided to migrate the database", "decision", 0),
        item("i-garden", "A totally unrelated idea about gardening", "idea", 0),
        item("d-yday", "Chose Postgres for the new service", "decision", 1),
        item("i-cache", "Cache the daily report instead of rebuilding it", "idea", 1),
        item("a-launch", "Draft the launch checklist", "action", 2, owner = "Marcus"),
        item("a-old", "Draft an old onboarding doc", "action", 10, owner = "Marcus"),
        item("c-sam", "Send Sam the pricing deck", "commitment", 1, counterpart = "Sam", direction = "i_owe"),
        item("q-watch", "Do we need the watch app at launch", "question", 4),
        item("r-best", "Send the quarterly budget report to finance", "idea", 1),
        item("r-decoy", "File the monthly report", "idea", 1),
        item("d-lastweek", "Chose a venue for the offsite", "decision", 8),
        item("d-lastmonth", "Chose the vendor for payroll", "decision", 20),
    )

    private fun ids(question: String) = LocalRecall.answer(question, items, today).citedIds

    @Test
    fun `date windows follow the calendar`() {
        assertEquals(today, LocalRecall.resolveRange("what happened today", today)?.start)
        assertEquals(today, LocalRecall.resolveRange("what did I do tonight", today)?.end)
        assertEquals(today.minusDays(1), LocalRecall.resolveRange("yesterday?", today)?.start)
        val lastWeek = LocalRecall.resolveRange("last week", today)!!
        assertEquals(LocalDate.of(2026, 10, 5), lastWeek.start)
        assertEquals(LocalDate.of(2026, 10, 11), lastWeek.end)
        val thisWeek = LocalRecall.resolveRange("this week", today)!!
        assertEquals(LocalDate.of(2026, 10, 12), thisWeek.start)
        assertEquals(today, thisWeek.end)
        val lastMonth = LocalRecall.resolveRange("last month", today)!!
        assertEquals(LocalDate.of(2026, 9, 1), lastMonth.start)
        assertEquals(LocalDate.of(2026, 9, 30), lastMonth.end)
        val thisMonth = LocalRecall.resolveRange("this month", today)!!
        assertEquals(LocalDate.of(2026, 10, 1), thisMonth.start)
        assertEquals("the last 3 days", LocalRecall.resolveRange("in the last 3 days", today)?.label)
        assertEquals(today.minusDays(364), LocalRecall.resolveRange("past 999 days", today)?.start)
        assertEquals(today, LocalRecall.resolveRange("last 0 days", today)?.start)
        assertNull(LocalRecall.resolveRange("tell me about gardening", today))
    }

    @Test
    fun `kind hints`() {
        assertEquals("decision", LocalRecall.kindHint("What did I decide?"))
        assertEquals("commitment", LocalRecall.kindHint("What did I promise Sam?"))
        assertEquals("action", LocalRecall.kindHint("my todo list"))
        assertEquals("idea", LocalRecall.kindHint("any ideas"))
        assertEquals("question", LocalRecall.kindHint("open questions"))
        assertNull(LocalRecall.kindHint("gardening"))
    }

    @Test
    fun `search terms drop filler, repeats and are bounded`() {
        assertEquals(listOf("quarterly", "budget", "report"), LocalRecall.searchTerms("What about the quarterly budget report?"))
        assertEquals(listOf("milk"), LocalRecall.searchTerms("milk milk MILK"))
        assertEquals(LocalRecall.MAX_TERMS, LocalRecall.searchTerms((1..50).joinToString(" ") { "word$it" }).size)
        assertTrue(LocalRecall.searchTerms("what is the").isEmpty())
    }

    @Test
    fun `stems let tenses meet`() {
        assertEquals(LocalRecall.stem("decide"), LocalRecall.stem("decided"))
        assertEquals(LocalRecall.stem("caching"), LocalRecall.stem("cache"))
        assertEquals("go", LocalRecall.stem("go"))
        assertEquals("is", LocalRecall.stem("is"))
    }

    @Test
    fun `a date and kind question needs no keywords`() {
        assertEquals(listOf("d-today"), ids("What did I decide today?"))
        assertEquals(listOf("d-yday"), ids("What did I decide yesterday?"))
    }

    @Test
    fun `keyword question finds the item and ranks the closer match first`() {
        assertEquals(listOf("i-cache"), ids("What about caching the daily report?").take(1))
        val ranked = ids("What about the quarterly budget report?")
        assertEquals("r-best", ranked.first())
        assertTrue("r-decoy" in ranked)
    }

    @Test
    fun `owner counterpart and direction`() {
        assertEquals(listOf("a-launch"), ids("What tasks does Marcus have in the last 3 days?"))
        val answer = LocalRecall.answer("What did I promise Sam?", items, today)
        assertEquals(listOf("c-sam"), answer.citedIds)
        assertTrue("(you owe Sam)" in answer.text)
    }

    @Test
    fun `last week and last month windows exclude other weeks`() {
        assertEquals(listOf("d-lastweek"), ids("What did I decide last week?"))
        assertEquals(listOf("d-lastmonth"), ids("What did I decide last month?"))
    }

    @Test
    fun `unrelated questions abstain and say so`() {
        val answer = LocalRecall.answer("Tell me about quantum computing", items, today)
        assertTrue(answer.abstained)
        assertEquals(LocalRecall.NOTHING_FOUND, answer.text)
        assertTrue(answer.citedIds.isEmpty())
    }

    @Test
    fun `a window with nothing in it abstains and names the window`() {
        val answer = LocalRecall.answer("What did I decide last week?", emptyList(), today)
        assertTrue(answer.abstained)
        assertEquals("last week", answer.rangeLabel)
    }

    @Test
    fun `the answer cites only what it read back and names the window`() {
        val answer = LocalRecall.answer("What did I decide yesterday?", items, today)
        assertFalse(answer.abstained)
        assertEquals("yesterday", answer.rangeLabel)
        assertTrue(answer.text.startsWith("Here is what your memory has for yesterday:"))
        assertTrue("- 2026-10-13 (decision): Chose Postgres for the new service" in answer.text)
    }

    @Test
    fun `answers show at most eight items and candidates are capped`() {
        val many = (1..30).map { item("n$it", "Note number $it about launch", "idea", it.toLong() % 5) }
        val answer = LocalRecall.answer("anything about launch", many, today)
        assertEquals(LocalRecall.MAX_SHOWN, answer.citedIds.size)
        assertEquals(LocalRecall.MAX_CANDIDATES, LocalRecall.retrieve("anything about launch", many, today).first.size)
    }

    @Test
    fun `describe covers owner, due date and a commitment owed to the person`() {
        val owned = LocalRecall.build(listOf(item("x", "Ship it", "action", owner = "Priya", due = "2026-10-20")), null)
        assertTrue("Ship it (owner: Priya) due 2026-10-20" in owned.text)
        val mine = LocalRecall.build(listOf(item("y", "Do it", "action", owner = "me")), null)
        assertFalse("owner" in mine.text)
        val owed = LocalRecall.build(
            listOf(item("z", "Send notes", "commitment", counterpart = "Lee", direction = "they_owe")),
            null,
        )
        assertTrue("(owed to you by Lee)" in owed.text)
    }
}
