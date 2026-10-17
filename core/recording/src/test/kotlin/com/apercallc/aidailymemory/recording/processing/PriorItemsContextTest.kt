package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PriorItemsContextTest {

    private fun action(text: String, owner: String? = null, due: String? = null) = ExtractedItem(
        kind = "action",
        text = text,
        confidence = "confirmed",
        evidenceSegmentIndices = listOf(0),
        owner = owner,
        due = due,
    )

    private fun commitment(text: String, direction: String, counterpart: String) = ExtractedItem(
        kind = "commitment",
        text = text,
        confidence = "confirmed",
        evidenceSegmentIndices = listOf(0),
        direction = direction,
        counterpart = counterpart,
    )

    private fun decision(text: String) = ExtractedItem(
        kind = "decision",
        text = text,
        confidence = "confirmed",
        evidenceSegmentIndices = listOf(0),
    )

    @Test
    fun `empty input returns null`() {
        assertNull(PriorItemsContext.describe(emptyList()))
    }

    @Test
    fun `decisions ideas and questions are never open items`() {
        assertNull(PriorItemsContext.describe(listOf(decision("ship it"))))
    }

    @Test
    fun `an open action is described with owner and due date`() {
        val described = PriorItemsContext.describe(listOf(action("Draft scope doc", owner = "Marcus", due = "Friday")))

        assertTrue(described != null)
        assertTrue(described.contains("Draft scope doc"))
        assertTrue(described.contains("Marcus"))
        assertTrue(described.contains("Friday"))
    }

    @Test
    fun `an open commitment is described with direction and counterpart`() {
        val described = PriorItemsContext.describe(listOf(commitment("Send the PR", "i_owe", "Marcus")))

        assertTrue(described != null)
        assertTrue(described.contains("i_owe"))
        assertTrue(described.contains("Marcus"))
    }

    @Test
    fun `an action without an owner still renders without crashing`() {
        val described = PriorItemsContext.describe(listOf(action("Unassigned task")))
        assertTrue(described!!.contains("Unassigned task"))
    }

    @Test
    fun `output never exceeds the requested bound`() {
        val many = (1..200).map { action("Task number $it with some extra padding text") }

        val described = PriorItemsContext.describe(many, maxChars = 300)

        assertTrue(described!!.length <= 300)
    }

    @Test
    fun `a bound too small to fit even one line returns null rather than a bare header`() {
        val described = PriorItemsContext.describe(listOf(action("x".repeat(500))), maxChars = 10)
        assertNull(described)
    }

    @Test
    fun `mixed open and non-open items keeps only the open ones`() {
        val described = PriorItemsContext.describe(
            listOf(decision("some decision"), action("Open task", owner = "Tommy")),
        )

        assertTrue(described!!.contains("Open task"))
        assertTrue(!described.contains("some decision"))
    }

    @Test
    fun `default bound is large enough for a handful of ordinary items`() {
        val described = PriorItemsContext.describe(
            listOf(
                action("Draft scope doc", owner = "Marcus", due = "Friday"),
                commitment("Send the PR", "i_owe", "Marcus"),
            ),
        )

        assertEquals(2, described!!.lines().size - 1) // header + one line per item
    }
}
