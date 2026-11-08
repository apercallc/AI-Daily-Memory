package com.apercallc.aidailymemory.recording.recall

import java.time.DayOfWeek
import java.time.LocalDate

/** A memory item as recall sees it: the person's corrected wording already applied to [text]. */
data class RecallItem(
    val id: String,
    val day: LocalDate,
    val kind: String,
    val text: String,
    val owner: String? = null,
    val counterpart: String? = null,
    val due: String? = null,
    val direction: String? = null,
)

data class RecallRange(val start: LocalDate, val end: LocalDate, val label: String)

/** One answer to one question. [citedIds] are the items it was built from, in display order. */
data class RecallAnswer(
    val text: String,
    val citedIds: List<String>,
    val abstained: Boolean,
    val rangeLabel: String?,
)

/**
 * One-shot Ask on the phone (ADR-0030): a question becomes a bounded search over the person's own
 * memory items, and the answer is only ever those items read back. No model, no conversation, no
 * network. Mirrors the server's `app/recall.py` so a phone-only account gets the same behavior;
 * change both together.
 */
object LocalRecall {
    const val MAX_CANDIDATES = 20
    const val MAX_SHOWN = 8
    const val MAX_TERMS = 12
    const val NOTHING_FOUND = "I don't have anything in your memory about that."

    private val STOPWORDS = setOf(
        "a", "about", "ago", "an", "and", "any", "anything", "are", "as", "ask",
        "at", "be", "been", "but", "by", "can", "could", "did", "do", "does",
        "find", "for", "from", "give", "had", "has", "have", "how", "i", "in",
        "is", "it", "its", "last", "me", "month", "my", "of", "on", "or",
        "our", "remember", "remind", "said", "say", "show", "so", "tell", "that", "the",
        "their", "them", "then", "there", "they", "this", "to", "today", "was", "we",
        "week", "were", "what", "when", "where", "which", "who", "whom", "why", "will",
        "with", "would", "yesterday", "you", "your",
    )
    private val KIND_HINTS = listOf(
        Regex("\\b(decid|decision|chose|choose|agreed on)\\w*") to "decision",
        Regex("\\b(promis|commit|owe|follow[- ]?up|owed)\\w*") to "commitment",
        Regex("\\b(todo|to-do|task|action item|need to do|have to do)\\w*") to "action",
        Regex("\\b(idea|brainstorm)\\w*") to "idea",
        Regex("\\b(question|wonder|open question|unresolved)\\w*") to "question",
    )
    private val LAST_N_DAYS = Regex("\\b(?:last|past)\\s+(\\d{1,3})\\s+days?\\b")
    private val TODAY = Regex("\\btoday\\b|\\bthis morning\\b|\\bthis afternoon\\b|\\btonight\\b")
    private val WORD = Regex("[a-z0-9][a-z0-9'-]+")

    /** The calendar span a question points at, or null when it names no time. Weeks run Monday to Sunday. */
    fun resolveRange(question: String, today: LocalDate): RecallRange? {
        val text = question.lowercase()
        if ("yesterday" in text) return today.minusDays(1).let { RecallRange(it, it, "yesterday") }
        if (TODAY.containsMatchIn(text)) return RecallRange(today, today, "today")
        LAST_N_DAYS.find(text)?.let { m ->
            val days = m.groupValues[1].toInt().coerceIn(1, 365)
            return RecallRange(today.minusDays(days - 1L), today, "the last $days days")
        }
        val monday = today.with(DayOfWeek.MONDAY)
        if ("last week" in text) return RecallRange(monday.minusDays(7), monday.minusDays(1), "last week")
        if ("this week" in text) return RecallRange(monday, today, "this week")
        val first = today.withDayOfMonth(1)
        if ("last month" in text) {
            val end = first.minusDays(1)
            return RecallRange(end.withDayOfMonth(1), end, "last month")
        }
        if ("this month" in text) return RecallRange(first, today, "this month")
        return null
    }

