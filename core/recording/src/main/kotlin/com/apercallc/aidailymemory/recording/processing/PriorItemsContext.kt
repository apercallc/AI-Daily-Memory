package com.apercallc.aidailymemory.recording.processing

/**
 * Formats currently-open commitments and actions as bounded prompt context for extraction
 * (TODO.md Phase 2 "evaluate prior open action items as extraction context").
 *
 * Pure and tested so the formatting itself never needs a live provider call to verify. Whether
 * including this context actually improves follow-up accuracy without adding false positives is
 * a separate, still-open measurement (docs/product/AI_EVALUATION.md) — this is the building
 * block that measurement needs, not a claim that the measurement has already run. Not wired in
 * by default for that reason.
 */
object PriorItemsContext {
    const val DEFAULT_MAX_CHARS = 2_000

    /**
     * Returns null when there is nothing open to mention, so a prompt is never padded with an
     * empty context section. Only actions and commitments are included — decisions, ideas, and
     * questions are not "open" in a way a later session could update.
     */
    fun describe(openItems: List<ExtractedItem>, maxChars: Int = DEFAULT_MAX_CHARS): String? {
        val relevant = openItems.filter { it.kind == "action" || it.kind == "commitment" }
        if (relevant.isEmpty()) return null

        val header = "Previously open items, for context only — do not re-extract these unless " +
            "the transcript below updates or resolves them:"
        val body = StringBuilder(header)
        for (item in relevant) {
            val line = "\n- [${item.kind}] ${item.text}${who(item)}${due(item)}"
            if (body.length + line.length > maxChars) break
            body.append(line)
        }
        // Nothing actually fit under the bound: a lone header with no items would mislead
        // rather than help, so this counts the same as "nothing open".
        return body.toString().takeIf { it.length > header.length }
    }

    private fun who(item: ExtractedItem): String = when (item.kind) {
        "commitment" -> " — ${item.direction ?: "commitment"} with ${item.counterpart ?: "someone"}"
        "action" -> item.owner?.let { " — owed by $it" } ?: ""
        else -> ""
    }

    private fun due(item: ExtractedItem): String = item.due?.let { " (due $it)" } ?: ""
}
