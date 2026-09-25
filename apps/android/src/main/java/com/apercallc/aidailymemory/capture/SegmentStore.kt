package com.apercallc.aidailymemory.capture

import android.util.Log
import com.apercallc.aidailymemory.data.CaptureTimeColumns
import com.apercallc.aidailymemory.data.SegmentDao
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.audio.AdtsHeaderWriter
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.segment.SegmentEvent
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.segment.SegmentState
import com.apercallc.aidailymemory.recording.segment.SegmentTransition
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.security.GeneralSecurityException
import java.security.MessageDigest

/** Audio format of a segment; recorded on every row so later decoding never guesses. */
data class SegmentFormat(val codec: String, val sampleRateHz: Int, val channelCount: Int)

/**
 * Owns segment files and their database rows. The row is written (WRITING) before the first
 * byte, so a crash always leaves a record that recovery can find and report as a gap.
 */
class SegmentStore(
    private val directory: File,
    private val dao: SegmentDao,
    private val cipher: () -> SegmentCipher,
    private val lifecycle: SegmentLifecycle,
    private val ids: UuidV7Generator,
    private val clock: DeviceClock,
) {
    /**
     * Starts the next segment of [sessionId].
     *
     * @throws EncryptionUnavailableException if the key cannot be used (nothing is written).
     * @throws IOException if the file cannot be created (the row is quarantined first).
     */
    suspend fun begin(
        sessionId: SessionId,
        continuesPrevious: Boolean,
        format: SegmentFormat,
    ): OpenSegment {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("segment directory unavailable")
        }
        // Before the row exists: a key failure must not leave a phantom segment behind.
        val segmentCipher = cipher()
        val segmentId = ids.next().toString()
        val entity = SegmentEntity(
            segmentId = segmentId,
            sessionId = sessionId.value,
            sequence = dao.nextSequence(sessionId.value),
            state = SegmentState.WRITING.name,
            byteSize = 0,
            durationMs = 0,
            sha256 = null,
            acknowledgedAtEpochMs = null,
            defect = null,
            fileName = "$segmentId$SEALED_SUFFIX",
            codec = format.codec,
            sampleRateHz = format.sampleRateHz,
            channelCount = format.channelCount,
            continuesPrevious = continuesPrevious,
            start = CaptureTimeColumns.of(clock.now()),
        )
        dao.insert(entity)
        return try {
            OpenSegment(entity, partFile(entity), segmentCipher, format)
        } catch (e: IOException) {
            abandon(entity)
            throw e
        } catch (e: GeneralSecurityException) {
            abandon(entity)
            throw EncryptionUnavailableException("segment stream unavailable", e)
        }
    }

    /**
     * Closes, fsyncs, and renames the file, then records SEALED (or QUARANTINED if invalid).
     * Only after this returns does the audio count as captured.
     */
    suspend fun seal(segment: OpenSegment): SegmentEntity {
        val closed = try {
            segment.close()
        } catch (e: IOException) {
            Log.e(TAG, "segment_close_failed", e)
            return abandon(segment.entity)
        }
        val sealedFile = File(directory, segment.entity.fileName)
        if (!partFile(segment.entity).renameTo(sealedFile)) {
            Log.e(TAG, "segment_rename_failed")
            return abandon(segment.entity)
        }
        syncDirectory()
        val event = SegmentEvent.Seal(
            // An encrypted empty stream still has a header; empty audio must look empty.
            byteSize = if (closed.plaintextBytes == 0L) 0 else sealedFile.length(),
            durationMs = closed.durationMs,
            sha256 = closed.sha256,
        )
        return apply(segment.entity, event)
    }

    /** Quarantines every segment a previous process left WRITING and removes its torn file. */
    suspend fun abandonUnfinished(): Int {
        val stale = dao.inState(SegmentState.WRITING.name)
        stale.forEach { abandon(it) }
        return stale.size
    }

    private suspend fun abandon(entity: SegmentEntity): SegmentEntity {
        val part = partFile(entity)
        if (part.exists() && !part.delete()) Log.w(TAG, "segment_part_delete_failed")
        return apply(entity, SegmentEvent.Abandon)
    }

    private suspend fun apply(entity: SegmentEntity, event: SegmentEvent): SegmentEntity {
        val current = dao.get(entity.segmentId) ?: entity
        return when (val result = lifecycle.reduce(current.toSegment(), event)) {
            is SegmentTransition.Applied -> current.withSegment(result.segment).also { dao.update(it) }
            is SegmentTransition.Ignored -> current
            is SegmentTransition.Rejected -> {
                // A rejected seal means the store and the lifecycle disagree: surface it.
                Log.e(TAG, "segment_transition_rejected", IllegalStateException(result.reason.name))
                current
            }
        }
    }

    private fun partFile(entity: SegmentEntity) = File(directory, "${entity.segmentId}$PART_SUFFIX")

    /** Makes the rename durable. Best effort: the file data itself is already synced. */
    private fun syncDirectory() {
        try {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            Log.w(TAG, "segment_directory_sync_failed", e)
        }
    }

    companion object {
        private const val TAG = "SegmentStore"
        const val PART_SUFFIX = ".part"
        const val SEALED_SUFFIX = ".seg"
    }
}

