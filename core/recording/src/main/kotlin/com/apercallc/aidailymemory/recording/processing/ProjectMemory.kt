package com.apercallc.aidailymemory.recording.processing

/**
 * One open commitment or action, reduced to what grouping and ordering need (TODO.md Phase 7:
 * "durable summaries and project memory for recurring commitments and relationships"). The
 * daily timeline already shows every item per session per day; this groups the subset that
 * still matters — open (non-dismissed) commitments and actions — by who they involve, so a
 * promise made weeks ago to the same person doesn't disappear once its day scrolls out of view.
 */
data class ProjectMemoryItem(
    val id: String,
    val text: String,
    val kind: String,
    val due: String?,
    val lastMentionedEpochMs: Long,
)

/**
 * Every open item involving one subject (a counterpart, or an owner when no counterpart was
 * extracted), newest mention first. [subject] is null for items with neither — the view
 * resolves that to a localized "no specific person" label, the same way [DayLabel]-style
 * resolution keeps UI strings out of pure data.
 */
data class ProjectMemoryGroup(
    val subject: String?,
    val items: List<ProjectMemoryItem>,
    val lastMentionedEpochMs: Long,
)

object ProjectMemoryBuilder {
    /**
     * Groups [items] (already filtered by the caller to open commitments/actions) by
     * [subjectOf], newest-mention-first within each group and across groups. Generic over the
     * caller's own item type so this stays free of any storage dependency; the caller supplies
     * how to read a subject and a [ProjectMemoryItem] out of its own rows.
     */
    fun <T> build(
        items: List<T>,
        subjectOf: (T) -> String?,
        toItem: (T) -> ProjectMemoryItem,
    ): List<ProjectMemoryGroup> =
        items.groupBy(subjectOf)
            .map { (subject, grouped) ->
                val sortedItems = grouped.map(toItem).sortedByDescending { it.lastMentionedEpochMs }
                ProjectMemoryGroup(subject, sortedItems, sortedItems.first().lastMentionedEpochMs)
            }
            .sortedByDescending { it.lastMentionedEpochMs }
}
