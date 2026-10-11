package com.apercallc.aidailymemory.capture

import com.apercallc.aidailymemory.recording.audio.AdtsScanner
import com.apercallc.aidailymemory.recording.storage.AudioRetention
import android.util.Log
import com.apercallc.aidailymemory.data.CaptureTimeColumns
import com.apercallc.aidailymemory.data.SegmentDao
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.platform.logFailure
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
import java.security.ProviderException

/** Audio format of a segment; recorded on every row so later decoding never guesses. */
data class SegmentFormat(val codec: String, val sampleRateHz: Int, val channelCount: Int)

sealed interface UserAudioDeletionResult {
    data class Deleted(val segments: Int) : UserAudioDeletionResult
    data object SessionActive : UserAudioDeletionResult
    data object RemoteCopyExists : UserAudioDeletionResult
}

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
    /** Resolves the key before the microphone opens, so a Keystore unwrap never delays audio. */
    fun warmUp() {
        cipher()
    }

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
            logFailure(TAG, "segment_close_failed", e)
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

    /**
     * Resolves every segment a previous process left WRITING. Returns how many were lost.
     *
     * A crash between the rename and the SEALED row leaves a complete, fsynced `.seg` file
     * behind a WRITING row: that audio is real and is sealed after re-verifying it. Anything
     * else (a torn `.part`, or a `.seg` that doesn't decrypt and parse cleanly) is quarantined
     * as a gap and its file removed.
     */
    suspend fun abandonUnfinished(): Int {
        val stale = dao.inState(SegmentState.WRITING.name)
        var lost = 0
        for (entity in stale) {
            val file = File(directory, entity.fileName).takeIf { it.isFile }
            val recovered = file?.let {
                when (val read = decrypt(entity, it)) {
                    // The Keystore is briefly unusable: leave the row WRITING and the file in
                    // place, so the next restore tries again. Never delete real audio for this.
                    SegmentRead.Unavailable -> continue
                    SegmentRead.Corrupt -> deleteAndForget(it)
                    is SegmentRead.Audio -> sealRecovered(entity, it, read.bytes)
                }
            }
            if (recovered?.state != SegmentState.SEALED.name) {
                abandon(entity)
                lost++
            }
        }
        return lost
    }

    /**
     * Applies the user's audio-retention setting (ADR-0013): deletes the local file and records
     * DELETED for every segment the lifecycle allows. The lifecycle refuses anything the server
     * hasn't acknowledged and anything inside the recovery window, whatever the setting says.
     * Returns how many segments were deleted.
     */
    suspend fun applyRetention(nowEpochMs: Long, retention: AudioRetention): Int {
        var deleted = 0
        val candidates = dao.inState(SegmentState.ACKNOWLEDGED.name) +
            dao.inState(SegmentState.QUARANTINED.name)
        for (entity in candidates) {
            val result = lifecycle.reduce(
                entity.toSegment(),
                SegmentEvent.RetentionDelete(nowEpochMs, retention),
            )
            if (result !is SegmentTransition.Applied) continue
            val file = File(directory, entity.fileName)
            // File first: a row that says DELETED while the audio still exists would be a lie
            // about the user's data. If the delete fails, try again next time.
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "retention_file_delete_failed")
                continue
            }
            dao.update(entity.withSegment(result.segment))
            deleted++
        }
        return deleted
    }

    /**
     * Deletes a stopped session's local audio at the user's request. This deliberately refuses
     * sessions with an in-progress segment or a segment uploaded/acknowledged by a server; the
     * caller must remove remote copies through the owning service before deleting those sessions.
     * Files are removed before their rows are updated, so a failure leaves a retryable row.
     */
    suspend fun deleteSessionAudio(sessionId: String): UserAudioDeletionResult {
        val candidates = dao.forSession(sessionId)
        if (candidates.any { it.state == SegmentState.WRITING.name }) {
            return UserAudioDeletionResult.SessionActive
        }
        if (candidates.any {
                it.state == SegmentState.UPLOADED.name || it.state == SegmentState.ACKNOWLEDGED.name
            }
        ) {
            return UserAudioDeletionResult.RemoteCopyExists
        }

        var deleted = 0
        for (entity in candidates) {
            val result = lifecycle.reduce(entity.toSegment(), SegmentEvent.UserDelete)
            when (result) {
                is SegmentTransition.Applied -> {
                    deleteSegmentFiles(entity)
                    dao.update(entity.withSegment(result.segment))
                    deleted++
                }
                is SegmentTransition.Ignored -> deleteSegmentFiles(entity)
                is SegmentTransition.Rejected -> throw IllegalStateException(
                    "local session deletion rejected: ${result.reason}",
                )
            }
        }
        return UserAudioDeletionResult.Deleted(deleted)
    }

    private fun deleteSegmentFiles(entity: SegmentEntity) {
        val files = listOf(File(directory, entity.fileName), partFile(entity))
        for (file in files) {
            if (file.exists() && !file.delete()) {
                throw IOException("could not delete local segment")
            }
        }
    }

    /**
     * Every clip of this segment has its transcript stored on this phone (ADR-0014): the audio
     * has served its purpose, and the retention setting may now delete it after the recovery
     * window. The segment's own checksum is what the acknowledgement is recorded against.
     */
    suspend fun markTranscribed(entity: SegmentEntity, nowEpochMs: Long): SegmentEntity {
        val sha256 = entity.sha256 ?: return entity
        return apply(entity, SegmentEvent.ServerAcknowledged(sha256, nowEpochMs))
    }

    /** Every clip of this segment is on the server and verified (ADR-0015). */
    suspend fun markUploaded(entity: SegmentEntity): SegmentEntity = apply(entity, SegmentEvent.UploadCompleted)

    /**
     * The decrypted, fully valid ADTS stream of a sealed segment, or null when the file is gone
     * (retention), fails authentication, or doesn't parse cleanly. Never partial audio.
     */
    fun readAudio(entity: SegmentEntity): ByteArray? {
        val file = File(directory, entity.fileName).takeIf { it.isFile } ?: return null
        return (decrypt(entity, file) as? SegmentRead.Audio)?.bytes
    }

    /** What reading a sealed file gave: audio, proof it isn't audio, or "try again later". */
    private sealed interface SegmentRead {
        class Audio(val bytes: ByteArray) : SegmentRead
        data object Corrupt : SegmentRead
        data object Unavailable : SegmentRead
    }

    private fun decrypt(entity: SegmentEntity, file: File): SegmentRead {
        val plaintext = try {
            cipher().decrypting(file.inputStream(), entity.segmentId).use { it.readBytes() }
        } catch (e: IOException) {
            // Includes authentication failures: a tampered or torn ciphertext is not audio.
            logFailure(TAG, "segment_unreadable", e, warning = true)
            return SegmentRead.Corrupt
        } catch (e: GeneralSecurityException) {
            logFailure(TAG, "segment_unreadable", e, warning = true)
            return SegmentRead.Corrupt
        } catch (e: ProviderException) {
            // A Keystore hiccup says nothing about the file: it may be perfectly good audio.
            logFailure(TAG, "segment_keystore_failure", e, warning = true)
            return SegmentRead.Unavailable
        }
        val scan = AdtsScanner.scan(plaintext)
        val valid = !scan.corrupt && scan.validBytes == plaintext.size && scan.completeFrames > 0
        return if (valid) SegmentRead.Audio(plaintext) else SegmentRead.Corrupt
    }

    private suspend fun sealRecovered(entity: SegmentEntity, file: File, plaintext: ByteArray): SegmentEntity {
        val scan = AdtsScanner.scan(plaintext)
        val sha256 = MessageDigest.getInstance("SHA-256").digest(plaintext)
            .joinToString("") { "%02x".format(it) }
        Log.i(TAG, "segment_recovered_after_crash")
        return apply(entity, SegmentEvent.Seal(file.length(), scan.durationMs, sha256))
    }

    private fun deleteAndForget(file: File): SegmentEntity? {
        if (!file.delete()) Log.w(TAG, "segment_file_delete_failed")
        return null
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
                Log.e(TAG, "segment_transition_rejected reason=${result.reason}")
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
            logFailure(TAG, "segment_directory_sync_failed", e, warning = true)
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
    } catch (e: Throwable) {
        // Any failure (a key error or an IOException writing the header) must not leak the
        // descriptor: one leak per failed segment adds up over a day of retries.
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
