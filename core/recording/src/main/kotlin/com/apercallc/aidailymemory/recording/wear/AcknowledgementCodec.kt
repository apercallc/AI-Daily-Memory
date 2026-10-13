package com.apercallc.aidailymemory.recording.wear

/**
 * Text form of recent command acknowledgements so a phone restart does not forget which
 * watch commands it already answered (a retried command must get the same answer, not run
 * twice). One entry per line: `commandId|OUTCOME|revision`, oldest first.
 */
object AcknowledgementCodec {
    private const val SEPARATOR = '|'

    fun encode(entries: List<CommandAcknowledgement>): String = entries.joinToString("\n") {
        "${it.commandId}$SEPARATOR${it.outcome.name}$SEPARATOR${it.revision}"
    }

    /** Skips malformed lines; a damaged store must never block commands. */
    fun decode(text: String?): List<CommandAcknowledgement> =
        text.orEmpty().lineSequence().mapNotNull { line ->
            val parts = line.split(SEPARATOR)
            if (parts.size != 3 || parts[0].isBlank()) return@mapNotNull null
            val outcome = CommandOutcome.entries.firstOrNull { it.name == parts[1] } ?: return@mapNotNull null
            val revision = parts[2].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
            CommandAcknowledgement(parts[0], outcome, revision)
        }.toList()

    /** A command id must be encodable: no separator or line breaks. */
    fun isEncodable(commandId: String): Boolean =
        commandId.isNotBlank() && commandId.none { it == SEPARATOR || it == '\n' || it == '\r' }
}
