package com.apercallc.aidailymemory.recording.processing

/**
 * Scores extraction output against a gold-standard set of expected items (TODO.md Phase 2
 * "AI evaluation": measure precision/recall for decisions, actions, commitments, and ideas).
 *
 * Matching is structural, not exact-text: a predicted item matches a gold item when they name
 * the same [ExtractedItem.kind] and share at least one evidence line, mirroring how
 * [MemoryReconciler] already treats evidence as an item's real identity rather than its wording.
 * Matching is greedy one-to-one — once a gold item is claimed by a prediction it cannot be
 * claimed again, and vice versa — so a provider cannot inflate recall by emitting duplicates of
 * the same real item.
 *
 * This measures mechanical precision/recall once a gold dataset exists; it is not itself the
 * gold dataset and does not replace human review of what that dataset should contain. See
 * [ExtractionGoldDataset] and docs/product/AI_EVALUATION.md.
 */
object ExtractionEvaluator {

    /** Counts for one [ExtractedItem.kind] (or the synthetic `"overall"` rollup). */
    data class KindScore(
        val kind: String,
        val truePositives: Int,
        val falsePositives: Int,
        val falseNegatives: Int,
    ) {
        /** 1.0 when nothing was predicted for this kind: no prediction, no false positive. */
        val precision: Double get() = ratio(truePositives, truePositives + falsePositives)

        /** 1.0 when nothing was expected for this kind: nothing to miss. */
        val recall: Double get() = ratio(truePositives, truePositives + falseNegatives)

        val f1: Double get() {
            val p = precision
            val r = recall
            return if (p + r == 0.0) 0.0 else 2 * p * r / (p + r)
        }

        operator fun plus(other: KindScore): KindScore = KindScore(
            kind = kind,
            truePositives = truePositives + other.truePositives,
            falsePositives = falsePositives + other.falsePositives,
            falseNegatives = falseNegatives + other.falseNegatives,
        )

        companion object {
            fun empty(kind: String) = KindScore(kind, 0, 0, 0)
        }
    }

    /** Per-kind scores for one or more sessions, plus an [overall] rollup across every kind. */
    data class ExtractionScore(val byKind: Map<String, KindScore>) {
        val overall: KindScore
            get() = byKind.values.fold(KindScore.empty(OVERALL_KIND)) { acc, k -> acc + k }
    }

    private const val OVERALL_KIND = "overall"

    private fun ratio(numerator: Int, denominator: Int): Double =
        if (denominator == 0) 1.0 else numerator.toDouble() / denominator

    /**
     * Scores one session's predicted items against its gold items. [predicted] and [gold] must
     * reference the same transcript's 0-based evidence indices (TODO.md "evidence-backed").
     */
    fun score(predicted: List<ExtractedItem>, gold: List<ExtractedItem>): ExtractionScore {
        val kinds = (predicted.map { it.kind } + gold.map { it.kind }).toSortedSet()
        val byKind = kinds.associateWith { kind ->
            scoreKind(kind, predicted.filter { it.kind == kind }, gold.filter { it.kind == kind })
        }
        return ExtractionScore(byKind)
    }

    private fun scoreKind(
        kind: String,
        predicted: List<ExtractedItem>,
        gold: List<ExtractedItem>,
    ): KindScore {
        val claimedGold = mutableSetOf<Int>()
        var truePositives = 0
        for (p in predicted) {
            val matchIndex = gold.indices.firstOrNull { idx ->
                idx !in claimedGold && gold[idx].evidenceSegmentIndices.any { it in p.evidenceSegmentIndices }
            }
            if (matchIndex != null) {
                claimedGold += matchIndex
                truePositives++
            }
        }
        return KindScore(
            kind = kind,
            truePositives = truePositives,
            falsePositives = predicted.size - truePositives,
            falseNegatives = gold.size - truePositives,
        )
    }

    /** Aggregates multiple sessions' scores into one dataset-wide measurement. */
    fun aggregate(scores: List<ExtractionScore>): ExtractionScore {
        val allKinds = scores.flatMap { it.byKind.keys }.toSortedSet()
        val byKind = allKinds.associateWith { kind ->
            scores.mapNotNull { it.byKind[kind] }.fold(KindScore.empty(kind)) { acc, k -> acc + k }
        }
        return ExtractionScore(byKind)
    }
}
