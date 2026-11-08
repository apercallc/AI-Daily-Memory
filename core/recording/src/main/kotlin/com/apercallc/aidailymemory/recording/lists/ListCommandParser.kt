package com.apercallc.aidailymemory.recording.lists

/** One explicit "add X to the Y list" command found in speech (ADR-0030). */
data class ListCommand(
    /** Lowercase list name, e.g. "shopping". */
    val listName: String,
    /** Items in spoken order, trimmed and de-duplicated. Never empty. */
    val items: List<String>,
    /** The sentence the command came from; the evidence shown next to each item. */
    val sentence: String,
)

/**
 * Finds explicit list commands in a transcript line. Pure and deterministic: no model, no network.
 *
 * Only a sentence that *starts* with "add" or "put" and ends with "... to/on the <name> list" is a
 * command. Casual speech ("we need milk"), questions ("did you add milk to the list?") and
 * statements about a list ("the shopping list is on the fridge") produce nothing: a wrong list
 * item is a defect, so unusual phrasing is missed rather than guessed.
 */
object ListCommandParser {
    const val DEFAULT_LIST = "shopping"
    const val MAX_ITEMS_PER_COMMAND = 20
    const val MAX_ITEM_CHARS = 80
    const val MAX_LIST_NAME_WORDS = 3

    /** Longer sentences are skipped: bounds regex work on pasted or runaway transcript text. */
    private const val MAX_SENTENCE_CHARS = 300

    private val SENTENCE_END = Regex("(?<=[.!?])\\s+|\\n+")
    private val COMMAND = Regex(
        "^(?:(?:okay|ok|so|please|hey|also|and)[,\\s]+)*" +
            "(?:add|put)\\s+(.+?)\\s+(?:to|on|onto|in)\\s+(?:(?:my|the|our|a)\\s+)?(.*?)\\s*list\\W*$",
        RegexOption.IGNORE_CASE,
    )

    // shortcut: "mac and cheese" splits into two items, say them with a comma-free pause or fix by hand.
    private val ITEM_SEPARATOR = Regex("\\s*(?:,|&|\\band\\b|\\bplus\\b)\\s*", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("\\s+")

    fun parse(text: String): List<ListCommand> =
        text.split(SENTENCE_END)
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_SENTENCE_CHARS }
            .mapNotNull(::parseSentence)

    /** Case- and spacing-insensitive identity of an item, for de-duplication. */
    fun key(text: String): String = text.trim().replace(WHITESPACE, " ").lowercase()

    private fun parseSentence(sentence: String): ListCommand? {
        val match = COMMAND.matchEntire(sentence) ?: return null
        val name = listName(match.groupValues[2]) ?: return null
        val items = items(match.groupValues[1])
        if (items.isEmpty()) return null
        return ListCommand(name, items, sentence)
    }

    private fun listName(raw: String): String? {
        val words = raw.trim().lowercase().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size > MAX_LIST_NAME_WORDS) return null
        if (words.isEmpty()) return DEFAULT_LIST
        val name = words.joinToString(" ")
        return if (name == "groceries") "grocery" else name
    }

    private fun items(raw: String): List<String> {
        val seen = mutableSetOf<String>()
        return raw.split(ITEM_SEPARATOR)
            .map { it.trim().trim('.', '!', '?', ';', ':').replace(WHITESPACE, " ") }
            .filter { it.isNotEmpty() && it.length <= MAX_ITEM_CHARS }
            .filter { seen.add(key(it)) }
            .take(MAX_ITEMS_PER_COMMAND)
    }
}
