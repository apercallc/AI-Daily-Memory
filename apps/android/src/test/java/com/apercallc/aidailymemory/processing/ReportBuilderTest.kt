package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.MemoryItemEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReportBuilderTest {
    private fun item(kind: String, text: String, id: String = "$kind-$text") = MemoryItemEntity(
        id = id,
        sessionId = "s1",
        memoryDay = "2026-10-12",
        kind = kind,
        text = text,
        confidence = "likely",
        quote = null,
        owner = "Sam",
        due = "2026-10-20",
        direction = null,
        counterpart = null,
        extractionModel = "user-key:m",
        extractionVersion = 1,
        evidenceEpochMs = 1_500L,
        dismissedAtEpochMs = null,
        extractedAtEpochMs = 9L,
    )

    private fun line(start: Long, end: Long) = LocalTranscriptLineEntity(
        lineId = "l$start",
        sessionId = "s1",
        segmentId = "seg",
        startEpochMs = start,
        endEpochMs = end,
        speaker = null,
        text = "hello",
        confidence = null,
        provider = "p",
        model = "m",
    )

    private fun build(items: List<MemoryItemEntity>, lines: List<LocalTranscriptLineEntity> = emptyList()) =
        ReportBuilder.build("2026-10-12", items, lines, 42L, "user-key:m")

    @Test
    fun `no items gives the neutral headline and an empty summary`() {
        val report = build(emptyList())

        assertEquals("Transcript only", report.headline)
        assertEquals("", report.summary)
        assertTrue(report.items.isEmpty())
    }

    @Test
    fun `headline counts kinds with correct plurals`() {
        val report = build(
            listOf(
                item("decision", "a"),
                item("decision", "b"),
                item("action", "c"),
                item("commitment", "d"),
                item("idea", "e"),
                item("question", "f"),
            ),
        )

        assertEquals("2 decisions, 1 action item, 1 commitment, 1 idea", report.headline)
    }

    @Test
    fun `a day with only questions keeps the neutral headline`() {
        assertEquals("Memory Day", build(listOf(item("question", "why?"))).headline)
    }

    @Test
    fun `summary groups items by kind in a fixed order`() {
        val report = build(listOf(item("idea", "i1"), item("decision", "d1"), item("decision", "d2"), item("action", "a1")))

        assertEquals("Decided: d1; d2. To do: a1. Ideas: i1", report.summary)
    }

    @Test
    fun `items keep their fields and coverage sums transcript time`() {
        val report = build(listOf(item("action", "send")), listOf(line(1_000, 3_000), line(4_000, 4_500)))

        val reported = report.items.single()
        assertEquals("Sam", reported.owner)
        assertEquals("2026-10-20", reported.due)
        assertEquals(1_500L, reported.evidenceEpochMs)
        assertEquals(2_500L, report.coverage.transcribedMs)
        assertEquals(1, report.coverage.sessions)
        assertEquals(42L, report.generatedAtEpochMs)
    }

    @Test
    fun `coverage counts every distinct session across items and transcript lines`() {
        val report = ReportBuilder.build(
            "2026-10-12",
            listOf(item("action", "send", id = "a1").copy(sessionId = "s1")),
            listOf(
                line(0, 1_000).copy(sessionId = "s1"),
                line(1_000, 2_000).copy(sessionId = "s2"),
            ),
            42L,
            "user-key:m",
        )

        assertEquals(2, report.coverage.sessions)
    }

    @Test
    fun `serialize and deserialize round trip`() {
        val report = build(listOf(item("decision", "go")), listOf(line(0, 1_000)))

        assertEquals(report, ReportBuilder.deserialize(ReportBuilder.serialize(report)))
    }

    @Test
    fun `more than 8 items of one kind are counted, not all spelled out`() {
        val items = (1..12).map { item("decision", "d$it", id = "d$it") }

        val report = build(items)

        assertTrue(report.summary.startsWith("Decided: d1; d2; d3; d4; d5; d6; d7; d8 (+4 more)"))
        assertEquals(12, report.items.size)
    }

    @Test
    fun `a very long summary is capped with an ellipsis, not left unbounded`() {
        val longText = "decision text padded to be unusually long so that even just the first eight of " +
            "them, before any per-kind truncation marker is even added, already exceed the whole-summary cap "
        val items = (1..50).map { item("decision", "$longText$it", id = "d$it") }

        val report = build(items)

        assertTrue(report.summary.length <= 1_001)
        assertTrue(report.summary.endsWith("…"))
    }

    @Test
    fun `a report defaults to complete status`() {
        assertEquals(
            com.apercallc.aidailymemory.recording.processing.DailyReportStatus.COMPLETE,
            build(listOf(item("decision", "go"))).status,
        )
    }

    @Test
    fun `a report built with isComplete false is marked partial`() {
        val report = ReportBuilder.build(
            "2026-10-12", listOf(item("decision", "go")), emptyList(), 42L, "user-key:m", isComplete = false,
        )

        assertEquals(com.apercallc.aidailymemory.recording.processing.DailyReportStatus.PARTIAL, report.status)
    }
}
