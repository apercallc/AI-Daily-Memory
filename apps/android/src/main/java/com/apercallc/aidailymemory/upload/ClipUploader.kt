package com.apercallc.aidailymemory.upload

import android.util.Log
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.ClipDao
import com.apercallc.aidailymemory.data.ClipUploadEntity
import com.apercallc.aidailymemory.data.SegmentDao
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.data.SpeechDao
import com.apercallc.aidailymemory.net.ApiException
import com.apercallc.aidailymemory.net.ClipRequest
import com.apercallc.aidailymemory.net.ServerLink
import com.apercallc.aidailymemory.net.UploadApi
import com.apercallc.aidailymemory.recording.audio.AdtsScanner
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.speech.ClipPlanner
import com.apercallc.aidailymemory.recording.speech.SpeechRegion
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Why a run stopped early; each maps to a different "try again when" for the job. */
enum class UploadStop { NOT_LINKED, OFFLINE, UNAUTHORIZED, QUOTA, SERVER }

data class UploadRun(val received: Int, val failed: Int, val remaining: Int, val stoppedBy: UploadStop?)

/**
 * Uploads speech clips (ADR-0015) through the server's idempotent protocol and marks a
 * segment UPLOADED once the server has verified every clip of it.
 *
 * Nothing is deleted here: local audio stays until its transcript is stored where the user's
 * data lives (ADR-0014), which is a later acknowledgement, not this upload.
 */
