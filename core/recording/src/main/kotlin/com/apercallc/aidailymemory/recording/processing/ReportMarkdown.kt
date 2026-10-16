package com.apercallc.aidailymemory.recording.processing

/**
 * Renders a [DailyReport] as a Markdown document for the Android share sheet (TODO.md "Add an
 * in-app report screen and share reports as Markdown through the Android share sheet").
 *
 * This only formats content the person already sees in the report; it never generates new
 * content, so it stays grounded in the same evidence-backed items (AGENTS.md "no silent
 * failure" / ADR-0004 evidence rules apply to the report itself, not to this renderer).
 */

private val KIND_HEADINGS: List<Pair<String, String>> =
    listOf(
        "decision" to "Decisions",
        "action" to "Action items",
        "commitment" to "Commitments",
        "idea" to "Ideas",
        "question" to "Questions",
    )

fun renderMarkdown(report: DailyReport): String {
    val body = StringBuilder()
    body.append("# Memory for ${report.memoryDay}\n\n")
    body.append("**${report.headline}**\n\n")
    body.append("${report.summary}\n\n")
    body.append(coverageLine(report.coverage))
    if (report.status == DailyReportStatus.PARTIAL) {
        body.append(PARTIAL_NOTICE)
    }

    val sections = itemSections(report.items)
    if (sections.isNotEmpty()) {
        body.append("\n")
        body.append(sections)
    }
    return body.toString()
}

private const val PARTIAL_NOTICE =
    "Still processing — not all of today's audio has been analyzed yet.\n"

private fun coverageLine(coverage: ReportCoverage): String {
    val minutes = (coverage.recordedMs + 30_000) / 60_000
    val sessionWord = if (coverage.sessions == 1) "session" else "sessions"
    return "Recorded $minutes min across ${coverage.sessions} $sessionWord.\n"
}

private fun itemSections(items: List<ReportItem>): String {
    val byKind = items.groupBy { it.kind }
    val sections = StringBuilder()
    var firstSection = true
    for ((kind, heading) in KIND_HEADINGS) {
        val kindItems = byKind[kind] ?: continue
        if (!firstSection) sections.append("\n")
        firstSection = false
        sections.append("## $heading\n")
        for (item in kindItems) {
            sections.append("- ${item.text}${itemDetail(item)}\n")
            if (!item.quote.isNullOrBlank()) {
                sections.append("  > ${item.quote}\n")
            }
        }
    }
    return sections.toString()
}

private fun itemDetail(item: ReportItem): String =
    when (item.kind) {
        "action" -> actionDetail(item.owner, item.due)
        "commitment" -> commitmentDetail(item.direction, item.counterpart)
        else -> ""
    }

private fun actionDetail(
    owner: String?,
    due: String?,
): String {
    val parts = listOfNotNull(owner, due?.let { "due $it" })
    return if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
}

private fun commitmentDetail(
    direction: String?,
    counterpart: String?,
): String {
    if (counterpart == null) return ""
    return when (direction) {
        "i_owe" -> " (I owe $counterpart)"
        "owed_to_me" -> " ($counterpart owes this)"
        else -> " ($counterpart)"
    }
}
