package com.apercallc.aidailymemory.processing

import com.apercallc.aidailymemory.recording.processing.ExtractionEvaluator
import com.apercallc.aidailymemory.recording.processing.ExtractionGoldDataset
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Manually-triggered measurement for TODO.md Phase 2 "measure precision/recall for decisions,
 * explicit tasks, commitments, and ideas; treat false positives on tasks/commitments as a
 * high-severity failure".
 *
 * This is deliberately NOT part of `./gradlew qualityCheck`, `testDebugUnitTest`, or any CI
 * gate — it is guarded behind environment variables and is a complete no-op (assumption
 * skipped) when they are unset, because running it calls a real, user-configured LLM API and
 * spends that person's real provider quota/money. That should never happen silently as a side
 * effect of running the ordinary test suite (see docs/product/AI_EVALUATION.md).
 *
 * To actually run a measurement once [ExtractionGoldDataset] has at least one case with
 * `reviewed = true`:
 *
 * ```
 * GOLD_DATASET_API_URL=https://api.openai.com/v1/chat/completions \
 * GOLD_DATASET_API_KEY=sk-... \
 * GOLD_DATASET_MODEL=gpt-4o-mini \
 * ./gradlew :apps:android:testDebugUnitTest --tests "*GoldDatasetMeasurementRunner*"
 * ```
 *
 * It only ever scores cases already marked `reviewed = true`, so partial review already
 * produces a partial, honest measurement — this does not wait for every case in the dataset to
 * be reviewed before it becomes useful. It still refuses to report a number from zero reviewed
 * cases, since a precision/recall of "1.0 over nothing" would be misleading, not reassuring.
 */
class GoldDatasetMeasurementRunner {

    @Test
    fun measurePrecisionRecallAgainstReviewedGoldCases() {
        val apiUrl = System.getenv("GOLD_DATASET_API_URL")
        val apiKey = System.getenv("GOLD_DATASET_API_KEY")
        val model = System.getenv("GOLD_DATASET_MODEL")
        assumeTrue(
            "Set GOLD_DATASET_API_URL / GOLD_DATASET_API_KEY / GOLD_DATASET_MODEL to actually " +
                "run this measurement — see this class's kdoc. Skipping (this is expected in " +
                "ordinary test runs and CI).",
            apiUrl != null && apiKey != null && model != null,
        )

        val reviewed = ExtractionGoldDataset.cases.filter { it.reviewed }
        if (reviewed.isEmpty()) {
            println(
                "No reviewed gold cases yet (every ExtractionGoldDataset.cases entry still has " +
                    "reviewed = false) — nothing to measure. Review the dataset first; see " +
                    "docs/product/AI_EVALUATION.md.",
            )
            return
        }

        val provider = ApiKeyExtractionProvider(apiUrl = apiUrl!!, apiKey = apiKey!!, model = model!!)
        val scores = reviewed.map { case ->
            val predicted = runBlocking { provider.extract(case.transcript) }.items
            case.id to ExtractionEvaluator.score(predicted, case.expected)
        }
        val overall = ExtractionEvaluator.aggregate(scores.map { it.second })

        println(
            "=== Gold dataset measurement: ${reviewed.size}/${ExtractionGoldDataset.cases.size} " +
                "reviewed cases, model=$model ===",
        )
        for ((kind, score) in overall.byKind) {
            println(
                "%-10s precision=%.2f recall=%.2f f1=%.2f (tp=%d fp=%d fn=%d)".format(
                    kind,
                    score.precision,
                    score.recall,
                    score.f1,
                    score.truePositives,
                    score.falsePositives,
                    score.falseNegatives,
                ),
            )
        }
    }
}
