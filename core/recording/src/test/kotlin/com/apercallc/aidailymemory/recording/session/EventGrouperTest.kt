package com.apercallc.aidailymemory.recording.session

import kotlin.test.Test
import kotlin.test.assertEquals

class EventGrouperTest {
    private fun span(id: String, startMs: Long, durationMs: Long) = SessionSpan(id, startMs, durationMs)

    @Test
    fun `no sessions gives no events`() {
        assertEquals(emptyList(), EventGrouper.group(emptyList()))
    }

    @Test
    fun `a single short session is its own event`() {
        val groups = EventGrouper.group(listOf(span("a", 0, 30_000)))

        assertEquals(listOf(EventGroup(listOf("a"), 0, 30_000)), groups)
    }

    @Test
    fun `two short sessions close together join one event`() {
        val groups = EventGrouper.group(
            listOf(span("a", 0, 30_000), span("b", 60_000, 20_000)),
        )

        assertEquals(listOf(EventGroup(listOf("a", "b"), 0, 80_000)), groups)
    }

    @Test
    fun `two short sessions far apart stay separate events`() {
        val groups = EventGrouper.group(
            listOf(span("a", 0, 30_000), span("b", 700_000, 20_000)),
        )

        assertEquals(
            listOf(EventGroup(listOf("a"), 0, 30_000), EventGroup(listOf("b"), 700_000, 720_000)),
            groups,
        )
    }

    @Test
    fun `a gap exactly at the threshold still joins`() {
        val groups = EventGrouper.group(
            listOf(span("a", 0, 30_000), span("b", 30_000 + 600_000, 20_000)),
            groupingGapMs = 600_000,
        )

        assertEquals(1, groups.size)
        assertEquals(listOf("a", "b"), groups.single().sessionIds)
    }

    @Test
    fun `a long session never joins a neighboring short session`() {
        val groups = EventGrouper.group(
            listOf(span("meeting", 0, 600_000), span("note", 600_500, 20_000)),
        )

        assertEquals(2, groups.size)
        assertEquals(listOf("meeting"), groups[0].sessionIds)
        assertEquals(listOf("note"), groups[1].sessionIds)
    }

    @Test
    fun `a short session never joins a preceding long session even when adjacent`() {
        val groups = EventGrouper.group(
            listOf(span("note", -20_000, 20_000), span("meeting", 0, 600_000)),
        )

        assertEquals(2, groups.size)
    }

    @Test
    fun `two long sessions back to back each stand alone`() {
        val groups = EventGrouper.group(
            listOf(span("m1", 0, 600_000), span("m2", 600_000, 700_000)),
        )

        assertEquals(listOf(listOf("m1"), listOf("m2")), groups.map { it.sessionIds })
    }

    @Test
    fun `three short sessions cluster where the gap allows and split where it does not`() {
        val groups = EventGrouper.group(
            listOf(
                span("a", 0, 30_000),
                span("b", 60_000, 30_000),
                span("c", 2_000_000, 30_000),
            ),
        )

        assertEquals(listOf(listOf("a", "b"), listOf("c")), groups.map { it.sessionIds })
    }

    @Test
    fun `input order does not matter, only start time`() {
        val forward = EventGrouper.group(listOf(span("a", 0, 30_000), span("b", 60_000, 20_000)))
        val reversed = EventGrouper.group(listOf(span("b", 60_000, 20_000), span("a", 0, 30_000)))

        assertEquals(forward, reversed)
    }

    @Test
    fun `a custom long-session threshold changes what counts as a meeting`() {
        val groups = EventGrouper.group(
            listOf(span("a", 0, 120_000), span("b", 130_000, 20_000)),
            longSessionThresholdMs = 60_000,
        )

        assertEquals(2, groups.size)
    }

    @Test
    fun `force-join overrides the automatic gap rule`() {
        val groups = EventGrouper.group(
            listOf(span("a", 0, 30_000), span("b", 900_000, 20_000)),
            overrides = mapOf("b" to SessionBoundaryOverride.FORCE_JOIN),
        )

        assertEquals(listOf(listOf("a", "b")), groups.map { it.sessionIds })
    }

    @Test
    fun `force-join overrides even a neighboring long session`() {
        val groups = EventGrouper.group(
            listOf(span("meeting", 0, 600_000), span("note", 600_500, 20_000)),
            overrides = mapOf("note" to SessionBoundaryOverride.FORCE_JOIN),
        )

        assertEquals(listOf(listOf("meeting", "note")), groups.map { it.sessionIds })
    }

    @Test
    fun `force-split overrides the automatic clustering of two close short sessions`() {
        val groups = EventGrouper.group(
            listOf(span("a", 0, 30_000), span("b", 60_000, 20_000)),
            overrides = mapOf("b" to SessionBoundaryOverride.FORCE_SPLIT),
        )

        assertEquals(listOf(listOf("a"), listOf("b")), groups.map { it.sessionIds })
    }

    @Test
    fun `an override on the day's first session has no predecessor to act on`() {
        val groups = EventGrouper.group(
            listOf(span("a", 0, 30_000), span("b", 60_000, 20_000)),
            overrides = mapOf("a" to SessionBoundaryOverride.FORCE_SPLIT),
        )

        assertEquals(listOf(listOf("a", "b")), groups.map { it.sessionIds })
    }
}