class ClipUploader(
    private val store: SegmentStore,
    private val segments: SegmentDao,
    private val clips: ClipDao,
    private val speech: SpeechDao,
    private val api: UploadApi,
    private val link: () -> ServerLink?,
    private val ids: UuidV7Generator,
) {
    suspend fun run(limit: Int = BATCH): UploadRun {
        val server = link() ?: return UploadRun(0, 0, clips.pendingCount(), UploadStop.NOT_LINKED)
        planClips()
        var received = 0
        var failed = 0
        var stop: UploadStop? = null
        val audio = AudioCache(store, segments)
        for (clip in clips.pending(limit)) {
            currentCoroutineContext().ensureActive()
            when (val outcome = upload(server, clip, audio)) {
                Outcome.Received -> received++
                Outcome.Failed -> failed++
                Outcome.Retry -> Unit
                is Outcome.Stop -> {
                    stop = outcome.reason
                    break
                }
            }
        }
        markUploadedSegments()
        val remaining = clips.pendingCount()
        Log.i(TAG, "clip_upload_batch received=$received failed=$failed remaining=$remaining stop=$stop")
        return UploadRun(received, failed, remaining, stop)
    }

    /** Turns speech regions into frame-aligned clips with their final size and checksum. */
    private suspend fun planClips() {
        for (segment in clips.unplanned(PLAN_BATCH)) {
            val audio = store.readAudio(segment) ?: continue
            val frames = frameOffsets(audio)
            val regions = speech.regions(segment.segmentId).map {
                SpeechRegion(it.startMs, it.endMs, it.speechMs, it.lowConfidence)
            }
            val planned = ClipPlanner.plan(regions, frames.size - 1, segment.sampleRateHz).map { clip ->
                val bytes = slice(audio, frames, clip.firstFrame, clip.endFrame)
                ClipUploadEntity(
                    clipId = ids.next().toString(),
                    segmentId = segment.segmentId,
                    firstFrame = clip.firstFrame,
                    endFrame = clip.endFrame,
                    offsetMs = clip.offsetMs,
                    durationMs = clip.durationMs,
                    byteSize = bytes.size.toLong(),
                    sha256 = sha256(bytes),
                    state = PENDING,
                    attempts = 0,
                    lastError = null,
                )
            }
            clips.plan(planned)
        }
    }

    private sealed interface Outcome {
        data object Received : Outcome
        data object Failed : Outcome
        data object Retry : Outcome
        data class Stop(val reason: UploadStop) : Outcome
    }

    private suspend fun upload(server: ServerLink, clip: ClipUploadEntity, cache: AudioCache): Outcome {
        val segment = cache.segment(clip.segmentId) ?: return fail(clip, "segment_missing")
        val (audio, frames) = cache.audio(segment) ?: return fail(clip, "audio_unreadable")
        if (clip.endFrame > frames.size - 1) return fail(clip, "audio_changed")
        val bytes = slice(audio, frames, clip.firstFrame, clip.endFrame)
        // The plan fixed the checksum; different bytes now means the file changed underneath.
        if (sha256(bytes) != clip.sha256) return fail(clip, "audio_changed")
        return try {
            var state = api.create(server, request(segment, clip))
            state.target?.let { target ->
                api.put(target, bytes)
                state = api.complete(server, clip.clipId)
            }
            if (state.state == "received" || state.state == "transcribed") {
                clips.update(clip.copy(state = RECEIVED, lastError = null))
                Outcome.Received
            } else {
                retry(clip, "state_${state.state}")
            }
        } catch (e: ApiException) {
            handle(clip, e)
        } catch (e: IOException) {
            Log.w(TAG, "clip_upload_network_error")
            Outcome.Stop(UploadStop.OFFLINE)
        }
    }

    private suspend fun handle(clip: ClipUploadEntity, e: ApiException): Outcome = when {
        e.status == HTTP_UNAUTHORIZED || e.status == HTTP_FORBIDDEN -> Outcome.Stop(UploadStop.UNAUTHORIZED)
        e.status == HTTP_TOO_MANY -> Outcome.Stop(UploadStop.QUOTA)
        e.status >= HTTP_SERVER_ERROR -> Outcome.Stop(UploadStop.SERVER)
        // Storage said no to the PUT, or the bytes didn't verify: upload again next run.
        e.code in RETRYABLE_CODES -> retry(clip, e.code)
        // Conflicts and validation errors won't change on retry: terminal, and visible.
        else -> fail(clip, e.code)
    }

    private suspend fun retry(clip: ClipUploadEntity, reason: String): Outcome {
        val attempts = clip.attempts + 1
        if (attempts >= MAX_ATTEMPTS) return fail(clip.copy(attempts = attempts), reason)
        clips.update(clip.copy(attempts = attempts, lastError = reason))
        return Outcome.Retry
    }

    private suspend fun fail(clip: ClipUploadEntity, reason: String): Outcome {
        Log.e(TAG, "clip_upload_failed reason=$reason")
        clips.update(clip.copy(state = FAILED, lastError = reason))
        return Outcome.Failed
    }

    private suspend fun markUploadedSegments() {
        for (segment in clips.fullyUploaded()) store.markUploaded(segment)
    }

    private fun request(segment: SegmentEntity, clip: ClipUploadEntity): ClipRequest {
        val start = Instant.ofEpochMilli(segment.start.occurredAtEpochMs + clip.offsetMs)
        val offset = ZoneOffset.ofTotalSeconds(segment.start.utcOffsetMinutes * SECONDS_PER_MINUTE)
        return ClipRequest(
            uploadId = clip.clipId,
            sessionId = segment.sessionId,
            segmentId = segment.segmentId,
            offsetMs = clip.offsetMs,
            durationMs = clip.durationMs,
            byteSize = clip.byteSize,
            sha256 = clip.sha256,
            codec = segment.codec,
            sampleRateHz = segment.sampleRateHz,
            channelCount = segment.channelCount,
            capturedAt = OffsetDateTime.ofInstant(start, offset).toString(),
        )
    }

    /** Reads each segment once per run: its clips are consecutive in the pending order. */
    private class AudioCache(private val store: SegmentStore, private val segments: SegmentDao) {
        private var id: String? = null
        private var entity: SegmentEntity? = null
        private var audio: Pair<ByteArray, IntArray>? = null

        suspend fun segment(segmentId: String): SegmentEntity? {
            if (segmentId != id) {
                id = segmentId
                entity = segments.get(segmentId)
                audio = null
            }
            return entity
        }

        fun audio(segment: SegmentEntity): Pair<ByteArray, IntArray>? {
            if (audio == null) audio = store.readAudio(segment)?.let { it to frameOffsets(it) }
            return audio
        }
    }

    companion object {
        private const val TAG = "ClipUploader"
        const val PENDING = "PENDING"
        const val RECEIVED = "RECEIVED"
        const val FAILED = "FAILED"
        const val MAX_ATTEMPTS = 5
        private const val BATCH = 100
        private const val PLAN_BATCH = 50
        private const val SECONDS_PER_MINUTE = 60
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER_ERROR = 500
        private val RETRYABLE_CODES = setOf("upload_integrity_failed", "upload_missing", "storage_rejected")

        /** Frame start offsets, plus the end of the last frame as a sentinel. */
        internal fun frameOffsets(audio: ByteArray): IntArray {
            val offsets = ArrayList<Int>()
            val scan = AdtsScanner.scan(audio) { offset, _ -> offsets += offset }
            offsets += scan.validBytes
            return offsets.toIntArray()
        }

        internal fun slice(audio: ByteArray, frames: IntArray, first: Int, end: Int): ByteArray =
            audio.copyOfRange(frames[first], frames[end])

        internal fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
