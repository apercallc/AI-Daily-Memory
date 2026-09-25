package com.apercallc.aidailymemory.upload

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.capture.SegmentCipher
import com.apercallc.aidailymemory.capture.SegmentFormat
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.data.SegmentSpeechEntity
import com.apercallc.aidailymemory.data.SpeechRegionEntity
import com.apercallc.aidailymemory.net.ApiException
import com.apercallc.aidailymemory.net.ClipRequest
import com.apercallc.aidailymemory.net.ServerLink
import com.apercallc.aidailymemory.net.StorageException
import com.apercallc.aidailymemory.net.UploadApi
import com.apercallc.aidailymemory.net.UploadState
import com.apercallc.aidailymemory.net.UploadTarget
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.audio.AdtsHeaderWriter
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.segment.SegmentState
import com.apercallc.aidailymemory.recording.speech.EnergyVad
import com.apercallc.aidailymemory.recording.upload.UploadStop
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.time.OffsetDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ClipUploaderTest {
    private lateinit var db: CaptureDatabase
    private lateinit var directory: File
    private lateinit var store: SegmentStore
    private val format = SegmentFormat("aac-lc-adts", 16_000, 1)
    private val link = ServerLink("https://api.example", "adm_" + "t".repeat(40))
    private var linked: ServerLink? = link
    private val api = FakeApi()

    /** Scripted server: one response per call, default "accept everything". */
    private class FakeApi : UploadApi {
        val creates = mutableListOf<ClipRequest>()
        val puts = mutableListOf<ByteArray>()
        val completes = mutableListOf<String>()
        var onCreate: (ClipRequest) -> UploadState = {
            UploadState("pending", UploadTarget("https://store.example/${it.uploadId}", mapOf("x" to "y")))
        }
        var onPut: () -> Unit = {}
        var onComplete: (String) -> UploadState = { UploadState("received", null) }

        override fun create(link: ServerLink, clip: ClipRequest): UploadState = onCreate(clip).also { creates += clip }

        override fun put(target: UploadTarget, bytes: ByteArray) {
            onPut()
            puts += bytes
        }

        override fun complete(link: ServerLink, uploadId: String): UploadState =
            onComplete(uploadId).also { completes += uploadId }

        override fun status(link: ServerLink, uploadId: String): String? = "received"
    }

    private var since = 0L

    private fun uploader(planBatch: Int = 50) = ClipUploader(
        store = store,
        segments = db.segments(),
        clips = db.clips(),
        speech = db.speech(),
        api = api,
        link = { linked },
        uploadSinceEpochMs = { since },
        ids = UuidV7Generator(),
        io = Dispatchers.Unconfined,
        planBatch = planBatch,
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java).allowMainThreadQueries().build()
        directory = File(context.cacheDir, "upload-test").apply { deleteRecursively() }
        StreamingAeadConfig.register()
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
    }

    @After
    fun tearDown() {
        db.close()
        directory.deleteRecursively()
    }

    /** 100 frames (6.4 s) with speech regions [regions] in ms, analyzed OK. */
    private suspend fun analyzed(vararg regions: Pair<Long, Long>): SegmentEntity {
        val open = store.begin(SessionId(UuidV7Generator().next().toString()), false, format)
        repeat(100) { i -> open.writeFrame(ByteBuffer.wrap(ByteArray(40) { (it + i).toByte() }), 40) }
        val segment = store.seal(open)
        db.speech().save(
            SegmentSpeechEntity(segment.segmentId, EnergyVad.VERSION, "OK", 6_400, 1_000, 2_000, regions.size, 0),
            regions.map { (start, end) -> SpeechRegionEntity(segment.segmentId, start, end, end - start, false) },
        )
        return segment
    }

    private suspend fun stateOf(segment: SegmentEntity) = db.segments().get(segment.segmentId)?.state

    @Test
    fun `nothing happens until the phone is linked`() = runTest {
        analyzed(0L to 1_000L)
        linked = null

        val run = uploader().run()

        assertEquals(UploadStop.NOT_LINKED, run.stoppedBy)
        assertTrue(api.creates.isEmpty())
    }

    @Test
    fun `speech regions become frame-aligned clips that upload and complete`() = runTest {
        val segment = analyzed(100L to 700L, 3_000L to 4_000L)

        val run = uploader().run()

        assertEquals(UploadRun(received = 2, failed = 0, remaining = 0, stoppedBy = null), run)
        val first = api.creates.first()
        assertEquals(segment.segmentId, first.segmentId)
        assertEquals(segment.sessionId, first.sessionId)
        assertEquals(64, first.offsetMs) // frame 1 starts at 64 ms
        assertEquals(640, first.durationMs) // frames 1..10
        assertEquals(10L * 47, first.byteSize) // 7-byte header + 40-byte payload per frame
        assertEquals(first.byteSize, api.puts.first().size.toLong())
        assertEquals(ClipUploader.sha256(api.puts.first()), first.sha256)
        assertEquals("aac-lc-adts", first.codec)
        // An ISO time with offset: the server rejects ambiguous times.
        OffsetDateTime.parse(first.capturedAt)
        assertEquals(listOf(first.uploadId, api.creates[1].uploadId), api.completes)
        assertEquals(SegmentState.UPLOADED.name, stateOf(segment))
    }

    @Test
    fun `a clip the server already has is marked received without uploading again`() = runTest {
        val segment = analyzed(0L to 500L)
        api.onCreate = { UploadState("received", null) }

        uploader().run()

        assertTrue(api.puts.isEmpty())
        assertEquals(ClipUploader.RECEIVED, db.clips().forSegment(segment.segmentId).single().state)
    }

    @Test
    fun `a segment with no speech leaves the queue without upload and is not called uploaded`() = runTest {
        val segment = analyzed()

        val run = uploader().run()

        assertTrue(api.creates.isEmpty())
        assertEquals(0, run.remaining)
        assertEquals(ClipUploader.NO_SPEECH, db.clips().planFor(segment.segmentId)?.outcome)
        // "No speech detected" is not "transcript stored": the audio isn't released.
        assertEquals(SegmentState.SEALED.name, stateOf(segment))
    }

    @Test
    fun `a storage 403 is retried and never treated as a signed-out account`() = runTest {
        val segment = analyzed(0L to 500L)
        api.onPut = { throw StorageException(403) }

        val run = uploader().run()

        assertEquals(null, run.stoppedBy)
        val clip = db.clips().forSegment(segment.segmentId).single()
        assertEquals(ClipUploader.PENDING, clip.state)
        assertEquals("storage_403", clip.lastError)
    }

    @Test
    fun `audio recorded before the account was linked stays on the phone unless included`() = runTest {
        val before = analyzed(0L to 500L)
        since = System.currentTimeMillis() + 60_000

        val run = uploader().run()

        assertTrue(api.creates.isEmpty())
        assertEquals(0, run.remaining)
        assertEquals(null, db.clips().planFor(before.segmentId))

        since = 0 // the user chose to include what was already recorded
        uploader().run()
        assertEquals(1, api.creates.size)
    }

    @Test
    fun `clips planned under an earlier consent never upload under a later link`() = runTest {
        analyzed(0L to 500L)
        api.onCreate = { throw IOException("offline") }
        uploader().run() // planned with the backlog allowed, then went offline
        api.onCreate = { UploadState("received", null) }
        since = System.currentTimeMillis() + 60_000 // reconnected without the backlog

        val run = uploader().run()

        assertEquals(0, run.received)
        assertEquals(0, run.remaining)
        assertTrue(api.creates.isEmpty()) // never sent under the new link
    }

    @Test
    fun `a backlog larger than one planning batch is counted as remaining`() = runTest {
        analyzed(0L to 500L)
        analyzed(0L to 500L)
        analyzed(0L to 500L)

        val run = uploader(planBatch = 1).run()

        assertEquals(1, run.received)
        assertEquals(2, run.remaining)
    }

    @Test
    fun `an unreadable segment leaves the queue, is shown, and can be retried`() = runTest {
        val broken = analyzed(0L to 500L)
        val fine = analyzed(0L to 500L)
        File(directory, broken.fileName).delete()

        uploader(planBatch = 1).run()
        uploader(planBatch = 1).run()

        assertEquals(ClipUploader.UNREADABLE, db.clips().planFor(broken.segmentId)?.outcome)
        assertEquals(SegmentState.UPLOADED.name, stateOf(fine))
        assertEquals(1, db.clips().observeProblems().first())

        db.clips().retryProblems()
        assertEquals(null, db.clips().planFor(broken.segmentId))
        assertEquals(0, db.clips().observeProblems().first())
    }

    @Test
    fun `when the server gave up on a clip, it is uploaded again under a new id`() = runTest {
        val segment = analyzed(0L to 500L)
        api.onCreate = { UploadState("failed", null) }
        uploader().run()
        val first = api.creates.single().uploadId

        api.onCreate = { UploadState("pending", UploadTarget("https://s/${it.uploadId}", emptyMap())) }
        uploader().run()

        val second = api.creates.last().uploadId
        assertTrue(first != second)
        assertEquals(ClipUploader.RECEIVED, db.clips().forSegment(segment.segmentId).single().state)
    }

    @Test
    fun `planning twice keeps the first plan and its ids`() = runTest {
        val segment = analyzed(0L to 500L)
        api.onCreate = { throw IOException("offline") }
        uploader().run()
        val planned = db.clips().forSegment(segment.segmentId)

        uploader().run()

        assertEquals(planned, db.clips().forSegment(segment.segmentId))
    }

    @Test
    fun `going offline stops the run and keeps the clip pending`() = runTest {
        val segment = analyzed(0L to 500L, 2_000L to 2_500L)
        api.onPut = { throw IOException("connection reset") }

        val run = uploader().run()

        assertEquals(UploadStop.OFFLINE, run.stoppedBy)
        assertEquals(2, run.remaining)
        val clip = db.clips().forSegment(segment.segmentId).first()
        assertEquals(ClipUploader.PENDING, clip.state)
        assertEquals(0, clip.attempts)
        assertEquals(SegmentState.SEALED.name, stateOf(segment))
    }

    @Test
    fun `server-side stops map to their reasons`() = runTest {
        analyzed(0L to 500L)
        val cases = listOf(
            401 to UploadStop.UNAUTHORIZED,
            403 to UploadStop.UNAUTHORIZED,
            429 to UploadStop.QUOTA,
            503 to UploadStop.SERVER,
        )
        for ((status, reason) in cases) {
            api.onCreate = { throw ApiException(status, "x") }
            assertEquals(reason, uploader().run().stoppedBy)
        }
    }

    @Test
    fun `integrity failures retry, then give up visibly`() = runTest {
        val segment = analyzed(0L to 500L)
        api.onComplete = { throw ApiException(409, "upload_integrity_failed") }

        repeat(ClipUploader.MAX_ATTEMPTS - 1) {
            uploader().run()
            assertEquals(ClipUploader.PENDING, db.clips().forSegment(segment.segmentId).single().state)
        }
        val last = uploader().run()

        assertEquals(1, last.failed)
        val clip = db.clips().forSegment(segment.segmentId).single()
        assertEquals(ClipUploader.FAILED, clip.state)
        assertEquals("upload_integrity_failed", clip.lastError)
    }

    @Test
    fun `an unexpected server state is retried`() = runTest {
        val segment = analyzed(0L to 500L)
        api.onComplete = { UploadState("pending", null) }

        uploader().run()

        val clip = db.clips().forSegment(segment.segmentId).single()
        assertEquals(ClipUploader.PENDING, clip.state)
        assertEquals("state_pending", clip.lastError)
    }

    @Test
    fun `conflicts are terminal and never retried`() = runTest {
        val segment = analyzed(0L to 500L)
        api.onCreate = { throw ApiException(409, "idempotency_conflict") }

        val run = uploader().run()

        assertEquals(1, run.failed)
        assertEquals(ClipUploader.FAILED, db.clips().forSegment(segment.segmentId).single().state)
        assertEquals(0, uploader().run().failed) // nothing pending any more
    }

    @Test
    fun `audio that can't be read after planning is retried, then shown, never uploaded`() = runTest {
        val segment = analyzed(0L to 500L)
        api.onCreate = { throw IOException("offline") }
        uploader().run() // plans, then stops
        File(directory, segment.fileName).delete()
        api.onCreate = { UploadState("pending", UploadTarget("https://s", emptyMap())) }

        uploader().run()
        assertEquals(ClipUploader.PENDING, db.clips().forSegment(segment.segmentId).single().state)
        repeat(ClipUploader.MAX_ATTEMPTS) { uploader().run() }

        val clip = db.clips().forSegment(segment.segmentId).single()
        assertEquals(ClipUploader.FAILED, clip.state)
        assertEquals("audio_unreadable", clip.lastError)
        assertTrue(api.puts.isEmpty())
        assertEquals(1, db.clips().observeProblems().first())
    }

    @Test
    fun `frame offsets include an end sentinel`() {
        val header = ByteArray(7)
        AdtsHeaderWriter(16_000, 1).write(3, header)
        val stream = header + byteArrayOf(1, 2, 3) + header + byteArrayOf(4, 5, 6)

        val offsets = ClipUploader.frameOffsets(stream)

        assertEquals(listOf(0, 10, 20), offsets.toList())
        assertEquals(listOf<Byte>(4, 5, 6), ClipUploader.slice(stream, offsets, 1, 2).takeLast(3))
    }
}
