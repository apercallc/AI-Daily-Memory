package com.apercallc.aidailymemory.processing

import android.util.Log
import com.apercallc.aidailymemory.capture.SegmentAudioRead
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.ClipDao
import com.apercallc.aidailymemory.data.DailyReportDao
import com.apercallc.aidailymemory.data.DailyReportEntity
import com.apercallc.aidailymemory.data.LocalTranscriptDao
import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.MemoryItemDao
import com.apercallc.aidailymemory.data.MemoryItemEntity
import com.apercallc.aidailymemory.data.ProcessingStateDao
import com.apercallc.aidailymemory.data.ProcessingStateEntity
import com.apercallc.aidailymemory.data.SegmentDao
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.data.SpeechDao
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.id.UuidV7Generator
import com.apercallc.aidailymemory.recording.processing.ConfigGap
import com.apercallc.aidailymemory.recording.processing.ExtractedItem
import com.apercallc.aidailymemory.recording.processing.NumberedLine
import com.apercallc.aidailymemory.recording.processing.ProcessingException
import com.apercallc.aidailymemory.recording.processing.ProcessingValidation
import com.apercallc.aidailymemory.recording.processing.TranscriptionProvider
import com.apercallc.aidailymemory.recording.processing.ExtractionProvider
import com.apercallc.aidailymemory.recording.segment.SegmentState
import com.apercallc.aidailymemory.recording.speech.EnergyVad
import com.apercallc.aidailymemory.recording.session.PartOfDay
import com.apercallc.aidailymemory.settings.ProcessingSettings
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class ProcessingRun(val processed: Int, val failed: Int, val remaining: Int)

/**
 * On-device processing pipeline (ADR-0020): transcribes audio clips, extracts memory items,
 * builds a daily report, and deletes audio after transcription. Runs while charging.
 *
 * Follows the pattern of [com.apercallc.aidailymemory.analysis.SpeechAnalyzer]: finds pending
 * work, processes one session at a time, and reports counts so the job can reschedule.
 */
