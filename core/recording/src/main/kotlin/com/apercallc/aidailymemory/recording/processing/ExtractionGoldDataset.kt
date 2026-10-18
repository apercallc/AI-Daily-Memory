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
        // Added in an agent-assisted review pass (2026-10-19): the original 8 cases never used
        // the "owed_to_me" commitment direction, never had more than one expected item per
        // transcript, and had no hard over-extraction trap beyond hedging. These fill those
        // gaps. Still agent-drafted, still unreviewed — same caveat as every case above.
        GoldCase(
            id = "commitment-owed-to-me",
            transcript = listOf(
                NumberedLine(0, "Tommy", "Can you review my PR before end of day?"),
                NumberedLine(1, "Marcus", "Yeah, I'll review it tonight, no problem."),
            ),
            expected = listOf(
                ExtractedItem(
                    kind = "commitment",
                    text = "Review the PR tonight",
                    confidence = "confirmed",
                    evidenceSegmentIndices = listOf(0, 1),
                    direction = "owed_to_me",
                    counterpart = "Marcus",
                    quote = "I'll review it tonight, no problem.",
                ),
            ),
            reviewed = false,
            notes = "First case exercising direction = owed_to_me; every other commitment case so far was i_owe.",
        ),
        GoldCase(
            id = "compound-decision-and-action-in-one-exchange",
            transcript = listOf(
                NumberedLine(0, "Tommy", "Let's cut the export feature from this release."),
                NumberedLine(
                    1,
                    "Marcus",
                    "Okay, I'll update the release notes to remove that line by tomorrow.",
                ),
            ),
            expected = listOf(
                ExtractedItem(
                    kind = "decision",
                    text = "Cut the export feature from this release",
                    confidence = "confirmed",
                    evidenceSegmentIndices = listOf(0),
                    quote = "Let's cut the export feature from this release.",
                ),
                ExtractedItem(
                    kind = "action",
                    text = "Update the release notes to remove the export line",
                    confidence = "confirmed",
                    evidenceSegmentIndices = listOf(1),
                    owner = "Marcus",
                    due = "tomorrow",
                ),
            ),
            reviewed = false,
            notes = "First case with more than one expected item from the same transcript — exercises" +
                " the evaluator's per-kind, per-item matching rather than one item per case.",
        ),
        GoldCase(
            id = "negative-sarcastic-hyperbole-is-not-a-commitment",
            transcript = listOf(
                NumberedLine(0, "Marcus", "Can you finish the whole rewrite by tomorrow?"),
                NumberedLine(1, "Tommy", "Oh sure, I'll rewrite the entire backend overnight, easy."),
            ),
            expected = emptyList(),
            reviewed = false,
            notes = "Sarcastic/hyperbolic reply to an unreasonable ask (\"oh sure... easy\" on an" +
                " overnight full-backend rewrite) — not a genuine commitment. A provider that takes" +
                " exaggerated agreement at face value would log a false positive here.",
        ),
        GoldCase(
            id = "negative-unassigned-complaint-is-not-an-action",
            transcript = listOf(
                NumberedLine(0, "Tommy", "We really need to fix that login crash before we ship."),
                NumberedLine(1, "Marcus", "Yeah, someone should look at it."),
            ),
            expected = emptyList(),
            reviewed = false,
            notes = "AGENT'S CALL, FLAGGED UNCERTAIN — needs Tommy's own judgment, not a rubber stamp:" +
                " nobody here takes ownership (\"someone should\" is not an assignment), so this leans" +
                " toward extracting nothing rather than an action with owner = null. But a real user" +
                " might genuinely want this surfaced as a low-confidence, unassigned task. Pick" +
                " whichever answer you'd actually want from your own memory app, not this guess.",
        ),
    )
}