    /** The item kind a question is about ("what did I decide..."), or null. */
    fun kindHint(question: String): String? {
        val text = question.lowercase()
        return KIND_HINTS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(text) }?.second
    }

    /** The words that carry the question, in order, without filler. Bounded: pasted text must not become a huge query. */
    fun searchTerms(question: String): List<String> =
        WORD.findAll(question.lowercase()).map { it.value }
            .filter { it !in STOPWORDS }
            .distinct()
            .take(MAX_TERMS)
            .toList()

    /**
     * Crude stem so "decided", "deciding" and "decide" meet.
     * shortcut: English suffix stripping, not a real stemmer; upgrade if recall misses real questions.
     */
    internal fun stem(word: String): String {
        var w = word
        for (suffix in listOf("ing", "ed", "es", "s")) {
            if (w.length - suffix.length >= 3 && w.endsWith(suffix)) {
                w = w.dropLast(suffix.length)
                break
            }
        }
        return if (w.length > 3 && w.endsWith("e")) w.dropLast(1) else w
    }

    /** At most [MAX_CANDIDATES] items that could answer [question], best first. */
    fun retrieve(question: String, items: List<RecallItem>, today: LocalDate): Pair<List<RecallItem>, RecallRange?> {
        val window = resolveRange(question, today)
        val kind = kindHint(question)
        val terms = searchTerms(question).map(::stem)
        val inWindow = if (window == null) items else items.filter { it.day >= window.start && it.day <= window.end }

        val byRecency = compareByDescending<RecallItem> { it.day }.thenBy { it.id }
        if (terms.isNotEmpty()) {
            val scored = inWindow.map { it to matches(it, terms) }.filter { it.second > 0 }
            if (scored.isNotEmpty()) {
                val ranked = scored.sortedWith(compareByDescending<Pair<RecallItem, Int>> { it.second }.thenBy(byRecency) { it.first })
                return ranked.take(MAX_CANDIDATES).map { it.first } to window
            }
        }
        if (window != null || kind != null) {
            val broad = if (kind != null) inWindow.filter { it.kind == kind } else inWindow
            return broad.sortedWith(byRecency).take(MAX_CANDIDATES) to window
        }
        return emptyList<RecallItem>() to window
    }

    private fun matches(item: RecallItem, terms: List<String>): Int {
        val words = WORD.findAll("${item.text} ${item.owner.orEmpty()} ${item.counterpart.orEmpty()}".lowercase())
            .map { stem(it.value) }.toSet()
        return terms.count { it in words }
    }

    fun answer(question: String, items: List<RecallItem>, today: LocalDate): RecallAnswer {
        val (found, window) = retrieve(question, items, today)
        return build(found, window?.label)
    }

    internal fun build(found: List<RecallItem>, label: String?): RecallAnswer {
        if (found.isEmpty()) return RecallAnswer(NOTHING_FOUND, emptyList(), abstained = true, rangeLabel = label)
        val shown = found.take(MAX_SHOWN)
        val when_ = label?.let { " for $it" }.orEmpty()
        val lines = shown.joinToString("\n") { "- ${it.day} (${it.kind}): ${describe(it)}" }
        return RecallAnswer("Here is what your memory has$when_:\n$lines", shown.map { it.id }, abstained = false, rangeLabel = label)
    }

    private fun describe(item: RecallItem): String {
        val parts = mutableListOf(item.text)
        if (item.kind == "commitment" && !item.counterpart.isNullOrEmpty()) {
            val who = if (item.direction == "i_owe") "you owe" else "owed to you by"
            parts += "($who ${item.counterpart})"
        } else if (!item.owner.isNullOrEmpty() && item.owner != "me") {
            parts += "(owner: ${item.owner})"
        }
        if (!item.due.isNullOrEmpty()) parts += "due ${item.due}"
        return parts.joinToString(" ")
    }
}
