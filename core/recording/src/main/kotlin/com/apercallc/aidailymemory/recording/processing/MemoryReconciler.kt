package com.apercallc.aidailymemory.recording.processing

/**
 * A stable, evidence-anchored identity for one memory item, used to match a freshly
 * (re)extracted item back to the item it supersedes (TODO.md Phase 7 "Consolidate and supersede
 * memories while supporting reprocessing by extraction/schema version"). Extraction always
 * replaces a session's items outright on every run — simple and always-consistent, by design
 * (ADR-0019) — which means every item gets a brand-new id every time. Matching therefore has to
 * use something about the item's *content* that is likely to survive a better model rewording
 * the same real-world fact: primarily the evidence anchor (the same transcript moment), with the
 * item's other structured fields as the next most specific signal when no evidence anchor is
 * available on either side. Two items are the same real-world fact only if every field here is
 * equal; a changed field (e.g. a corrected due date pulled from a better re-extraction) is
 * intentionally treated as "no longer the same identity" rather than guessed at.
 */
data class MemoryItemIdentity(
    val kind: String,
    val evidenceEpochMs: Long?,
    val owner: String? = null,
    val counterpart: String? = null,
    val direction: String? = null,
    val due: String? = null,
)

/** One stored or about-to-be-stored memory item, reduced to its id and matching identity. */
data class IdentifiedMemoryItem(val id: String, val identity: MemoryItemIdentity)

/**
 * What a reprocessing run must carry forward from the items it is about to replace, so a
 * correction or dismissal never silently reappears or orphans (AGENTS.md "derived AI output is
 * always correctable"; known limitation documented on `MemoryItemCorrectionEntity`).
 */
data class MemoryReconciliation(
    /** New item id -> the dismissal time to carry forward (the original decision, not now). */
    val carriedDismissals: Map<String, Long>,
    /** Old item id -> new item id: every correction row tied to the old id should be repointed
     *  at the new one instead of becoming orphaned. */
    val correctionReassignments: Map<String, String>,
) {
    companion object {
        val NONE = MemoryReconciliation(emptyMap(), emptyMap())
    }
}

object MemoryReconciler {
    /**
     * Matches [current]'s freshly (re)extracted items back to [previous]'s stored items by
     * identity, greedily and one-to-one: a previous item matches at most one current item and
     * vice versa, so two similar-looking items are never silently merged into one. Previous
     * items with no match in [current] are left out of the result entirely — their corrections
     * become inert (kept in storage, never shown against a current item) rather than reassigned
     * to the wrong thing.
     */
    fun reconcile(
        previous: List<IdentifiedMemoryItem>,
        previousDismissedAt: Map<String, Long>,
        current: List<IdentifiedMemoryItem>,
    ): MemoryReconciliation {
        if (previous.isEmpty() || current.isEmpty()) return MemoryReconciliation.NONE
        val remainingCurrent = current.toMutableList()
        val carriedDismissals = mutableMapOf<String, Long>()
        val correctionReassignments = mutableMapOf<String, String>()
        for (old in previous) {
            val matchIndex = remainingCurrent.indexOfFirst { it.identity == old.identity }
            if (matchIndex < 0) continue
            val match = remainingCurrent.removeAt(matchIndex)
            correctionReassignments[old.id] = match.id
            previousDismissedAt[old.id]?.let { carriedDismissals[match.id] = it }
        }
        return MemoryReconciliation(carriedDismissals, correctionReassignments)
    }
}