class ProcessingPipeline(
    private val segments: SegmentStore,
    private val segmentDao: SegmentDao,
    private val speechDao: SpeechDao,
    private val clipDao: ClipDao,
    private val memoryItemDao: MemoryItemDao,
    private val dailyReportDao: DailyReportDao,
    private val localTranscriptDao: LocalTranscriptDao,
    private val processingStateDao: ProcessingStateDao,
    private val transcriptionProvider: TranscriptionProvider,
    private val extractionProvider: ExtractionProvider,
    private val settings: ProcessingSettings,
    private val ids: UuidV7Generator,
    private val now: () -> Long = System::currentTimeMillis,
    private val shouldKeepAudioForUpload: suspend (SegmentEntity) -> Boolean = { false },
    /** Non-empty = settings are incomplete: sessions wait instead of failing (see settings UI). */
    private val configGaps: () -> List<ConfigGap> = { emptyList() },
) {
    /**
     * Processes up to [limit] sessions, oldest first. Cancellation stops between sessions.
     * Returns immediately if processing is disabled.
     */
    suspend fun processPending(limit: Int = BATCH): ProcessingRun {
        if (!settings.processingEnabled.value) return ProcessingRun(0, 0, 0)
        if (configGaps().isNotEmpty()) {
            // Never run with a placeholder provider: that would mark every session FAILED.
            // Settings show what is missing; saving them re-queues and reschedules.
            Log.w(TAG, "processing_waiting_for_settings")
            return ProcessingRun(0, 0, 0)
        }

        processingStateDao.enqueueReadySessions(EnergyVad.VERSION, now())
        var processed = 0
        var failed = 0
        val pending = processingStateDao.pending(limit)
        for (state in pending) {
            currentCoroutineContext().ensureActive()
            try {
                processSession(state.sessionId)
                processed++
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    updateState(state.sessionId, "PENDING", null)
                }
                throw e
            } catch (e: ProcessingException) {
                Log.w(TAG, "processing_failed code=${e.code}")
                failed++
                updateState(state.sessionId, if (e.retryable) "PENDING" else "FAILED", e.code)
            } catch (e: Exception) {
                logFailure(TAG, "processing_exception", e)
                failed++
                updateState(state.sessionId, "FAILED", "exception")
            }
        }
        val remaining = processingStateDao.pending(BATCH).size
        Log.i(TAG, "processing_batch processed=$processed failed=$failed remaining=$remaining")
        return ProcessingRun(processed, failed, remaining)
    }

    private suspend fun processSession(sessionId: String) {
        // Reuse persisted evidence after an interrupted extraction or sync-window stop. Audio
        // may already be deleted, so load transcripts before deciding there is no work to do.
        val allLines = localTranscriptDao.forSession(sessionId).toMutableList()
        val segmentIds = segmentDao.forSession(sessionId)
            .filter { it.state == SegmentState.SEALED.name }
            .map { it.segmentId }

        if (segmentIds.isEmpty() && allLines.isEmpty()) {
            updateState(sessionId, "COMPLETED", null)
            return
        }

        updateState(sessionId, "TRANSCRIBING", null)

        if (allLines.isEmpty()) {
            for (segmentId in segmentIds) {
                val segment = segmentDao.get(segmentId) ?: continue
                val audio = when (val read = segments.readAudioResult(segment)) {
                    is SegmentAudioRead.Audio -> read.bytes
                    SegmentAudioRead.Unavailable ->
                        throw ProcessingException("audio_temporarily_unavailable", retryable = true)
                    SegmentAudioRead.Missing,
                    SegmentAudioRead.Corrupt ->
                        throw ProcessingException("audio_unreadable", retryable = false)
                }
                if (audio.isEmpty()) throw ProcessingException("audio_empty", retryable = false)

                val result = transcriptionProvider.transcribe(
                    audio,
                    segment.codec,
                    segment.sampleRateHz,
                )
                ProcessingValidation.validateTranscript(result, segment.durationMs)

                val segmentStart = segment.start.occurredAtEpochMs
                for (span in result.spans) {
                    val lineId = ids.next().toString()
                    allLines.add(
                        LocalTranscriptLineEntity(
                            lineId = lineId,
                            sessionId = sessionId,
                            segmentId = segmentId,
                            startEpochMs = segmentStart + span.startMs,
                            endEpochMs = segmentStart + span.endMs,
                            speaker = span.speaker,
                            text = span.text,
                            confidence = span.confidence,
                            provider = transcriptionProvider.name,
                            model = transcriptionProvider.model,
                        ),
                    )
                }
            }

            if (allLines.isEmpty()) {
                updateState(sessionId, "COMPLETED", null)
                return
            }
            localTranscriptDao.insertAll(allLines)
        }

        updateState(sessionId, "TRANSCRIBED", null)
        var audioDeleted = false
        if (settings.deleteAudioAfterTranscription.value) {
            var audioRetainedForUpload = false
            for (segmentId in segmentIds) {
                val segment = segmentDao.get(segmentId) ?: continue
                if (shouldKeepAudioForUpload(segment)) {
                    audioRetainedForUpload = true
                    continue
                }
                segments.deleteSegmentAudio(segment)
            }
            audioDeleted = !audioRetainedForUpload
            if (audioDeleted) {
                updateState(sessionId, "TRANSCRIBED", null, audioDeleted = true)
            }
        }

        // Build numbered lines for extraction
        val numberedLines = allLines.mapIndexed { index, line ->
            NumberedLine(index, line.speaker, line.text)
        }

        updateState(sessionId, "EXTRACTING", null)

        // Extract memory items
        val extractionResult = extractionProvider.extract(numberedLines)
        ProcessingValidation.validateExtraction(extractionResult, numberedLines.size)

        // Store memory items (replace for session — idempotent by replacement)
        val memoryDay = computeMemoryDay(allLines.first().startEpochMs)
        val extractionModel = "${extractionProvider.name}:${extractionProvider.model}"
        val nowMs = now()
        val memoryItems = extractionResult.items.map { item ->
            MemoryItemEntity(
                id = ids.next().toString(),
                sessionId = sessionId,
                memoryDay = memoryDay,
                kind = item.kind,
                text = item.text,
                confidence = item.confidence,
                quote = item.quote,
                owner = item.owner,
                due = item.due,
                direction = item.direction,
                counterpart = item.counterpart,
                extractionModel = extractionModel,
                extractionVersion = 1,
                evidenceEpochMs = item.evidenceSegmentIndices.firstOrNull()?.let { idx ->
                    allLines.getOrNull(idx)?.startEpochMs
                },
                dismissedAtEpochMs = null,
                extractedAtEpochMs = nowMs,
            )
        }
        memoryItemDao.replaceForSession(sessionId, memoryItems)

        // Build and store daily report
        val report = ReportBuilder.build(memoryDay, sessionId, memoryItems, allLines, nowMs, extractionModel)
        val reportJson = ReportBuilder.serialize(report)
        dailyReportDao.upsert(
            DailyReportEntity(
                id = ids.next().toString(),
                memoryDay = memoryDay,
                zoneId = ZoneId.systemDefault().id,
                headline = report.headline,
                summary = report.summary,
                reportJson = reportJson,
                generatedAtEpochMs = nowMs,
                extractionModel = extractionModel,
                extractionVersion = 1,
            ),
        )

        updateState(sessionId, "COMPLETED", null, audioDeleted = audioDeleted)
    }

    private fun computeMemoryDay(startEpochMs: Long): String {
        return Instant.ofEpochMilli(startEpochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }

    private suspend fun updateState(
        sessionId: String,
        state: String,
        error: String?,
        audioDeleted: Boolean = false,
    ) {
        val audioWasDeleted = processingStateDao.forSession(sessionId)?.audioDeleted == true
        processingStateDao.upsert(
            ProcessingStateEntity(
                sessionId = sessionId,
                state = state,
                audioDeleted = audioDeleted || audioWasDeleted,
                lastError = error,
                updatedAtEpochMs = now(),
            ),
        )
    }

    companion object {
        private const val TAG = "ProcessingPipeline"
        private const val BATCH = 3
    }
}