package com.apercallc.aidailymemory.upload

import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.ClipDao
import com.apercallc.aidailymemory.data.TranscriptDao
import com.apercallc.aidailymemory.data.TranscriptEntity
import com.apercallc.aidailymemory.data.TranscriptSpanEntity
import com.apercallc.aidailymemory.net.ApiException
import com.apercallc.aidailymemory.net.DeliveryApi
import com.apercallc.aidailymemory.net.DeviceKey
import com.apercallc.aidailymemory.net.ServerLink
import com.apercallc.aidailymemory.net.UploadApi
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.upload.UploadStop
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

data class DeliveryRun(val stored: Int, val unreadable: Int, val stoppedBy: UploadStop?)

/**
 * Collects transcripts sealed to this phone (ADR-0014): register the device key, fetch,
 * open, store as local evidence, then acknowledge so the server deletes its copy. Only after a
 * segment's every clip has its transcript here is the segment acknowledged, which is what lets
 * the audio-retention setting delete its audio.
 *
 * A delivery that can't be opened or parsed is never acknowledged: the server keeps it until
 * the hold expires, then the clip's audio (still on the phone) is uploaded again.
 */
class DeliveryReceiver(
    private val link: () -> ServerLink?,
    private val api: DeliveryApi,
    private val uploads: UploadApi,
    private val clips: ClipDao,
    private val ids: UuidV7Generator,
    private val key: () -> DeviceKey,
    private val prefs: SharedPreferences,
    private val transcripts: TranscriptDao,
    private val store: SegmentStore,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Registers this phone's key for [server] unless already registered for that token. */
    suspend fun ensureRegistered(server: ServerLink) = withContext(io) {
        val publicKey = key().publicKey
        val fingerprint = fingerprint(server, publicKey)
        if (prefs.getString(KEY_REGISTERED, null) == fingerprint) return@withContext
        api.registerKey(server, publicKey)
        prefs.edit { putString(KEY_REGISTERED, fingerprint) }
    }

    suspend fun run(maxPages: Int = MAX_PAGES): DeliveryRun = withContext(io) {
        val server = link() ?: return@withContext DeliveryRun(0, 0, UploadStop.NOT_LINKED)
        var stored = 0
        var unreadable = 0
        try {
            ensureRegistered(server)
            val deviceKey = key()
            for (pageNumber in 0 until maxPages) {
                currentCoroutineContext().ensureActive()
                val page = api.deliveries(server, PAGE)
                val opened = page.mapNotNull { delivery ->
                    val payload = try {
                        TranscriptPayload.parse(deviceKey.open(delivery.ciphertext, delivery.uploadId), delivery.uploadId)
                    } catch (e: GeneralSecurityException) {
                        logFailure(TAG, "delivery_unopenable", e, warning = true)
                        null
                    } catch (e: IllegalArgumentException) {
                        logFailure(TAG, "delivery_invalid", e, warning = true)
                        null
                    }
                    if (payload == null) {
                        unreadable++
                        return@mapNotNull null
                    }
                    transcripts.store(payload.transcript(now()), payload.spans)
                    delivery.deliveryId
                }
                if (opened.isNotEmpty()) api.acknowledge(server, opened)
                stored += opened.size
                if (page.size < PAGE) break
            }
        } catch (e: ApiException) {
            return@withContext DeliveryRun(stored, unreadable, stopFor(e))
        } catch (e: IOException) {
            Log.w(TAG, "delivery_network_error")
            return@withContext DeliveryRun(stored, unreadable, UploadStop.OFFLINE)
        } finally {
            acknowledgeSegments()
        }
        val stop = try {
            recheckAwaiting(server)
            null
        } catch (e: ApiException) {
            stopFor(e)
        } catch (e: IOException) {
            UploadStop.OFFLINE
        }
        DeliveryRun(stored, unreadable, stop)
    }

    /**
     * ADR-0014 "no silent loss": a clip the server accepted but whose transcript never came
     * (it failed, its sealed copy expired, or it belongs to an account this phone no longer
     * uses) is queued again. The audio is still on the phone, because only a stored
     * transcript releases it.
     */
    private suspend fun recheckAwaiting(server: ServerLink) {
        for (clip in clips.awaiting(RECHECK_BATCH)) {
            currentCoroutineContext().ensureActive()
            when (uploads.status(server, clip.clipId)) {
                null, "failed", "delivered" -> {
                    // "delivered" without a local transcript can only mean it went to a copy of
                    // this app that no longer exists: upload again rather than lose it.
                    Log.w(TAG, "clip_requeued_after_server_loss")
                    clips.requeue(clip.clipId, ids.next().toString())
                }
                else -> Unit // still being processed
            }
        }
    }

    private suspend fun acknowledgeSegments() {
        for (segment in transcripts.segmentsWithAllTranscripts()) store.markTranscribed(segment, now())
    }

    private fun stopFor(e: ApiException): UploadStop = when {
        e.status == HTTP_UNAUTHORIZED || e.status == HTTP_FORBIDDEN -> UploadStop.UNAUTHORIZED
        // The token already has a different key: this install can't prove it's that device.
        e.code == "device_key_mismatch" -> UploadStop.UNAUTHORIZED
        e.status == HTTP_TOO_MANY -> UploadStop.QUOTA
        else -> UploadStop.SERVER
    }

    private fun fingerprint(server: ServerLink, publicKey: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(server.baseUrl.toByteArray())
        digest.update(0)
        digest.update(server.token.toByteArray())
        digest.update(0)
        digest.update(publicKey)
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    companion object {
        private const val TAG = "DeliveryReceiver"
        private const val KEY_REGISTERED = "device_key_registered_for"
        private const val PAGE = 50
        private const val MAX_PAGES = 20
        private const val RECHECK_BATCH = 20
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_TOO_MANY = 429
    }
}

/** A delivered transcript, validated before it becomes evidence. */
internal data class TranscriptPayload(
    val uploadId: String,
    val segmentId: String,
    val offsetMs: Long,
    val provider: String,
    val model: String,
    val language: String?,
    val spans: List<TranscriptSpanEntity>,
) {
    fun transcript(receivedAtEpochMs: Long) =
        TranscriptEntity(uploadId, segmentId, offsetMs, provider, model, language, receivedAtEpochMs)

    companion object {
        private const val VERSION = 1
        private const val MAX_TEXT = 20_000

        /** @throws IllegalArgumentException for anything malformed, of another version, or for another upload. */
        fun parse(bytes: ByteArray, expectedUploadId: String): TranscriptPayload {
            val json = try {
                JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: JSONException) {
                throw IllegalArgumentException("not json", e)
            }
            require(json.optInt("v") == VERSION) { "unsupported payload version" }
            val uploadId = json.optString("upload_id")
            require(uploadId == expectedUploadId) { "payload for another upload" }
            val spansJson = json.optJSONArray("spans") ?: throw IllegalArgumentException("no spans")
            var previousStart = 0L
            val spans = (0 until spansJson.length()).map { i ->
                val span = spansJson.getJSONObject(i)
                val start = span.getLong("start_ms")
                val end = span.getLong("end_ms")
                val text = span.getString("text")
                require(start in previousStart..end) { "spans out of order" }
                require(text.length <= MAX_TEXT) { "span too long" }
                previousStart = start
                TranscriptSpanEntity(
                    uploadId = uploadId,
                    idx = i,
                    startMs = start,
                    endMs = end,
                    text = text,
                    speaker = span.optString("speaker").takeIf { span.has("speaker") && !span.isNull("speaker") },
                    confidence = span.optDouble("confidence").takeUnless { it.isNaN() },
                )
            }
            return TranscriptPayload(
                uploadId = uploadId,
                segmentId = json.getString("segment_id"),
                offsetMs = json.getLong("offset_ms").also { require(it >= 0) { "negative offset" } },
                provider = json.getString("provider"),
                model = json.getString("model"),
                language = json.optString("language").takeIf { json.has("language") && !json.isNull("language") },
                spans = spans,
            )
        }
    }
}
