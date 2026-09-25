package com.apercallc.aidailymemory.upload

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.capture.SegmentCipher
import com.apercallc.aidailymemory.capture.SegmentFormat
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.ClipPlanEntity
import com.apercallc.aidailymemory.data.ClipUploadEntity
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.data.TranscriptCipher
import com.apercallc.aidailymemory.net.ApiException
import com.apercallc.aidailymemory.net.ClipRequest
import com.apercallc.aidailymemory.net.DeliveryApi
import com.apercallc.aidailymemory.net.DeviceKey
import com.apercallc.aidailymemory.net.SealedDelivery
import com.apercallc.aidailymemory.net.ServerLink
import com.apercallc.aidailymemory.net.UploadApi
import com.apercallc.aidailymemory.net.UploadState
import com.apercallc.aidailymemory.net.UploadTarget
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.segment.SegmentState
import com.apercallc.aidailymemory.recording.upload.UploadStop
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class DeliveryReceiverTest {
    private lateinit var db: CaptureDatabase
    private lateinit var directory: File
    private lateinit var store: SegmentStore
    private lateinit var handle: KeysetHandle
    private lateinit var seal: HybridEncrypt
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("delivery-test", Context.MODE_PRIVATE)
    private var link: ServerLink? = ServerLink("https://api.example", "adm_" + "d".repeat(40))
    private val server = FakeServer()
    private val cipher = TranscriptCipher {
        com.google.crypto.tink.aead.AeadConfig.register()
        aeadKey
    }
    private val aeadKey by lazy {
        com.google.crypto.tink.aead.AeadConfig.register()
        KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(RegistryConfiguration.get(), com.google.crypto.tink.Aead::class.java)
    }

    private suspend fun lines(sessionId: String) =
        db.transcripts().observeSession(sessionId).first().mapNotNull(cipher::open)

    /** Holds sealed deliveries until acknowledged, like backend/app/api/deliveries.py. */
    private class FakeServer : DeliveryApi {
        val registered = mutableListOf<ByteArray>()
        val waiting = mutableListOf<SealedDelivery>()
        val acked = mutableListOf<String>()
        var fail: (() -> Unit)? = null

        override fun registerKey(link: ServerLink, publicKey: ByteArray) {
            fail?.invoke()
            registered += publicKey
        }

        override fun deliveries(link: ServerLink, limit: Int): List<SealedDelivery> {
            fail?.invoke()
            return waiting.take(limit)
        }

        override fun acknowledge(link: ServerLink, deliveryIds: List<String>): Int {
            acked += deliveryIds
            waiting.removeAll { it.deliveryId in deliveryIds }
            return deliveryIds.size
        }
    }

    /** What the server says about each upload id when the phone checks (null = unknown). */
    private val statuses = mutableMapOf<String, String?>()

    private val uploadApi = object : UploadApi {
        override fun create(link: ServerLink, clip: ClipRequest): UploadState = error("not used")

        override fun put(target: UploadTarget, bytes: ByteArray) = error("not used")

        override fun complete(link: ServerLink, uploadId: String): UploadState = error("not used")

        override fun status(link: ServerLink, uploadId: String): String? {
            server.fail?.invoke()
            return statuses.getOrDefault(uploadId, "transcribed")
        }
    }

    private fun receiver() = DeliveryReceiver(
        link = { link },
        api = server,
        uploads = uploadApi,
        clips = db.clips(),
        ids = UuidV7Generator(),
        key = { DeviceKey(handle) },
        prefs = prefs,
        transcripts = db.transcripts(),
        cipher = cipher,
        store = store,
        now = { 1_000L },
        io = Dispatchers.Unconfined,
    )

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        directory = File(context.cacheDir, "delivery-test").apply { deleteRecursively() }
        StreamingAeadConfig.register()
        HybridConfig.register()
        val cipher = SegmentCipher(
            KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
                .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java),
        )
        store = SegmentStore(
            directory = directory,
            dao = db.segments(),
            cipher = { cipher },
            lifecycle = SegmentLifecycle(recoveryWindowMs = 0),
            ids = UuidV7Generator(),
            clock = DeviceClock(context.contentResolver as ContentResolver),
        )
        handle = KeysetHandle.generateNew(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"))
        seal = handle.publicKeysetHandle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
    }

    @After
    fun tearDown() {
        db.close()
        directory.deleteRecursively()
    }

    /** An UPLOADED segment with planned clips (ids returned). */
    private suspend fun uploadedSegment(clips: Int): Pair<SegmentEntity, List<String>> {
        val open = store.begin(SessionId(UuidV7Generator().next().toString()), false, SegmentFormat("aac-lc-adts", 16_000, 1))
        repeat(10) { open.writeFrame(ByteBuffer.wrap(ByteArray(20)), 20) }
        val segment = store.seal(open)
        val ids = (0 until clips).map { UuidV7Generator().next().toString() }
        db.clips().plan(
            ClipPlanEntity(segment.segmentId, ClipUploader.PLANNED, 0),
            ids.mapIndexed { i, id ->
                ClipUploadEntity(id, segment.segmentId, i, i + 1, i * 64L, 64, 47, "0".repeat(64), ClipUploader.RECEIVED, 0, null)
            },
        )
        return store.markUploaded(segment) to ids
    }

    private suspend fun payload(uploadId: String, segmentId: String, version: Int = 1, offsetMs: Long? = null): ByteArray = JSONObject()
        .put("v", version)
        .put("upload_id", uploadId)
        .put("segment_id", segmentId)
        .put("offset_ms", offsetMs ?: db.clips().byId(uploadId)?.offsetMs ?: 0)
        .put("captured_at", "2026-09-25T14:03:00+02:00")
        .put("provider", "fake")
        .put("model", "fake-1")
        .put("language", "en")
        .put(
            "spans",
            JSONArray()
                .put(JSONObject().put("start_ms", 0).put("end_ms", 900).put("text", "Let's ship Friday.").put("speaker", "A").put("confidence", 0.9))
                .put(JSONObject().put("start_ms", 1_000).put("end_ms", 1_500).put("text", "I'll send the deck.").put("speaker", JSONObject.NULL)),
        )
        .toString()
        .toByteArray()

    private fun deliver(uploadId: String, plaintext: ByteArray, sealedFor: String = uploadId) {
        val ciphertext = seal.encrypt(plaintext, (DeviceKey.INFO_PREFIX + sealedFor).toByteArray())
        server.waiting += SealedDelivery("d-$uploadId", uploadId, ciphertext)
    }

    @Test
    fun `the device key is registered once per token and again for a new one`() = runTest {
        receiver().run()
        receiver().run()
        assertEquals(1, server.registered.size)
        assertEquals(DeviceKey(handle).publicKey.toList(), server.registered.single().toList())

        link = ServerLink("https://api.example", "adm_" + "e".repeat(40))
        receiver().run()

        assertEquals(2, server.registered.size)
    }

    @Test
    fun `transcripts are opened, stored as evidence, acknowledged, and release their segment`() = runTest {
        val (segment, ids) = uploadedSegment(clips = 2)
        deliver(ids[0], payload(ids[0], segment.segmentId))

        val first = receiver().run()

        assertEquals(DeliveryRun(stored = 1, unreadable = 0, stoppedBy = null), first)
        assertEquals(listOf("d-${ids[0]}"), server.acked)
        // One clip still missing its transcript: the audio is not released yet.
        assertEquals(SegmentState.UPLOADED.name, db.segments().get(segment.segmentId)?.state)
        assertEquals(1, db.transcripts().awaitingCount())

        deliver(ids[1], payload(ids[1], segment.segmentId))
        receiver().run()

        assertEquals(SegmentState.ACKNOWLEDGED.name, db.segments().get(segment.segmentId)?.state)
        assertEquals(0, db.transcripts().awaitingCount())
        val lines = lines(segment.sessionId)
        assertEquals(4, lines.size)
        assertEquals("Let's ship Friday.", lines.first().text)
        assertEquals("A", lines.first().speaker)
        assertNull(lines.first { it.text == "I'll send the deck." }.speaker)
        assertEquals(segment.start.occurredAtEpochMs, lines.first().startEpochMs) // first clip at offset 0
        // Stored sealed: the words are not in the database in the clear.
        val raw = db.transcripts().observeSession(segment.sessionId).first()
        assertTrue(raw.none { String(it.sealed, Charsets.ISO_8859_1).contains("Friday") })
    }

    @Test
    fun `a redelivered transcript changes nothing`() = runTest {
        val (segment, ids) = uploadedSegment(clips = 1)
        deliver(ids[0], payload(ids[0], segment.segmentId))
        receiver().run()
        deliver(ids[0], payload(ids[0], segment.segmentId))

        receiver().run()

        assertEquals(2, lines(segment.sessionId).size)
    }

    @Test
    fun `anything that can't be opened or trusted is never acknowledged`() = runTest {
        val (segment, ids) = uploadedSegment(clips = 3)
        deliver(ids[0], payload(ids[0], segment.segmentId), sealedFor = "someone-else") // wrong binding
        deliver(ids[1], payload(ids[2], segment.segmentId)) // payload claims another upload
        deliver(ids[2], payload(ids[2], segment.segmentId, version = 2)) // unknown version

        val run = receiver().run()

        assertEquals(DeliveryRun(stored = 0, unreadable = 3, stoppedBy = null), run)
        assertTrue(server.acked.isEmpty())
        assertEquals(SegmentState.UPLOADED.name, db.segments().get(segment.segmentId)?.state)
    }

    @Test
    fun `not linked, signed out, and offline each stop cleanly`() = runTest {
        link = null
        assertEquals(UploadStop.NOT_LINKED, receiver().run().stoppedBy)

        link = ServerLink("https://api.example", "adm_" + "d".repeat(40))
        server.fail = { throw ApiException(401, "unauthorized") }
        assertEquals(UploadStop.UNAUTHORIZED, receiver().run().stoppedBy)

        server.fail = { throw ApiException(503, "processing_unavailable") }
        assertEquals(UploadStop.SERVER, receiver().run().stoppedBy)

        server.fail = { throw ApiException(429, "quota_exceeded") }
        assertEquals(UploadStop.QUOTA, receiver().run().stoppedBy)

        server.fail = { throw IOException("offline") }
        assertEquals(UploadStop.OFFLINE, receiver().run().stoppedBy)
    }

    @Test
    fun `failed clips are queued again, unknown ones are shown, others wait`() = runTest {
        val (segment, ids) = uploadedSegment(clips = 4)
        statuses[ids[0]] = "failed"
        statuses[ids[1]] = null
        statuses[ids[2]] = "delivered"
        statuses[ids[3]] = "received"

        receiver().run()

        val clips = db.clips().forSegment(segment.segmentId)
        assertEquals(ClipUploader.PENDING, clips[0].state)
        assertTrue(clips[0].clipId != ids[0]) // new upload id
        assertEquals(listOf("FAILED" to "unknown_to_server", "FAILED" to "delivered_elsewhere"), clips.subList(1, 3).map { it.state to it.lastError })
        assertEquals(ClipUploader.RECEIVED, clips[3].state)
    }

    @Test
    fun `requeueing stops after the retry budget and is shown instead`() = runTest {
        val (segment, ids) = uploadedSegment(clips = 1)
        db.clips().update(db.clips().byId(ids[0])!!.copy(attempts = ClipUploader.MAX_ATTEMPTS - 1))
        statuses[ids[0]] = "failed"

        receiver().run()

        val clip = db.clips().forSegment(segment.segmentId).single()
        assertEquals(ClipUploader.FAILED, clip.state)
        assertEquals("server_gave_up", clip.lastError)
    }

    @Test
    fun `a payload whose placement disagrees with the local clip is not stored`() = runTest {
        val (segment, ids) = uploadedSegment(clips = 1)
        deliver(ids[0], payload(ids[0], segment.segmentId, offsetMs = 999))

        assertEquals(DeliveryRun(0, 1, null), receiver().run())
        assertTrue(server.acked.isEmpty())
    }

    @Test
    fun `a payload with a missing field is rejected, not a crash`() = runTest {
        val (_, ids) = uploadedSegment(clips = 1)
        deliver(ids[0], JSONObject().put("v", 1).put("upload_id", ids[0]).put("spans", JSONArray()).toString().toByteArray())

        assertEquals(DeliveryRun(0, 1, null), receiver().run())
    }

    @Test
    fun `registration problems stop uploads with the right reason`() = runTest {
        val registered = link!!
        assertNull(receiver().registrationStop(registered))
        prefs.edit().clear().commit()
        server.fail = { throw ApiException(409, "device_key_mismatch") }
        assertEquals(UploadStop.UNAUTHORIZED, receiver().registrationStop(registered))
        server.fail = { throw IOException("offline") }
        assertEquals(UploadStop.OFFLINE, receiver().registrationStop(registered))
    }

    @Test
    fun `a token that already has another key means reconnect`() = runTest {
        server.fail = { throw ApiException(409, "device_key_mismatch") }

        assertEquals(UploadStop.UNAUTHORIZED, receiver().run().stoppedBy)
    }

    @Test
    fun `payload validation rejects malformed evidence`() {
        assertFailsWith<IllegalArgumentException> { TranscriptPayload.parse("not json".toByteArray(), "u") }
        val backwards = JSONObject()
            .put("v", 1).put("upload_id", "u").put("segment_id", "s").put("offset_ms", 0)
            .put("provider", "p").put("model", "m")
            .put("spans", JSONArray().put(JSONObject().put("start_ms", 10).put("end_ms", 5).put("text", "x")))
        assertFailsWith<IllegalArgumentException> { TranscriptPayload.parse(backwards.toString().toByteArray(), "u") }
        val noSpans = JSONObject().put("v", 1).put("upload_id", "u")
        assertFailsWith<IllegalArgumentException> { TranscriptPayload.parse(noSpans.toString().toByteArray(), "u") }
    }
}
