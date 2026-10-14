package com.apercallc.aidailymemory.processing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.capture.SegmentCipher
import com.apercallc.aidailymemory.capture.SegmentFormat
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.CaptureTimeColumns
import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.ProcessingStateEntity
import com.apercallc.aidailymemory.data.SessionInfoEntity
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.segment.SegmentLifecycle
import com.apercallc.aidailymemory.recording.processing.ExtractionProvider
import com.apercallc.aidailymemory.recording.processing.ExtractionResult
import com.apercallc.aidailymemory.recording.processing.NumberedLine
import com.apercallc.aidailymemory.recording.processing.ProcessingException
import com.apercallc.aidailymemory.recording.processing.TranscriptionProvider
import com.apercallc.aidailymemory.recording.processing.TranscriptResult
import com.apercallc.aidailymemory.recording.processing.TranscribedSpan
import com.apercallc.aidailymemory.settings.ProcessingSettings
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ProcessingPipelineTest {
    private lateinit var database: CaptureDatabase
    private lateinit var settings: ProcessingSettings

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, CaptureDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        settings = ProcessingSettings(
            context.getSharedPreferences("processing-pipeline-test", Context.MODE_PRIVATE),
        ) { com.apercallc.aidailymemory.settings.testAead() }.apply {
            setProcessingEnabled(true)
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `saved transcript is extracted after audio was deleted`() = runTest {
        addTranscribedSession()
        var extracted: List<NumberedLine> = emptyList()

        val run = pipeline(object : ExtractionProvider {
            override val name = "test"
            override val model = "test-model"
            override suspend fun extract(lines: List<NumberedLine>): ExtractionResult {
                extracted = lines
                return ExtractionResult(emptyList())
            }
        }).processPending()

        assertEquals(ProcessingRun(processed = 1, failed = 0, remaining = 0), run)
        assertEquals("A saved transcript line", extracted.single().text)
        assertEquals("COMPLETED", database.processingState().forSession(SESSION_ID)?.state)
        assertFalse(database.dailyReports().observeRecent(1).first().isEmpty())
    }

    @Test
    fun `with summaries off the transcript is kept and the session completes as transcript only`() = runTest {
        addTranscribedSession()

        val run = pipeline(extractionProvider = null).processPending()

        assertEquals(ProcessingRun(processed = 1, failed = 0, remaining = 0), run)
        assertEquals("COMPLETED", database.processingState().forSession(SESSION_ID)?.state)
        assertTrue(database.memoryItems().forSession(SESSION_ID).isEmpty())
        val report = database.dailyReports().observeRecent(1).first().single()
        assertEquals("Transcript only", report.headline)
        assertEquals(ProcessingPipeline.TRANSCRIPT_ONLY, report.extractionModel)
    }

    @Test
    fun `a transcription provider is closed after the run so the speech model is released`() = runTest {
        addTranscribedSession()
        var closed = false
        val provider = object : TranscriptionProvider, AutoCloseable {
            override val name = "closing"
            override val model = "m"
            override suspend fun transcribe(audio: ByteArray, codec: String, sampleRateHz: Int): TranscriptResult =
                error("saved transcript should avoid transcription")
            override fun close() {
                closed = true
            }
        }

        pipeline(extractionProvider = null, transcriptionProvider = provider).processPending()

        assertTrue(closed)
    }

    @Test
    fun `retryable provider failure returns processing state to pending`() = runTest {
        addTranscribedSession()

        val run = pipeline(object : ExtractionProvider {
            override val name = "test"
            override val model = "test-model"
            override suspend fun extract(lines: List<NumberedLine>): ExtractionResult {
                throw ProcessingException("temporary_provider_error", retryable = true)
            }
        }).processPending()

        assertEquals(1, run.failed)
        assertEquals(1, run.remaining)
        assertEquals("PENDING", database.processingState().forSession(SESSION_ID)?.state)
    }

    @Test
    fun `summary quota failure keeps transcript and can recover as transcript only`() = runTest {
        addTranscribedSession()

        val failedRun = pipeline(object : ExtractionProvider {
            override val name = "test"
            override val model = "test-model"
            override suspend fun extract(lines: List<NumberedLine>): ExtractionResult {
                throw ProcessingException("provider_quota_exhausted", retryable = false)
            }
        }).processPending()

        assertEquals(1, failedRun.failed)
        assertEquals("FAILED", database.processingState().forSession(SESSION_ID)?.state)
        assertEquals("provider_quota_exhausted", database.processingState().forSession(SESSION_ID)?.lastError)
        assertEquals("A saved transcript line", database.localTranscripts().forSession(SESSION_ID).single().text)

        // Mirrors the explicit user choice to turn summaries off before pressing Retry.
        database.processingState().retryFailed(nowEpochMs = 43L)
        val retryRun = pipeline(extractionProvider = null).processPending()

        assertEquals(ProcessingRun(processed = 1, failed = 0, remaining = 0), retryRun)
        assertEquals("COMPLETED", database.processingState().forSession(SESSION_ID)?.state)
        assertEquals("A saved transcript line", database.localTranscripts().forSession(SESSION_ID).single().text)
        val report = database.dailyReports().observeRecent(1).first().single()
        assertEquals("Transcript only", report.headline)
        assertEquals(ProcessingPipeline.TRANSCRIPT_ONLY, report.extractionModel)
    }

    @Test
    fun `transcription provider failure leaves the encrypted audio available for retry`() = runTest {
        val (segmentStore, directory) = encryptedSegmentStore()
        val open = segmentStore.begin(
            SessionId(SESSION_ID),
            continuesPrevious = false,
            format = SegmentFormat("aac-lc-adts", 16_000, 1),
        )
        open.writeFrame(ByteBuffer.wrap(ByteArray(200) { it.toByte() }), 200)
        val sealed = segmentStore.seal(open)
        database.processingState().upsert(
            ProcessingStateEntity(SESSION_ID, "PENDING", false, null, 0L),
        )
        val provider = object : TranscriptionProvider {
            override val name = "test"
            override val model = "test-model"
            override suspend fun transcribe(audio: ByteArray, codec: String, sampleRateHz: Int): TranscriptResult {
                throw ProcessingException("provider_auth_failed", retryable = false)
            }
        }

        val run = pipeline(
            extractionProvider = null,
            transcriptionProvider = provider,
            segmentStore = segmentStore,
        ).processPending()

        assertEquals(1, run.failed)
        assertEquals("FAILED", database.processingState().forSession(SESSION_ID)?.state)
        assertEquals("SEALED", database.segments().get(sealed.segmentId)?.state)
        assertTrue(File(directory, sealed.fileName).isFile)
    }

    @Test
    fun `successful external transcription records sent audio without retaining content`() = runTest {
        val (segmentStore, _) = encryptedSegmentStore()
        val open = segmentStore.begin(
            SessionId(SESSION_ID),
            continuesPrevious = false,
            format = SegmentFormat("aac-lc-adts", 16_000, 1),
        )
        open.writeFrame(ByteBuffer.wrap(ByteArray(200) { it.toByte() }), 200)
        segmentStore.seal(open)
        database.sessions().register(SessionInfoEntity(SESSION_ID, "2026-10-14", "UTC", 0L, null))
        database.processingState().upsert(ProcessingStateEntity(SESSION_ID, "PENDING", false, null, 0L))
        val provider = object : TranscriptionProvider {
            override val name = "user-key"
            override val model = "nova-3"
            override val billingProviderId = "deepgram"
            override val isExternalProvider = true
            override suspend fun transcribe(audio: ByteArray, codec: String, sampleRateHz: Int) =
                TranscriptResult("en", listOf(TranscribedSpan(0, 1_000, "Hello there.")))
        }

        val run = pipeline(
            extractionProvider = null,
            transcriptionProvider = provider,
            segmentStore = segmentStore,
        ).processPending()

        val sample = database.providerUsage().observeSince(0L).first().single()
        assertEquals(ProcessingRun(processed = 1, failed = 0, remaining = 0), run)
        assertEquals("TRANSCRIPTION", sample.stage)
        assertEquals("deepgram", sample.providerId)
        assertEquals("nova-3", sample.model)
        assertEquals(64L, sample.requestAudioMs)
        assertEquals(64L, sample.sessionAudioMs)
        assertEquals(null, sample.inputTokens)
        assertEquals(null, sample.outputTokens)
    }

    @Test
    fun `incomplete settings leave sessions pending instead of failing them`() = runTest {
        addTranscribedSession()

        val run = pipeline(
            extractionProvider = object : ExtractionProvider {
                override val name = "disabled"
                override val model = "none"
                override suspend fun extract(lines: List<NumberedLine>): ExtractionResult =
                    error("must not run with incomplete settings")
            },
            configGaps = { listOf(com.apercallc.aidailymemory.recording.processing.ConfigGap.EXTRACTION_KEY) },
        ).processPending()

        assertEquals(ProcessingRun(0, 0, 0), run)
        assertEquals("PENDING", database.processingState().forSession(SESSION_ID)?.state)
        assertEquals(null, database.processingState().forSession(SESSION_ID)?.lastError)
    }

    private suspend fun addTranscribedSession(segmentState: String = "DELETED") {
        database.segments().insert(
            SegmentEntity(
                segmentId = SEGMENT_ID,
                sessionId = SESSION_ID,
                sequence = 0,
                state = segmentState,
                byteSize = 0,
                durationMs = 1_000,
                sha256 = null,
                acknowledgedAtEpochMs = null,
                defect = null,
                fileName = "$SEGMENT_ID.seg",
                codec = "aac-lc-adts",
                sampleRateHz = 16_000,
                channelCount = 1,
                continuesPrevious = false,
                start = CaptureTimeColumns(1_000L, "UTC", 0, "1970-01-01", 0L, "boot"),
            ),
        )
        database.localTranscripts().insertAll(
            listOf(
                LocalTranscriptLineEntity(
                    lineId = "line-1",
                    sessionId = SESSION_ID,
                    segmentId = SEGMENT_ID,
                    startEpochMs = 1_000L,
                    endEpochMs = 2_000L,
                    speaker = "Speaker 1",
                    text = "A saved transcript line",
                    confidence = 0.9,
                    provider = "test",
                    model = "test-model",
                ),
            ),
        )
        database.processingState().upsert(
            ProcessingStateEntity(
                SESSION_ID,
                "PENDING",
                segmentState == "DELETED",
                null,
                0L,
            ),
        )
    }

    private fun pipeline(
        extractionProvider: ExtractionProvider?,
        transcriptionProvider: TranscriptionProvider = object : TranscriptionProvider {
            override val name = "unexpected"
            override val model = "unexpected"
            override suspend fun transcribe(
                audio: ByteArray,
                codec: String,
                sampleRateHz: Int,
            ): TranscriptResult = error("saved transcript should avoid transcription")
        },
        configGaps: () -> List<com.apercallc.aidailymemory.recording.processing.ConfigGap> = { emptyList() },
        segmentStore: SegmentStore? = null,
    ) = ProcessingPipeline(
        segments = segmentStore ?: SegmentStore(
            directory = ApplicationProvider.getApplicationContext<Context>().cacheDir,
            dao = database.segments(),
            cipher = { error("saved transcript should avoid reading audio") },
            lifecycle = com.apercallc.aidailymemory.recording.segment.SegmentLifecycle(0),
            ids = UuidV7Generator(),
            clock = com.apercallc.aidailymemory.platform.DeviceClock(
                ApplicationProvider.getApplicationContext<Context>().contentResolver,
            ),
        ),
        segmentDao = database.segments(),
        speechDao = database.speech(),
        clipDao = database.clips(),
        memoryItemDao = database.memoryItems(),
        dailyReportDao = database.dailyReports(),
        localTranscriptDao = database.localTranscripts(),
        processingStateDao = database.processingState(),
        providerUsageDao = database.providerUsage(),
        transcriptionProvider = transcriptionProvider,
        extractionProvider = extractionProvider,
        settings = settings,
        ids = UuidV7Generator(),
        now = { 42L },
        configGaps = configGaps,
    )

    private fun encryptedSegmentStore(): Pair<SegmentStore, File> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        StreamingAeadConfig.register()
        val aead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_4KB"))
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        val directory = File(context.cacheDir, "pipeline-audio-test").apply { deleteRecursively() }
        val store = SegmentStore(
            directory = directory,
            dao = database.segments(),
            cipher = { SegmentCipher(aead) },
            lifecycle = SegmentLifecycle(0),
            ids = UuidV7Generator(),
            clock = com.apercallc.aidailymemory.platform.DeviceClock(context.contentResolver),
        )
        return store to directory
    }

    private companion object {
        const val SESSION_ID = "session-1"
        const val SEGMENT_ID = "segment-1"
    }
}
