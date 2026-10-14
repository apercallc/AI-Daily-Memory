package com.apercallc.aidailymemory.analysis

import android.util.Log
import com.apercallc.aidailymemory.capture.SegmentAudioRead
import com.apercallc.aidailymemory.capture.SegmentStore
import com.apercallc.aidailymemory.data.SegmentEntity
import com.apercallc.aidailymemory.data.SegmentSpeechEntity
import com.apercallc.aidailymemory.data.SpeechDao
import com.apercallc.aidailymemory.data.SpeechRegionEntity
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.speech.FrameSink
import com.apercallc.aidailymemory.recording.speech.SpeechConfig
import com.apercallc.aidailymemory.recording.speech.SpeechRegions
import com.apercallc.aidailymemory.recording.speech.SpeechSummary
import com.apercallc.aidailymemory.recording.speech.VoiceVad
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
 * Finds sealed segments no one has analyzed yet and records where human voices are (ADR-0015).
 * Silence, background noise, instruments and nature sounds are not speech and are left out, so
 * only voices are ever cut into clips for transcription.
 *
 * This remains shadow-only: it does not suppress or upload audio. The totals support local
 * transcription cost and device-performance estimates; VAD must pass labeled-audio and device
 * quality gates before it can skip any audio.
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
        for (segment in dao.pending(VoiceVad.VERSION, limit)) {
            currentCoroutineContext().ensureActive()
            val result = analyze(segment) ?: break
            val (outcome, summary) = result
            save(segment, outcome, summary)
            if (outcome == SpeechOutcome.OK) analyzed++ else failed++
        }
        val remaining = dao.pendingCount(VoiceVad.VERSION)
        Log.i(TAG, "speech_analysis_batch analyzed=$analyzed failed=$failed remaining=$remaining")
        return AnalysisRun(analyzed, failed, remaining)
    }

    private fun analyze(segment: SegmentEntity): Pair<SpeechOutcome, SpeechSummary?>? {
        val audio = when (val read = store.readAudioResult(segment)) {
            is SegmentAudioRead.Audio -> read.bytes
            SegmentAudioRead.Missing,
            SegmentAudioRead.Corrupt ->
                return SpeechOutcome.UNREADABLE to null
            SegmentAudioRead.Unavailable -> return null
        }
        val vad = VoiceVad(segment.sampleRateHz)
        val frames = FrameBuffer()
        return try {
            decoder.decode(audio, segment.sampleRateHz, segment.channelCount) { pcm, count ->
                vad.process(pcm, count)
            }
            vad.finish(frames)
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
        } catch (e: RuntimeException) {
            // A codec or detector fault on odd audio must cost this one segment, not every segment
            // after it: marked undecodable, logged, and the batch moves on.
            logFailure(TAG, "speech_analysis_segment_failed", e)
            SpeechOutcome.UNDECODABLE to null
        }
    }

    private suspend fun save(segment: SegmentEntity, outcome: SpeechOutcome, summary: SpeechSummary?) {
        val analysis = SegmentSpeechEntity(
            segmentId = segment.segmentId,
            analyzer = VoiceVad.VERSION,
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
