package com.apercallc.aidailymemory.analysis

import android.content.ContentResolver
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.capture.SegmentCipher
import com.apercallc.aidailymemory.capture.SegmentFormat
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.data.SessionInfoEntity
import com.apercallc.aidailymemory.platform.DeviceClock
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.audio.AdtsScanner
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.speech.EnergyVad
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin
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
class SpeechAnalyzerTest {
    private lateinit var db: CaptureDatabase
    private lateinit var directory: File
    private lateinit var store: SegmentStore
    private lateinit var cipher: SegmentCipher
    private val format = SegmentFormat("aac-lc-adts", 16_000, 1)
    private var calls = 0

    /** Stands in for MediaCodec: quiet for the first second, then a voice-level tone. */
    private var decoder = PcmDecoder { adts, rate, _, sink ->
        val frames = AdtsScanner.scan(adts).completeFrames
        val samples = frames * 1024
        val pcm = ShortArray(samples) { i ->
            if (i < rate) {
                (if (i % 2 == 0) 30 else -30).toShort()
            } else {
                (3_000 * sin(2 * PI * 220 * i / rate)).toInt().toShort()
            }
        }
        // Delivered in decoder-sized chunks, as MediaCodec would.
        var offset = 0
        while (offset < samples) {
            val n = minOf(2_048, samples - offset)
            sink.onPcm(pcm.copyOfRange(offset, offset + n), n)
            offset += n
        }
    }