/** Facts about a closed segment, computed while writing. */
data class ClosedSegment(val plaintextBytes: Long, val durationMs: Long, val sha256: String)

/**
 * One segment being written on the capture thread. Not thread-safe. Writes ADTS frames into a
 * streaming-AEAD file while hashing the plaintext, which is what the server will verify.
 */
class OpenSegment internal constructor(
    val entity: SegmentEntity,
    file: File,
    cipher: SegmentCipher,
    format: SegmentFormat,
) {
    private val fileOutput = FileOutputStream(file)
    private val output: OutputStream = try {
        cipher.encrypting(SyncOnClose(fileOutput), entity.segmentId)
    } catch (e: GeneralSecurityException) {
        fileOutput.close()
        throw e
    }
    private val digest = MessageDigest.getInstance("SHA-256")
    private val adts = AdtsHeaderWriter(format.sampleRateHz, format.channelCount)
    private val header = ByteArray(AdtsHeaderWriter.HEADER_SIZE)
    private val payload = ByteArray(AdtsHeaderWriter.MAX_FRAME_LENGTH)
    private val sampleRateHz = format.sampleRateHz
    private var plaintextBytes = 0L
    private var frames = 0L

    var isClosed = false
        private set

    /** Audio duration written so far, from encoded frames (AAC-LC: 1024 samples each). */
    val durationMs: Long get() = frames * SAMPLES_PER_FRAME * MILLIS / sampleRateHz

    /** Writes one raw AAC access unit as an ADTS frame. */
    fun writeFrame(buffer: ByteBuffer, size: Int) {
        adts.write(size, header)
        buffer.get(payload, 0, size)
        output.write(header)
        output.write(payload, 0, size)
        digest.update(header)
        digest.update(payload, 0, size)
        plaintextBytes += header.size + size
        frames++
    }

    internal fun close(): ClosedSegment {
        check(!isClosed) { "segment already closed" }
        isClosed = true
        output.close()
        return ClosedSegment(
            plaintextBytes = plaintextBytes,
            durationMs = durationMs,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    /** Flushes and fsyncs before the file descriptor closes: sealed means on disk. */
    private class SyncOnClose(private val file: FileOutputStream) : FilterOutputStream(file) {
        override fun write(b: ByteArray, off: Int, len: Int) = file.write(b, off, len)

        override fun close() {
            file.flush()
            file.fd.sync()
            file.close()
        }
    }

    private companion object {
        const val SAMPLES_PER_FRAME = 1024L
        const val MILLIS = 1000L
    }
}
