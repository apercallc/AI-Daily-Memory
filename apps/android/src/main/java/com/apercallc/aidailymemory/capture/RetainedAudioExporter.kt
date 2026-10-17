package com.apercallc.aidailymemory.capture

import com.apercallc.aidailymemory.data.SegmentDao
import com.apercallc.aidailymemory.recording.segment.SegmentState
import java.io.File
import java.io.IOException

/**
 * Exports a session's still-retained local audio (TODO.md Phase 8 privacy package: "include
 * retained raw audio only in an on-device export; audio never uploads to the server") as one
 * playable file. Segments are already individually valid ADTS/AAC streams
 * (`AdtsHeaderWriter`/`SegmentStore`), so decrypting and concatenating them in capture order
 * produces one valid combined stream — no re-muxing needed.
 *
 * This only reads and writes a local cache file; it never calls the network. The caller (the UI
 * layer) is responsible for handing the resulting file to the Android share sheet via a
 * `FileProvider`, matching how a daily report is already shared as Markdown.
 */
class RetainedAudioExporter(
    private val segmentDao: SegmentDao,
    private val segmentStore: SegmentStore,
    private val exportDirectory: File,
) {
    /** True when at least one of this session's segments still has its audio file on disk. */
    suspend fun hasRetainedAudio(sessionId: String): Boolean =
        segmentDao.forSession(sessionId).any { it.state in RETAINED_STATES }

    /**
     * Writes this session's retained audio to a fresh file in [exportDirectory] (overwriting any
     * earlier export of the same session), in capture order. Returns null — and leaves no
     * partial file behind — when there is nothing retained or a segment fails to decrypt: a
     * truncated or corrupt export would misrepresent the person's own recording.
     */
    suspend fun export(sessionId: String): File? {
        val retained = segmentDao.forSession(sessionId).filter { it.state in RETAINED_STATES }
        if (retained.isEmpty()) return null
        if (!exportDirectory.isDirectory && !exportDirectory.mkdirs()) return null

        val file = File(exportDirectory, "$sessionId$EXTENSION")
        return try {
            file.outputStream().use { out ->
                for (segment in retained) {
                    val bytes = segmentStore.readAudio(segment)
                        ?: throw IOException("retained segment unreadable: ${segment.segmentId}")
                    out.write(bytes)
                }
            }
            file
        } catch (e: IOException) {
            file.delete()
            null
        }
    }

    private companion object {
        const val EXTENSION = ".aac"
        val RETAINED_STATES = setOf(
            SegmentState.SEALED.name,
            SegmentState.UPLOADED.name,
            SegmentState.ACKNOWLEDGED.name,
        )
    }
}
