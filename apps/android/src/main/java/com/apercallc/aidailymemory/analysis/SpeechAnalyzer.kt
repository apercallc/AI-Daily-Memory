package com.apercallc.aidailymemory.analysis

import android.util.Log
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.data.SegmentSpeechEntity
import com.apercallc.aidailymemory.data.SpeechDao
import com.apercallc.aidailymemory.data.SpeechRegionEntity
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.speech.EnergyVad
import com.apercallc.aidailymemory.recording.speech.FrameSink
import com.apercallc.aidailymemory.recording.speech.SpeechConfig
import com.apercallc.aidailymemory.recording.speech.SpeechRegions
import com.apercallc.aidailymemory.recording.speech.SpeechSummary
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** How an analysis ended; stored so a bad segment is recorded once, not retried nightly. */
enum class SpeechOutcome {
    OK,

    /** File gone, failed authentication, or not a clean ADTS stream. */
    UNREADABLE,

    /** The decoder rejected the stream. */
    UNDECODABLE,
}

data class AnalysisRun(val analyzed: Int, val failed: Int, val remaining: Int)

/**
 * Finds sealed segments no one has analyzed yet and records where the speech is (ADR-0015).
 *
 * Phase 0 runs this in shadow mode: nothing is uploaded; the totals feed the Gate B
 * per-user-day cost model. Phase 1 will cut upload clips from the same regions.
 */
class SpeechAnalyzer(
    private val store: SegmentStore,
    private val dao: SpeechDao,
    private val decoder: PcmDecoder,
    private val now: () -> Long = System::currentTimeMillis,
    private val config: SpeechConfig = SpeechConfig(),
) {
    /** Analyzes up to [limit] segments, oldest first. Cancellation stops between segments. */
    suspend fun analyzePending(limit: Int = BATCH): AnalysisRun {
        var analyzed = 0
        var failed = 0
        for (segment in dao.pending(EnergyVad.VERSION, limit)) {
            currentCoroutineContext().ensureActive()
            val (outcome, summary) = analyze(segment)
            save(segment, outcome, summary)
            if (outcome == SpeechOutcome.OK) analyzed++ else failed++
        }
        val remaining = dao.pendingCount(EnergyVad.VERSION)
        Log.i(TAG, "speech_analysis_batch analyzed=$analyzed failed=$failed remaining=$remaining")
        return AnalysisRun(analyzed, failed, remaining)
    }

    private fun analyze(segment: SegmentEntity): Pair<SpeechOutcome, SpeechSummary?> {
        val audio = store.readAudio(segment) ?: return SpeechOutcome.UNREADABLE to null
        val vad = EnergyVad(segment.sampleRateHz)
        val frames = FrameBuffer()
        return try {
            decoder.decode(audio, segment.sampleRateHz, segment.channelCount) { pcm, count ->
                vad.process(pcm, count, frames)
            }
            val summary = SpeechRegions.build(
                frames.values,
                frames.size,
                vad.frameMs,
                segment.durationMs,
                config,
            )
            SpeechOutcome.OK to summary
        } catch (e: IOException) {
            logFailure(TAG, "speech_decode_failed", e, warning = true)
            SpeechOutcome.UNDECODABLE to null
        }
    }

    private suspend fun save(segment: SegmentEntity, outcome: SpeechOutcome, summary: SpeechSummary?) {
        val analysis = SegmentSpeechEntity(
            segmentId = segment.segmentId,
            analyzer = EnergyVad.VERSION,
            outcome = outcome.name,
            totalMs = segment.durationMs,
            speechMs = summary?.speechMs ?: 0,
            paddedMs = summary?.paddedMs ?: 0,
            regionCount = summary?.regions?.size ?: 0,
            analyzedAtEpochMs = now(),
        )
        val regions = summary?.regions.orEmpty().map {
            SpeechRegionEntity(segment.segmentId, it.startMs, it.endMs, it.speechMs, it.lowConfidence)
        }
        dao.save(analysis, regions)
    }

    /** Growable per-frame decisions; one segment is minutes, so this stays small. */
    private class FrameBuffer : FrameSink {
        var values = BooleanArray(INITIAL_FRAMES)
            private set
        var size = 0
            private set

        override fun onFrame(isSpeech: Boolean) {
            if (size == values.size) values = values.copyOf(values.size * 2)
            values[size++] = isSpeech
        }
    }

    private companion object {
        const val TAG = "SpeechAnalyzer"
        const val BATCH = 200
        const val INITIAL_FRAMES = 2_048
    }
}
