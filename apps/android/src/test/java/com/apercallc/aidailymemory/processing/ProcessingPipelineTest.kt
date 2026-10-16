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
import com.apercallc.aidailymemory.recording.processing.TranscriptChunker
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
    fun `completed segment transcript is saved before a later segment fails`() = runTest {
        val (segmentStore, _) = encryptedSegmentStore()
        repeat(2) {
            val open = segmentStore.begin(
                SessionId(SESSION_ID),
                continuesPrevious = it > 0,
                format = SegmentFormat("aac-lc-adts", 16_000, 1),
            )
            open.writeFrame(ByteBuffer.wrap(ByteArray(200) { byte -> byte.toByte() }), 200)
            segmentStore.seal(open)
        }
        database.processingState().upsert(ProcessingStateEntity(SESSION_ID, "PENDING", false, null, 0L))
        var calls = 0
        val provider = object : TranscriptionProvider {
            override val name = "test"
            override val model = "test-model"
            override suspend fun transcribe(audio: ByteArray, codec: String, sampleRateHz: Int): TranscriptResult {
                calls++
                if (calls == 2) throw ProcessingException("temporary_provider_error", retryable = true)
                return TranscriptResult("en", listOf(TranscribedSpan(0, 50, "First segment")))
            }
        }

        val run = pipeline(
            extractionProvider = null,
            transcriptionProvider = provider,
            segmentStore = segmentStore,
        ).processPending()

        assertEquals(1, run.failed)
        assertEquals(1, database.localTranscripts().countForSession(SESSION_ID))
        assertEquals("First segment", database.localTranscripts().forSession(SESSION_ID).single().text)

        var resumedCalls = 0
        val resumedRun = pipeline(
            extractionProvider = null,
            transcriptionProvider = object : TranscriptionProvider {
                override val name = "test"
                override val model = "test-model"
                override suspend fun transcribe(audio: ByteArray, codec: String, sampleRateHz: Int): TranscriptResult {
                    resumedCalls++
                    return TranscriptResult("en", listOf(TranscribedSpan(0, 50, "Second segment")))
                }
            },
            segmentStore = segmentStore,
        ).processPending()

        assertEquals(ProcessingRun(processed = 1, failed = 0, remaining = 0), resumedRun)
        assertEquals(1, resumedCalls)
        assertEquals(
            listOf("First segment", "Second segment"),
            database.localTranscripts().forSession(SESSION_ID).map { it.text },
        )
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

    @Test
    fun `sessions are enqueued even while provider settings are incomplete`() = runTest {
        val (store, _) = encryptedSegmentStore()
        addCompletedAudio(store, 1)
        val segmentId = database.segments().forSession(SESSION_ID).single().segmentId
        database.speech().upsert(
            com.apercallc.aidailymemory.data.SegmentSpeechEntity(
                segmentId = segmentId,
                analyzer = com.apercallc.aidailymemory.recording.speech.VoiceVad.VERSION,
                outcome = "OK",
                totalMs = 1_000,
                speechMs = 500,
                paddedMs = 500,
                regionCount = 1,
                analyzedAtEpochMs = 1L,
            ),
        )

        pipeline(
            extractionProvider = null,
            segmentStore = store,
            configGaps = { listOf(com.apercallc.aidailymemory.recording.processing.ConfigGap.EXTRACTION_KEY) },
        ).processPending()

        assertEquals("PENDING", database.processingState().forSession(SESSION_ID)?.state)
        assertEquals(null, database.processingState().forSession(SESSION_ID)?.lastError)
        assertEquals("SEALED", database.segments().forSession(SESSION_ID).single().state)
    }

    @Test
    fun `checkpoint failure rolls back later transcript after an empty segment`() = runTest {
        val (store, _) = encryptedSegmentStore()
        addCompletedAudio(store, 2)
        database.processingState().upsert(ProcessingStateEntity(SESSION_ID, "PENDING", false, null, 0L))
        // Simulate a storage failure at the precise crash window between evidence and progress.
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_checkpoint BEFORE INSERT ON processing_state " +
                "WHEN NEW.completed_segments = 2 BEGIN SELECT RAISE(ABORT, 'checkpoint fault'); END",
        )
        var calls = 0
        val provider = transcriptProvider {
            calls++
            if (calls == 1) TranscriptResult("en", emptyList())
            else TranscriptResult("en", listOf(TranscribedSpan(0, 50, "Uncommitted line")))
        }
        val interrupted = pipeline(null, provider, segmentStore = store).processPending()

        assertEquals(1, interrupted.failed)
        assertEquals(1, database.processingState().forSession(SESSION_ID)?.completedSegments)
        assertTrue(database.localTranscripts().forSession(SESSION_ID).isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_checkpoint")
        var resumedCalls = 0
        val resumed = pipeline(null, transcriptProvider {
            resumedCalls++
            TranscriptResult("en", listOf(TranscribedSpan(0, 50, "Committed line")))
        }, segmentStore = store).processPending()

        assertEquals(ProcessingRun(1, 0, 0), resumed)
        assertEquals(1, resumedCalls, "The committed empty segment must not run again")
        assertEquals("Committed line", database.localTranscripts().forSession(SESSION_ID).single().text)
    }

    @Test
    fun `legacy evidence beyond an empty segment is never replaced on recovery`() = runTest {
        val (store, _) = encryptedSegmentStore()
        addCompletedAudio(store, 2)
        val later = database.segments().forSession(SESSION_ID)[1]
        val original = LocalTranscriptLineEntity(
            "immutable", SESSION_ID, later.segmentId, 1_000L, 1_050L,
            null, "Original evidence", null, "old", "old-model",
        )
        database.localTranscripts().insertAll(listOf(original))
        database.processingState().upsert(ProcessingStateEntity(SESSION_ID, "PENDING", false, null, 0L))
        var calls = 0
        val run = pipeline(null, transcriptProvider {
            calls++
            TranscriptResult("en", emptyList())
        }, segmentStore = store).processPending()

        assertEquals(ProcessingRun(1, 0, 0), run)
        assertEquals(1, calls)
        assertEquals(listOf(original), database.localTranscripts().forSession(SESSION_ID))
    }

    @Test
    fun `retryable failures exhaust three attempts and manual retry preserves progress with a fresh budget`() = runTest {
        addTranscribedSession()
        val initial = requireNotNull(database.processingState().forSession(SESSION_ID))
        database.processingState().upsert(initial.copy(completedSegments = 2, totalSegments = 2, processingTimeMs = 75L))
        val evidence = database.localTranscripts().forSession(SESSION_ID)
        var calls = 0
        val provider = object : ExtractionProvider {
            override val name = "test"
            override val model = "test-model"
            override suspend fun extract(lines: List<NumberedLine>): ExtractionResult {
                calls++
                throw ProcessingException("temporary_provider_error", retryable = true)
            }
        }
        repeat(3) { pipeline(provider).processPending() }
        assertEquals("FAILED", database.processingState().forSession(SESSION_ID)?.state)
        assertEquals("temporary_provider_error", database.processingState().forSession(SESSION_ID)?.lastError)
        assertEquals(ProcessingRun(0, 0, 0), pipeline(provider).processPending())
        assertEquals(3, calls)

        assertEquals(1, database.processingState().retryFailed(43L))
        val reset = requireNotNull(database.processingState().forSession(SESSION_ID))
        assertEquals(2, reset.completedSegments)
        assertEquals(75L, reset.processingTimeMs)
        assertTrue(reset.audioDeleted)
        repeat(2) {
            pipeline(provider).processPending()
            assertEquals("PENDING", database.processingState().forSession(SESSION_ID)?.state)
        }
        pipeline(provider).processPending()
        assertEquals("FAILED", database.processingState().forSession(SESSION_ID)?.state)
        assertEquals(6, calls)
        assertEquals(evidence, database.localTranscripts().forSession(SESSION_ID))
    }

    @Test
    fun `the day's report accumulates items and transcript coverage across sessions`() = runTest {
        database.sessions().register(SessionInfoEntity("session-1", "1970-01-01", "UTC", 0L, null))
        database.sessions().register(SessionInfoEntity("session-2", "1970-01-01", "UTC", 0L, null))
        addTranscribedSession()
        val run1 = pipeline(
            object : ExtractionProvider {
                override val name = "test"
                override val model = "test-model"
                override suspend fun extract(lines: List<NumberedLine>) = ExtractionResult(
                    listOf(
                        com.apercallc.aidailymemory.recording.processing.ExtractedItem(
                            kind = "action", text = "from session one", confidence = "likely",
                            quote = null, owner = null, due = null, direction = null, counterpart = null,
                            evidenceSegmentIndices = listOf(0),
                        ),
                    ),
                )
            },
        ).processPending()
        assertEquals(ProcessingRun(1, 0, 0), run1)

        database.segments().insert(
            SegmentEntity(
                segmentId = "segment-2", sessionId = "session-2", sequence = 0, state = "DELETED",
                byteSize = 0, durationMs = 1_000, sha256 = null, acknowledgedAtEpochMs = null, defect = null,
                fileName = "segment-2.seg", codec = "aac-lc-adts", sampleRateHz = 16_000, channelCount = 1,
                continuesPrevious = false, start = CaptureTimeColumns(1_000L, "UTC", 0, "1970-01-01", 0L, "boot"),
            ),
        )
        database.localTranscripts().insertAll(
            listOf(
                LocalTranscriptLineEntity(
                    lineId = "line-2", sessionId = "session-2", segmentId = "segment-2",
                    startEpochMs = 3_000L, endEpochMs = 4_000L, speaker = null,
                    text = "A second session's transcript line", confidence = 0.9,
                    provider = "test", model = "test-model",
                ),
            ),
        )
        database.processingState().upsert(ProcessingStateEntity("session-2", "PENDING", true, null, 0L))

        val run2 = pipeline(
            object : ExtractionProvider {
                override val name = "test"
                override val model = "test-model"
                override suspend fun extract(lines: List<NumberedLine>) = ExtractionResult(
                    listOf(
                        com.apercallc.aidailymemory.recording.processing.ExtractedItem(
                            kind = "action", text = "from session two", confidence = "likely",
                            quote = null, owner = null, due = null, direction = null, counterpart = null,
                            evidenceSegmentIndices = listOf(0),
                        ),
                    ),
                )
            },
        ).processPending()
        assertEquals(ProcessingRun(1, 0, 0), run2)

        val reports = database.dailyReports().observeRecent(2).first()
        assertEquals(1, reports.size, "one day, one report row")
        val report = ReportBuilder.deserialize(reports.single().reportJson)
        assertEquals(
            setOf("from session one", "from session two"),
            report.items.map { it.text }.toSet(),
        )
        assertEquals(2, report.coverage.sessions)
        assertEquals(com.apercallc.aidailymemory.recording.processing.DailyReportStatus.COMPLETE, report.status)
    }

    @Test
    fun `a day's report is partial until every session registered for it finishes processing`() = runTest {
        // The pipeline computes a session's memory day from its first transcript line's epoch
        // time in the device zone; register sessions under that same computed value so this
        // test doesn't depend on which zone it happens to run in.
        val day = java.time.Instant.ofEpochMilli(1_000L).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        database.sessions().register(SessionInfoEntity("s1", day, "UTC", 1_000L, null))
        database.sessions().register(SessionInfoEntity("s2", day, "UTC", 2_000L, null))
        database.segments().insert(
            SegmentEntity(
                segmentId = "seg-s1", sessionId = "s1", sequence = 0, state = "DELETED",
                byteSize = 0, durationMs = 1_000, sha256 = null, acknowledgedAtEpochMs = null, defect = null,
                fileName = "seg-s1.seg", codec = "aac-lc-adts", sampleRateHz = 16_000, channelCount = 1,
                continuesPrevious = false, start = CaptureTimeColumns(1_000L, "UTC", 0, day, 0L, "boot"),
            ),
        )
        database.localTranscripts().insertAll(
            listOf(LocalTranscriptLineEntity("line-s1", "s1", "seg-s1", 1_000L, 1_050L, null, "hello", 0.9, "test", "test-model")),
        )
        database.processingState().upsert(ProcessingStateEntity("s1", "PENDING", true, null, 0L))

        val run1 = pipeline(extractionProvider = null).processPending()

        assertEquals(ProcessingRun(1, 0, 0), run1)
        val afterS1 = ReportBuilder.deserialize(database.dailyReports().observeRecent(1).first().single().reportJson)
        assertEquals(
            com.apercallc.aidailymemory.recording.processing.DailyReportStatus.PARTIAL,
            afterS1.status,
            "s2 is registered for this day but hasn't started processing yet",
        )

        database.segments().insert(
            SegmentEntity(
                segmentId = "seg-s2", sessionId = "s2", sequence = 0, state = "DELETED",
                byteSize = 0, durationMs = 1_000, sha256 = null, acknowledgedAtEpochMs = null, defect = null,
                fileName = "seg-s2.seg", codec = "aac-lc-adts", sampleRateHz = 16_000, channelCount = 1,
                continuesPrevious = false, start = CaptureTimeColumns(2_000L, "UTC", 0, day, 0L, "boot"),
            ),
        )
        database.localTranscripts().insertAll(
            listOf(LocalTranscriptLineEntity("line-s2", "s2", "seg-s2", 2_000L, 2_050L, null, "world", 0.9, "test", "test-model")),
        )
        database.processingState().upsert(ProcessingStateEntity("s2", "PENDING", true, null, 0L))

        val run2 = pipeline(extractionProvider = null).processPending()

        assertEquals(ProcessingRun(1, 0, 0), run2)
        val afterBoth = ReportBuilder.deserialize(database.dailyReports().observeRecent(1).first().single().reportJson)
        assertEquals(com.apercallc.aidailymemory.recording.processing.DailyReportStatus.COMPLETE, afterBoth.status)
    }

    @Test
    fun `cancellation preserves the existing failure budget`() = runTest {
        addTranscribedSession()
        var cancel = false
        val provider = object : ExtractionProvider {
            override val name = "test"
            override val model = "test-model"
            override suspend fun extract(lines: List<NumberedLine>): ExtractionResult {
                if (cancel) throw kotlinx.coroutines.CancellationException("window closed")
                throw ProcessingException("temporary_provider_error", retryable = true)
            }
        }
        pipeline(provider).processPending()
        cancel = true
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            pipeline(provider).processPending()
        }
        assertEquals("PENDING", database.processingState().forSession(SESSION_ID)?.state)
        cancel = false
        pipeline(provider).processPending()
        assertEquals("PENDING", database.processingState().forSession(SESSION_ID)?.state)
        pipeline(provider).processPending()
        assertEquals("FAILED", database.processingState().forSession(SESSION_ID)?.state)
    }

    @Test
    fun `a long transcript is chunked into bounded extraction calls, and results are concatenated`() = runTest {
        database.segments().insert(
            SegmentEntity(
                segmentId = SEGMENT_ID, sessionId = SESSION_ID, sequence = 0, state = "DELETED",
                byteSize = 0, durationMs = 1_000, sha256 = null, acknowledgedAtEpochMs = null, defect = null,
                fileName = "$SEGMENT_ID.seg", codec = "aac-lc-adts", sampleRateHz = 16_000, channelCount = 1,
                continuesPrevious = false, start = CaptureTimeColumns(1_000L, "UTC", 0, "1970-01-01", 0L, "boot"),
            ),
        )
        database.localTranscripts().insertAll(
            listOf(
                LocalTranscriptLineEntity(
                    "line-a", SESSION_ID, SEGMENT_ID, 1_000L, 1_050L, null, "aaaaa", 0.9, "test", "test-model",
                ),
                LocalTranscriptLineEntity(
                    "line-b", SESSION_ID, SEGMENT_ID, 1_050L, 1_100L, null, "bbbbb", 0.9, "test", "test-model",
                ),
            ),
        )
        database.processingState().upsert(ProcessingStateEntity(SESSION_ID, "PENDING", true, null, 0L))

        val callLines = mutableListOf<List<NumberedLine>>()
        val run = pipeline(
            object : ExtractionProvider {
                override val name = "test"
                override val model = "test-model"
                override suspend fun extract(lines: List<NumberedLine>): ExtractionResult {
                    callLines.add(lines)
                    return ExtractionResult(
                        listOf(
                            com.apercallc.aidailymemory.recording.processing.ExtractedItem(
                                kind = "idea", text = "from chunk ${callLines.size}", confidence = "likely",
                                quote = null, owner = null, due = null, direction = null, counterpart = null,
                                evidenceSegmentIndices = listOf(lines.first().index),
                            ),
                        ),
                    )
                }
            },
            // Each line is 5 chars; a budget of 5 forces one line per chunk.
            extractionChunkMaxChars = 5,
        ).processPending()

        assertEquals(ProcessingRun(1, 0, 0), run)
        assertEquals(2, callLines.size, "two chunks, one extraction call each")
        // Indices are preserved from the full line list, never renumbered per chunk.
        assertEquals(listOf(0), callLines[0].map { it.index })
        assertEquals(listOf(1), callLines[1].map { it.index })
        val items = database.memoryItems().forSession(SESSION_ID)
        assertEquals(setOf("from chunk 1", "from chunk 2"), items.map { it.text }.toSet())
    }

    private fun transcriptProvider(result: suspend () -> TranscriptResult) = object : TranscriptionProvider {
        override val name = "test"
        override val model = "test-model"
        override suspend fun transcribe(audio: ByteArray, codec: String, sampleRateHz: Int) = result()
    }

    private suspend fun addCompletedAudio(store: SegmentStore, count: Int) {
        repeat(count) { index ->
            val open = store.begin(SessionId(SESSION_ID), index > 0, SegmentFormat("aac-lc-adts", 16_000, 1))
            open.writeFrame(ByteBuffer.wrap(ByteArray(200) { it.toByte() }), 200)
            store.seal(open)
        }
        database.transitions().append(
            com.apercallc.aidailymemory.data.TransitionEntity(
                1L, "COMPLETED", SESSION_ID, null, "stop",
                CaptureTimeColumns(1_000L, "UTC", 0, "1970-01-01", 0L, "boot"),
            ),
        )
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
        extractionChunkMaxChars: Int = TranscriptChunker.DEFAULT_MAX_CHARS_PER_CHUNK,
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
        sessionDao = database.sessions(),
        database = database,
        transcriptionProvider = transcriptionProvider,
        extractionProvider = extractionProvider,
        settings = settings,
        ids = UuidV7Generator(),
        now = { 42L },
        configGaps = configGaps,
        extractionChunkMaxChars = extractionChunkMaxChars,
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
