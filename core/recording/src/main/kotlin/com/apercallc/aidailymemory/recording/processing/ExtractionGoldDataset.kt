package com.apercallc.aidailymemory.recording.processing

/**
 * Seed cases for TODO.md Phase 2 "create human-reviewed gold dataset".
 *
 * IMPORTANT — every case below has [GoldCase.reviewed] = false. They were drafted by an agent
 * session as a starting shape (coverage of each [ExtractedItem.kind], plus deliberate negative
 * cases), not confirmed by a human as ground truth. TODO.md calls a false-positive task or
 * commitment a high-severity failure, so the negative cases matter as much as the positive ones.
 *
 * Do not report a precision/recall number against this file, and do not treat the "AI
 * evaluation" TODO items as closed, until a human has independently read each transcript,
 * decided what should have been extracted, corrected [GoldCase.expected] to match, and flipped
 * `reviewed` to `true` — recording who and when in [GoldCase.notes]. See
 * docs/product/AI_EVALUATION.md for the review process and how to add a confirmed-bug
 * regression fixture once one exists.
 */
object ExtractionGoldDataset {

    data class GoldCase(
        val id: String,
        val transcript: List<NumberedLine>,
        val expected: List<ExtractedItem>,
        val reviewed: Boolean,
        val notes: String? = null,
    )

    val cases: List<GoldCase> = listOf(
        GoldCase(
            id = "decision-ship-auth-before-billing",
            transcript = listOf(
                NumberedLine(0, "Tommy", "Let's ship auth before we touch billing."),
                NumberedLine(1, "Marcus", "Agreed, billing can wait until next sprint."),
            ),
            expected = listOf(
                ExtractedItem(
                    kind = "decision",
                    text = "Ship auth before billing",
                    confidence = "confirmed",
                    evidenceSegmentIndices = listOf(0, 1),
                    quote = "Let's ship auth before we touch billing.",
                ),
            ),
            reviewed = false,
        ),
        GoldCase(
            id = "action-owner-and-due-date",
            transcript = listOf(
                NumberedLine(0, "Tommy", "Marcus, can you draft the scope doc by Wednesday?"),
                NumberedLine(1, "Marcus", "Sure, I'll have it ready by then."),
            ),
            expected = listOf(
                ExtractedItem(
                    kind = "action",
                    text = "Draft the scope doc",
                    confidence = "confirmed",
                    evidenceSegmentIndices = listOf(0, 1),
                    owner = "Marcus",
                    due = "Wednesday",
                ),
            ),
            reviewed = false,
        ),
        GoldCase(
            id = "commitment-i-owe",
            transcript = listOf(
                NumberedLine(0, "Tommy", "I'll send you the PR by Friday, Marcus."),
            ),
            expected = listOf(
                ExtractedItem(
                    kind = "commitment",
                    text = "Send the PR by Friday",
                    confidence = "confirmed",
                    evidenceSegmentIndices = listOf(0),
                    direction = "i_owe",
                    counterpart = "Marcus",
                ),
            ),
            reviewed = false,
        ),
        GoldCase(
            id = "idea-standalone",
            transcript = listOf(
                NumberedLine(
                    0,
                    "Tommy",
                    "What if we cached the daily report instead of rebuilding it every time?",
                ),
            ),
            expected = listOf(
                ExtractedItem(
                    kind = "idea",
                    text = "Cache the daily report instead of rebuilding it every time",
                    confidence = "likely",
                    evidenceSegmentIndices = listOf(0),
                ),
            ),
            reviewed = false,
        ),
        GoldCase(
            id = "question-open",
            transcript = listOf(
                NumberedLine(0, "Marcus", "Do we actually need the watch app at launch?"),
            ),
            expected = listOf(
                ExtractedItem(
                    kind = "question",
                    text = "Do we need the watch app at launch?",
                    confidence = "confirmed",
                    evidenceSegmentIndices = listOf(0),
                ),
            ),
            reviewed = false,
        ),
        // Negative cases: a transcript that should extract nothing of a given kind. These are
        // what actually exercises the "false positive" failure mode TODO.md calls out, and a
        // dataset of positive-only examples would never catch a provider that over-extracts.
        GoldCase(
            id = "negative-hedged-hypothetical-is-not-a-commitment",
            transcript = listOf(
                NumberedLine(0, "Tommy", "I could maybe send it Friday if things go well, who knows."),
            ),
            expected = emptyList(),
            reviewed = false,
            notes = "Hedged/hypothetical phrasing — extracting a commitment here would be a false positive.",
        ),
        GoldCase(
            id = "negative-small-talk-extracts-nothing",
            transcript = listOf(
                NumberedLine(0, "Marcus", "Good morning, how was your weekend?"),
                NumberedLine(1, "Tommy", "Pretty quiet, thanks."),
            ),
            expected = emptyList(),
            reviewed = false,
        ),
        GoldCase(
            id = "negative-past-completed-action-is-not-open",
            transcript = listOf(
                NumberedLine(0, "Tommy", "I already sent the deck yesterday, so we're good there."),
            ),
            expected = emptyList(),
            reviewed = false,
            notes = "Already-completed work — extracting an open action here would be a false positive.",
        ),
    )
}
