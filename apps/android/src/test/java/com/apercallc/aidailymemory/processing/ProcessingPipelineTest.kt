package com.apercallc.aidailymemory.processing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.CaptureTimeColumns
import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.ProcessingStateEntity
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.processing.ExtractionProvider
import com.apercallc.aidailymemory.recording.processing.ExtractionResult
import com.apercallc.aidailymemory.recording.processing.NumberedLine
import com.apercallc.aidailymemory.recording.processing.ProcessingException
import com.apercallc.aidailymemory.recording.processing.TranscriptionProvider
import com.apercallc.aidailymemory.recording.processing.TranscriptResult
import com.apercallc.aidailymemory.settings.ProcessingSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun `saved transcript extraction keeps audio while an upload is still eligible`() = runTest {
        addTranscribedSession(segmentState = "SEALED")

        val run = pipeline(
            extractionProvider = object : ExtractionProvider {
                override val name = "test"
                override val model = "test-model"
                override suspend fun extract(lines: List<NumberedLine>) = ExtractionResult(emptyList())
            },
            shouldKeepAudioForUpload = { true },
        ).processPending()

        assertEquals(1, run.processed)
        assertEquals("SEALED", database.segments().get(SEGMENT_ID)?.state)
        assertFalse(database.processingState().forSession(SESSION_ID)?.audioDeleted == true)
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
        extractionProvider: ExtractionProvider,
        shouldKeepAudioForUpload: suspend (SegmentEntity) -> Boolean = { false },
        configGaps: () -> List<com.apercallc.aidailymemory.recording.processing.ConfigGap> = { emptyList() },
    ) = ProcessingPipeline(
        segments = SegmentStore(
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
        transcriptionProvider = object : TranscriptionProvider {
            override val name = "unexpected"
            override val model = "unexpected"
            override suspend fun transcribe(
                audio: ByteArray,
                codec: String,
                sampleRateHz: Int,
            ): TranscriptResult = error("saved transcript should avoid transcription")
        },
        extractionProvider = extractionProvider,
        settings = settings,
        ids = UuidV7Generator(),
        now = { 42L },
        shouldKeepAudioForUpload = shouldKeepAudioForUpload,
        configGaps = configGaps,
    )

    private companion object {
        const val SESSION_ID = "session-1"
        const val SEGMENT_ID = "segment-1"
    }
}
