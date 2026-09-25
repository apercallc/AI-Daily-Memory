package com.apercallc.aidailymemory.upload

import android.util.Log
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.ClipDao
import com.apercallc.aidailymemory.data.ClipPlanEntity
import com.apercallc.aidailymemory.data.ClipUploadEntity
import com.apercallc.aidailymemory.data.SegmentDao
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.data.SpeechDao
import com.apercallc.aidailymemory.net.ApiException
import com.apercallc.aidailymemory.net.ClipRequest
import com.apercallc.aidailymemory.net.ServerLink
import com.apercallc.aidailymemory.net.StorageException
import com.apercallc.aidailymemory.net.UploadApi
import com.apercallc.aidailymemory.recording.audio.AdtsScanner
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.speech.ClipPlanner
import com.apercallc.aidailymemory.recording.speech.SpeechRegion
import com.apercallc.aidailymemory.recording.upload.UploadStop
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One run's result. [remaining] counts clips still pending *and* segments not yet planned, so
 * a backlog larger than one batch keeps the job going.
 */
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
    /** Only audio captured at or after this time is uploaded (see LinkStore). */
    private val uploadSinceEpochMs: () -> Long,
    private val ids: UuidV7Generator,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val planBatch: Int = PLAN_BATCH,
) {
    /** The link a run used, so a rejection can clear exactly that link. */
    var lastLink: ServerLink? = null
        private set

    suspend fun run(limit: Int = BATCH): UploadRun = withContext(io) {
        val server = link()
        lastLink = server
        if (server == null) return@withContext UploadRun(0, 0, 0, UploadStop.NOT_LINKED)
        val since = uploadSinceEpochMs()
        planClips(since)
        var received = 0
        var failed = 0
        var stop: UploadStop? = null
        val audio = AudioCache(store, segments)
        for (clip in clips.pending(since, limit)) {
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
        for (segment in clips.fullyUploaded()) store.markUploaded(segment)
        val remaining = clips.pendingCount(since) + clips.unplannedCount(since)
        Log.i(TAG, "clip_upload_batch received=$received failed=$failed remaining=$remaining stop=$stop")
        UploadRun(received, failed, remaining, stop)
    }

    /**
     * Turns speech regions into frame-aligned clips with their final size and checksum. Every
     * segment leaves the queue with an outcome, so none can block the ones behind it.
     */
    private suspend fun planClips(since: Long) {
        for (segment in clips.unplanned(since, planBatch)) {
            currentCoroutineContext().ensureActive()
            val analysis = speech.analysis(segment.segmentId)
            if (analysis == null || analysis.regionCount == 0) {
                // No speech: nothing to decrypt or upload, so nothing to protect with a server
                // round trip. Acknowledge locally now so AudioRetention governs it (ADR-0017).
                clips.plan(ClipPlanEntity(segment.segmentId, NO_SPEECH, now()), emptyList())
                store.markTranscribed(segment, now())
                continue
            }
            val audio = store.readAudio(segment)
            if (audio == null) {
                Log.w(TAG, "clip_plan_unreadable")
                clips.plan(ClipPlanEntity(segment.segmentId, UNREADABLE, now()), emptyList())
                continue
            }
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
            val outcome = if (planned.isEmpty()) EMPTY else PLANNED
            clips.plan(ClipPlanEntity(segment.segmentId, outcome, now()), planned)
            // Same reasoning as NO_SPEECH: the planner found nothing worth uploading (ADR-0017).
            if (planned.isEmpty()) store.markTranscribed(segment, now())
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
        // A read can fail transiently (Keystore busy): retry within the budget.
        val (audio, frames) = cache.audio(segment) ?: return retry(clip, "audio_unreadable")
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
            when (state.state) {
                in DONE_STATES -> {
                    val serverId = state.uploadId
                    if (serverId != null && serverId != clip.clipId) {
                        // The server already had this clip under the id we lost: follow it, so
                        // the transcript (sealed for that id) matches this row.
                        clips.adopt(clip.clipId, serverId)
                    } else {
                        clips.update(clip.copy(state = RECEIVED, lastError = null))
                    }
                    Outcome.Received
                }
                // Processing failed for good on the server (or the transcript expired before
                // it was collected). The audio is still here: upload again under a new id.
                "failed" -> reissue(clip)
                else -> retry(clip, "state_${state.state}")
            }
        } catch (e: StorageException) {
            // Expired URL, clock skew, signature: never an account problem. Ask again later.
            retry(clip, "storage_${e.status}")
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
        e.code in RETRYABLE_CODES -> retry(clip, e.code)
        // Conflicts and validation errors won't change on retry: terminal, and shown.
        else -> fail(clip, e.code)
    }

    private suspend fun retry(clip: ClipUploadEntity, reason: String): Outcome {
        val attempts = clip.attempts + 1
        if (attempts >= MAX_ATTEMPTS) return fail(clip.copy(attempts = attempts), reason)
        clips.update(clip.copy(attempts = attempts, lastError = reason))
        return Outcome.Retry
    }

    private suspend fun reissue(clip: ClipUploadEntity): Outcome {
        if (clip.attempts + 1 >= MAX_ATTEMPTS) return fail(clip, "server_failed")
        clips.reissue(clip.clipId, ids.next().toString())
        return Outcome.Retry
    }

    private suspend fun fail(clip: ClipUploadEntity, reason: String): Outcome {
        Log.e(TAG, "clip_upload_failed reason=$reason")
        clips.update(clip.copy(state = FAILED, lastError = reason))
        return Outcome.Failed
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
        const val PLANNED = "PLANNED"
        const val NO_SPEECH = "NO_SPEECH"
        const val UNREADABLE = "UNREADABLE"
        const val EMPTY = "EMPTY"
        const val MAX_ATTEMPTS = 5
        private const val BATCH = 100
        private const val PLAN_BATCH = 50
        private const val SECONDS_PER_MINUTE = 60
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER_ERROR = 500
        private val DONE_STATES = setOf("received", "transcribed", "delivered")

        /**
         * Integrity mismatches, a concurrent create, and a device key not registered yet all
         * settle on a later attempt.
         */
        private val RETRYABLE_CODES = setOf(
            "upload_integrity_failed",
            "upload_missing",
            "conflict",
            "device_key_required",
        )

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
