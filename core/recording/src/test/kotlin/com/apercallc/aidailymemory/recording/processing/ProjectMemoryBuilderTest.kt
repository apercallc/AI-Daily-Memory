package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private data class Row(val id: String, val subject: String?, val text: String, val mentionedMs: Long)

class ProjectMemoryBuilderTest {
    private fun toItem(row: Row) = ProjectMemoryItem(row.id, row.text, "commitment", null, row.mentionedMs)

    @Test
    fun `no items produces no groups`() {
        val groups = ProjectMemoryBuilder.build(emptyList<Row>(), { it.subject }, ::toItem)

        assertTrue(groups.isEmpty())
    }

    @Test
    fun `items for the same subject are grouped together`() {
        val rows = listOf(
            Row("1", "Alex", "Send the deck", 100L),
            Row("2", "Alex", "Follow up on budget", 200L),
        )

        val groups = ProjectMemoryBuilder.build(rows, { it.subject }, ::toItem)

        assertEquals(1, groups.size)
        assertEquals("Alex", groups.single().subject)
        assertEquals(2, groups.single().items.size)
    }

    @Test
    fun `different subjects produce separate groups`() {
        val rows = listOf(Row("1", "Alex", "a", 100L), Row("2", "Sam", "b", 200L))

        val groups = ProjectMemoryBuilder.build(rows, { it.subject }, ::toItem)

        assertEquals(setOf("Alex", "Sam"), groups.map { it.subject }.toSet())
    }

    @Test
    fun `items with no subject are grouped under a null subject rather than dropped`() {
        val rows = listOf(Row("1", null, "a", 100L))

        val groups = ProjectMemoryBuilder.build(rows, { it.subject }, ::toItem)

        assertEquals(null, groups.single().subject)
    }

    @Test
    fun `a group's items are sorted newest mention first`() {
        val rows = listOf(
            Row("1", "Alex", "older", 100L),
            Row("2", "Alex", "newer", 300L),
            Row("3", "Alex", "middle", 200L),
        )

        val groups = ProjectMemoryBuilder.build(rows, { it.subject }, ::toItem)

        assertEquals(listOf("newer", "middle", "older"), groups.single().items.map { it.text })
    }

    @Test
    fun `groups are sorted by their most recent mention, newest first`() {
        val rows = listOf(
            Row("1", "StaleContact", "old promise", 10L),
            Row("2", "RecentContact", "fresh promise", 500L),
        )

        val groups = ProjectMemoryBuilder.build(rows, { it.subject }, ::toItem)

        assertEquals(listOf("RecentContact", "StaleContact"), groups.map { it.subject })
    }

    @Test
    fun `a group's lastMentionedEpochMs is its newest item's time`() {
        val rows = listOf(Row("1", "Alex", "old", 100L), Row("2", "Alex", "new", 900L))

        val groups = ProjectMemoryBuilder.build(rows, { it.subject }, ::toItem)

        assertEquals(900L, groups.single().lastMentionedEpochMs)
    }
}