    private fun analyzer() = SpeechAnalyzer(store, db.speech(), { a, r, c, s -> decoder.decode(a, r, c, s) }, now = { 42L })

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        directory = File(context.cacheDir, "speech-test").apply { deleteRecursively() }
        StreamingAeadConfig.register()
        cipher = SegmentCipher(
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

    /** A sealed segment of [frames] AAC frames (64 ms each at 16 kHz). */
    private suspend fun sealed(session: String = "s1", frames: Int = 100): SegmentEntity {
        val open = store.begin(SessionId(session), continuesPrevious = false, format)
        repeat(frames) { open.writeFrame(ByteBuffer.wrap(ByteArray(50) { 1 }), 50) }
        return store.seal(open)
    }

    @Test
    fun `speech regions and totals are stored for each sealed segment`() = runTest {
        val segment = sealed() // 6.4 s: 1 s quiet, then voice

        val run = analyzer().analyzePending()

        assertEquals(AnalysisRun(analyzed = 1, failed = 0, remaining = 0), run)
        val analysis = db.speech().analysis(segment.segmentId)!!
        assertEquals(SpeechOutcome.OK.name, analysis.outcome)
        assertEquals(EnergyVad.VERSION, analysis.analyzer)
        assertEquals(6_400, analysis.totalMs)
        assertTrue(analysis.speechMs in 5_200..5_400, "speech ${analysis.speechMs}")
        val region = db.speech().regions(segment.segmentId).single()
        assertTrue(region.startMs in 0..100, "pre-roll reaches back into the quiet part")
        assertEquals(6_400, region.endMs)
        assertEquals(42, analysis.analyzedAtEpochMs)
    }

    @Test
    fun `analyzed segments are not analyzed again`() = runTest {
        sealed()
        analyzer().analyzePending()

        assertEquals(AnalysisRun(0, 0, 0), analyzer().analyzePending())
    }

    @Test
    fun `a batch limit leaves the rest pending for the next run`() = runTest {
        repeat(3) { sealed() }

        assertEquals(AnalysisRun(analyzed = 2, failed = 0, remaining = 1), analyzer().analyzePending(limit = 2))
        assertEquals(AnalysisRun(analyzed = 1, failed = 0, remaining = 0), analyzer().analyzePending(limit = 2))
    }

    @Test
    fun `transient keystore unavailability leaves analysis pending for retry`() = runTest {
        val segment = sealed()
        val available = cipher
        cipher = SegmentCipher(UnavailableStreamingAead)

        assertEquals(AnalysisRun(analyzed = 0, failed = 0, remaining = 1), analyzer().analyzePending())
        assertEquals(null, db.speech().analysis(segment.segmentId))

        cipher = available
        assertEquals(AnalysisRun(analyzed = 1, failed = 0, remaining = 0), analyzer().analyzePending())
        assertEquals(SpeechOutcome.OK.name, db.speech().analysis(segment.segmentId)?.outcome)
    }

    private object UnavailableStreamingAead : StreamingAead {
        private fun down(): Nothing = throw java.security.ProviderException("keystore busy")

        override fun newEncryptingChannel(
            ciphertextDestination: java.nio.channels.WritableByteChannel,
            associatedData: ByteArray,
        ) = down()

        override fun newSeekableDecryptingChannel(
            ciphertextSource: java.nio.channels.SeekableByteChannel,
            associatedData: ByteArray,
        ) = down()

        override fun newDecryptingChannel(
            ciphertextSource: java.nio.channels.ReadableByteChannel,
            associatedData: ByteArray,
        ) = down()

        override fun newEncryptingStream(
            ciphertextDestination: java.io.OutputStream,
            associatedData: ByteArray,
        ) = down()

        override fun newDecryptingStream(
            ciphertextSource: java.io.InputStream,
            associatedData: ByteArray,
        ) = down()
    }

    @Test
    fun `a missing file is recorded as unreadable once, not retried every night`() = runTest {
        val segment = sealed()
        File(directory, segment.fileName).delete()

        assertEquals(AnalysisRun(analyzed = 0, failed = 1, remaining = 0), analyzer().analyzePending())
        assertEquals(SpeechOutcome.UNREADABLE.name, db.speech().analysis(segment.segmentId)?.outcome)
        assertEquals(AnalysisRun(0, 0, 0), analyzer().analyzePending())
    }

    @Test
    fun `a tampered file is unreadable, never analyzed as audio`() = runTest {
        val segment = sealed()
        val file = File(directory, segment.fileName)
        val bytes = file.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        file.writeBytes(bytes)

        analyzer().analyzePending()

        assertEquals(SpeechOutcome.UNREADABLE.name, db.speech().analysis(segment.segmentId)?.outcome)
    }

    @Test
    fun `a decoder failure is recorded and does not stop the batch`() = runTest {
        val bad = sealed()
        val good = sealed()
        val real = decoder
        decoder = PcmDecoder { adts, rate, channels, sink ->
            if (adts.isNotEmpty() && calls++ == 0) throw IOException("codec") else real.decode(adts, rate, channels, sink)
        }

        val run = analyzer().analyzePending()

        assertEquals(AnalysisRun(analyzed = 1, failed = 1, remaining = 0), run)
        assertEquals(SpeechOutcome.UNDECODABLE.name, db.speech().analysis(bad.segmentId)?.outcome)
        assertEquals(SpeechOutcome.OK.name, db.speech().analysis(good.segmentId)?.outcome)
    }

    @Test
    fun `a new analyzer version replaces the old result and its regions`() = runTest {
        val segment = sealed()
        analyzer().analyzePending()
        db.speech().save(
            db.speech().analysis(segment.segmentId)!!.copy(analyzer = "older"),
            db.speech().regions(segment.segmentId),
        )

        assertEquals(1, analyzer().analyzePending().analyzed)
        assertEquals(EnergyVad.VERSION, db.speech().analysis(segment.segmentId)?.analyzer)
        assertEquals(1, db.speech().regions(segment.segmentId).size)
    }

    @Test
    fun `the sessions list and cost-model totals include analyzed speech`() = runTest {
        db.sessions().register(SessionInfoEntity("s1", "2026-09-22", "UTC", 0, null))
        sealed("s1")
        sealed("s1")
        analyzer().analyzePending()

        val row = db.sessions().observeSince("2026-09-01").first().single()
        assertEquals(12_800, row.capturedMs)
        assertEquals(12_800, row.analyzedMs)
        assertTrue(row.speechMs > 10_000)
        val totals = db.speech().totalsSince(0)
        assertEquals(12_800, totals.analyzedMs)
        assertEquals(row.speechMs, totals.speechMs)
        assertTrue(totals.paddedMs >= totals.speechMs)
    }
}
