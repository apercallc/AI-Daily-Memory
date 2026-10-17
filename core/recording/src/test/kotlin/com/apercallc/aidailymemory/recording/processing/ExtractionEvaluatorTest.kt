package com.apercallc.aidailymemory.recording.processing

import kotlin.test.Test
import kotlin.test.assertEquals

class ExtractionEvaluatorTest {

    private fun item(
        kind: String = "decision",
        evidence: List<Int> = listOf(0),
    ) = ExtractedItem(kind = kind, text = "x", confidence = "confirmed", evidenceSegmentIndices = evidence)

    @Test
    fun `perfect match scores 1 precision and recall`() {
        val gold = listOf(item(evidence = listOf(0)), item(evidence = listOf(1)))
        val predicted = listOf(item(evidence = listOf(0)), item(evidence = listOf(1)))

        val score = ExtractionEvaluator.score(predicted, gold).overall

        assertEquals(1.0, score.precision)
        assertEquals(1.0, score.recall)
        assertEquals(1.0, score.f1)
    }

    @Test
    fun `an unmatched prediction is a false positive`() {
        val gold = listOf(item(evidence = listOf(0)))
        val predicted = listOf(item(evidence = listOf(0)), item(evidence = listOf(9)))

        val score = ExtractionEvaluator.score(predicted, gold).overall

        assertEquals(1, score.truePositives)
        assertEquals(1, score.falsePositives)
        assertEquals(0, score.falseNegatives)
        assertEquals(0.5, score.precision)
        assertEquals(1.0, score.recall)
    }

    @Test
    fun `an unmatched gold item is a false negative`() {
        val gold = listOf(item(evidence = listOf(0)), item(evidence = listOf(1)))
        val predicted = listOf(item(evidence = listOf(0)))

        val score = ExtractionEvaluator.score(predicted, gold).overall

        assertEquals(1, score.truePositives)
        assertEquals(0, score.falsePositives)
        assertEquals(1, score.falseNegatives)
        assertEquals(1.0, score.precision)
        assertEquals(0.5, score.recall)
    }

    @Test
    fun `a different kind never matches even with the same evidence`() {
        val gold = listOf(item(kind = "commitment", evidence = listOf(0)))
        val predicted = listOf(item(kind = "action", evidence = listOf(0)))

        val score = ExtractionEvaluator.score(predicted, gold).overall

        assertEquals(0, score.truePositives)
        assertEquals(1, score.falsePositives)
        assertEquals(1, score.falseNegatives)
    }

    @Test
    fun `nothing predicted and nothing expected is vacuously perfect`() {
        val score = ExtractionEvaluator.score(emptyList(), emptyList()).overall

        assertEquals(1.0, score.precision)
        assertEquals(1.0, score.recall)
        assertEquals(1.0, score.f1)
    }

    @Test
    fun `duplicate predictions cannot claim the same gold item twice`() {
        val gold = listOf(item(evidence = listOf(0)))
        val predicted = listOf(item(evidence = listOf(0)), item(evidence = listOf(0)))

        val score = ExtractionEvaluator.score(predicted, gold).overall

        assertEquals(1, score.truePositives)
        assertEquals(1, score.falsePositives)
        assertEquals(0, score.falseNegatives)
    }

    @Test
    fun `overlapping evidence sets still match on partial overlap`() {
        val gold = listOf(item(evidence = listOf(0, 1, 2)))
        val predicted = listOf(item(evidence = listOf(2, 3)))

        val score = ExtractionEvaluator.score(predicted, gold).overall

        assertEquals(1, score.truePositives)
    }

    @Test
    fun `byKind separates kinds that both appear`() {
        val gold = listOf(item(kind = "decision", evidence = listOf(0)), item(kind = "action", evidence = listOf(1)))
        val predicted = listOf(item(kind = "decision", evidence = listOf(0)))

        val byKind = ExtractionEvaluator.score(predicted, gold).byKind

        assertEquals(1, byKind.getValue("decision").truePositives)
        assertEquals(1, byKind.getValue("action").falseNegatives)
    }

    @Test
    fun `aggregate sums kind scores across sessions`() {
        val sessionA = ExtractionEvaluator.score(listOf(item(evidence = listOf(0))), listOf(item(evidence = listOf(0))))
        val sessionB = ExtractionEvaluator.score(emptyList(), listOf(item(evidence = listOf(0))))

        val aggregate = ExtractionEvaluator.aggregate(listOf(sessionA, sessionB)).overall

        assertEquals(1, aggregate.truePositives)
        assertEquals(0, aggregate.falsePositives)
        assertEquals(1, aggregate.falseNegatives)
    }

    @Test
    fun `f1 is zero when precision and recall are both zero`() {
        val gold = listOf(item(kind = "idea", evidence = listOf(0)))
        val predicted = listOf(item(kind = "decision", evidence = listOf(0)))

        // Force a kind with zero true positives, zero precision and recall both achievable:
        // predicted-only "decision" kind has falsePositives>0 so precision 0 is not reachable
        // from this pair alone without a predicted item whose kind has no gold at all.
        val score = ExtractionEvaluator.score(predicted, gold).byKind.getValue("decision")

        assertEquals(0.0, score.precision)
        assertEquals(1.0, score.recall) // no gold items of kind "decision": vacuous recall
    }
}
