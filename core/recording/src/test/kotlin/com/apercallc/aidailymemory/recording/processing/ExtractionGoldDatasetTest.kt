package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Structural self-check for [ExtractionGoldDataset] — catches a malformed fixture (bad evidence
 * index, duplicate id) before it would ever reach [ExtractionEvaluator]. This is not the human
 * review itself; it only proves the dataset is internally consistent.
 */
class ExtractionGoldDatasetTest {

    @Test
    fun `every case has a unique id`() {
        val ids = ExtractionGoldDataset.cases.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `every expected item's evidence is in bounds for its own transcript`() {
        for (case in ExtractionGoldDataset.cases) {
            for (expected in case.expected) {
                assertTrue(
                    expected.evidenceSegmentIndices.all { it in case.transcript.indices },
                    "case '${case.id}' has out-of-bounds evidence for its own transcript",
                )
            }
        }
    }

    @Test
    fun `every expected item passes the same validation production extraction does`() {
        for (case in ExtractionGoldDataset.cases) {
            ProcessingValidation.validateExtraction(
                ExtractionResult(case.expected),
                segmentCount = case.transcript.size,
            )
        }
    }

    @Test
    fun `transcripts are numbered contiguously from zero`() {
        for (case in ExtractionGoldDataset.cases) {
            assertEquals(case.transcript.indices.toList(), case.transcript.map { it.index })
        }
    }

    @Test
    fun `no case is marked reviewed yet`() {
        // This is a tripwire, not a requirement: it exists so flipping a case's `reviewed` flag
        // to true (the real human-review signoff) is a deliberate, visible diff — not something
        // that could happen by accident alongside an unrelated change. Once a human has reviewed
        // a case, update this test to assert the specific reviewed ids instead of "none".
        assertTrue(ExtractionGoldDataset.cases.none { it.reviewed })
    }

    @Test
    fun `negative cases expect nothing to be extracted`() {
        val negatives = ExtractionGoldDataset.cases.filter { it.id.startsWith("negative-") }
        assertTrue(negatives.isNotEmpty())
        for (case in negatives) {
            assertEquals(emptyList(), case.expected, "case '${case.id}' should expect nothing")
        }
    }

    @Test
    fun `dataset covers every memory item kind at least once`() {
        val coveredKinds = ExtractionGoldDataset.cases.flatMap { it.expected }.map { it.kind }.toSet()
        assertEquals(setOf("decision", "action", "commitment", "idea", "question"), coveredKinds)
    }

    @Test
    fun `a perfectly predicting provider would score 1 precision and recall on the positive cases`() {
        val positives = ExtractionGoldDataset.cases.filter { it.expected.isNotEmpty() }
        val scores = positives.map { ExtractionEvaluator.score(predicted = it.expected, gold = it.expected) }
        val aggregate = ExtractionEvaluator.aggregate(scores).overall

        assertEquals(1.0, aggregate.precision)
        assertEquals(1.0, aggregate.recall)
    }

    @Test
    fun `notes is null unless a case needs a caveat`() {
        for (case in ExtractionGoldDataset.cases) {
            if (case.notes != null) assertTrue(case.notes.isNotBlank())
        }
        assertNull(ExtractionGoldDataset.cases.first { it.id == "decision-ship-auth-before-billing" }.notes)
    }
}
