package com.apercallc.aidailymemory.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.audio.DigitalSilenceDetector
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Capture parameters. Provisional until the Phase 0 device benchmark (ADR-0007). */
data class CaptureConfig(
    val sampleRateHz: Int = 16_000,
    val bitRate: Int = 32_000,
    val segmentMs: Long = 60_000,
    val silenceThresholdMs: Long = 4_000,
    /** Size of one blocking read; bounds how quickly a stop request is noticed. */
    val readMs: Long = 100,
) {
    val format = SegmentFormat(codec = "aac-lc-adts", sampleRateHz = sampleRateHz, channelCount = 1)
}

/** Live engine facts that feed capture health. */
data class EngineHealth(
    val lastSealedElapsedMs: Long? = null,
    val microphoneSilent: Boolean = false,
)

/**
 * Microphone → AAC → encrypted, sealed segments. One session at a time, on one dedicated
 * thread. It never decides state: it reports what it observed through [onReport], and the
 * controller turns that into reducer signals (EngineSignals).
 *
 * Failure policy: anything that stops real audio from being saved ends the capture loop with
 * [EngineReport.Failed]. Nothing is retried here; retries are the recovery policy's job.
 */
class AudioCaptureEngine(
    private val audioManager: AudioManager,
    private val store: SegmentStore,
    private val storage: suspend () -> StorageHealth,
    private val elapsedRealtimeMs: () -> Long,
    private val scope: CoroutineScope,
    private val onReport: (EngineReport) -> Unit,
    private val config: CaptureConfig = CaptureConfig(),
) {
    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "audio-capture")
    }.asCoroutineDispatcher()
    private val lock = Mutex()
    private var job: Job? = null

    @Volatile private var stopRequested = false

    @Volatile private var silencedBySystem = false

    private val _health = MutableStateFlow(EngineHealth())
    val health: StateFlow<EngineHealth> = _health.asStateFlow()

    /** Opens the mic for [sessionId]. A second open while running is ignored and logged. */
    suspend fun open(sessionId: SessionId) = lock.withLock {
        if (job?.isActive == true) {
            Log.w(TAG, "engine_open_while_running")
            return@withLock
        }
        stopRequested = false
        silencedBySystem = false
        job = scope.launch(dispatcher) { capture(sessionId) }
    }

    /** Stops capture and waits until the in-progress segment is sealed. Idempotent. */
    suspend fun close() = lock.withLock {
        stopRequested = true
        job?.join()
        job = null
    }

    @SuppressLint("MissingPermission") // Checked by the caller; a revocation is caught below.
    private suspend fun capture(sessionId: SessionId) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (isInCall()) return fail(sessionId, CaptureProblem.PHONE_CALL)
        if (storage().mustStopCapture) return fail(sessionId, CaptureProblem.STORAGE_LOW)

        val readSamples = (config.sampleRateHz * config.readMs / MILLIS).toInt()
        val record = openRecord(readSamples) ?: return fail(
            sessionId,
            CaptureProblem.MICROPHONE_UNAVAILABLE,
        )
        val callback = SilencedCallback(record.audioSessionId)
        audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        try {
            val encoder = try {
                AacEncoder(config.sampleRateHz, 1, config.bitRate, readSamples * BYTES_PER_SAMPLE)
            } catch (e: IOException) {
                Log.e(TAG, "encoder_unavailable", e)
                return fail(sessionId, CaptureProblem.UNKNOWN)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "encoder_unavailable", e)
                return fail(sessionId, CaptureProblem.UNKNOWN)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "encoder_unavailable", e)
                return fail(sessionId, CaptureProblem.UNKNOWN)
            }
            encoder.use { loop(sessionId, record, it, readSamples) }
        } finally {
            audioManager.unregisterAudioRecordingCallback(callback)
            record.stop()
            record.release()
            _health.value = _health.value.copy(microphoneSilent = false)
        }
    }

    private suspend fun loop(
        sessionId: SessionId,
        record: AudioRecord,
        encoder: AacEncoder,
        readSamples: Int,
    ) {
        val buffer = ShortArray(readSamples)
        val silence = DigitalSilenceDetector(config.sampleRateHz, config.silenceThresholdMs)
        var segment = beginOrFail(sessionId, continuesPrevious = false) ?: return
        var announced = false
        var failure: CaptureProblem? = null
        val sink: (ByteBuffer, Int) -> Unit = { data, size -> segment.writeFrame(data, size) }

        try {
            while (!stopRequested) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    Log.w(TAG, "audio_read_failed", IllegalStateException("code $read"))
                    failure = CaptureProblem.MICROPHONE_UNAVAILABLE
                    break
                }
                if (read == 0) continue
                if (!announced) {
                    announced = true
                    onReport(EngineReport.AudioFlowing(sessionId))
                }
                val silent = silence.process(buffer, read)
                if (silent != _health.value.microphoneSilent) {
                    _health.value = _health.value.copy(microphoneSilent = silent)
                }
                if (silent || silencedBySystem) {
                    // Never record digital silence as if it were captured audio (ADR-0010).
                    failure = if (isInCall()) {
                        CaptureProblem.PHONE_CALL
                    } else {
                        CaptureProblem.MICROPHONE_UNAVAILABLE
                    }
                    break
                }
                encoder.encode(buffer, read, sink)
                if (segment.durationMs >= config.segmentMs) {
                    seal(segment)
                    if (storage().mustStopCapture) {
                        failure = CaptureProblem.STORAGE_LOW
                        return
                    }
                    segment = beginOrFail(sessionId, continuesPrevious = true) ?: return
                }
            }
            encoder.finish(sink)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "encoder_failed", e)
            failure = CaptureProblem.UNKNOWN
        } catch (e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder_failed", e)
            failure = CaptureProblem.UNKNOWN
        } catch (e: IOException) {
            Log.e(TAG, "segment_write_failed", e)
            failure = CaptureProblem.STORAGE_LOW.takeIf { storage().mustStopCapture }
                ?: CaptureProblem.UNKNOWN
        } finally {
            // Seal whatever was captured, even on failure: it is real audio. A segment that
            // already sealed during rotation is left as it is.
            if (!segment.isClosed) seal(segment)
            failure?.let { fail(sessionId, it) }
        }
    }

    private suspend fun seal(segment: OpenSegment) {
        store.seal(segment)
        _health.value = _health.value.copy(lastSealedElapsedMs = elapsedRealtimeMs())
    }

    private suspend fun beginOrFail(sessionId: SessionId, continuesPrevious: Boolean): OpenSegment? =
        try {
            store.begin(sessionId, continuesPrevious, config.format)
        } catch (e: EncryptionUnavailableException) {
            Log.e(TAG, "segment_encryption_unavailable", e)
            fail(sessionId, CaptureProblem.ENCRYPTION_UNAVAILABLE)
            null
        } catch (e: IOException) {
            Log.e(TAG, "segment_begin_failed", e)
            val problem = if (storage().mustStopCapture) {
                CaptureProblem.STORAGE_LOW
            } else {
                CaptureProblem.UNKNOWN
            }
            fail(sessionId, problem)
            null
        }

    @SuppressLint("MissingPermission")
    private fun openRecord(readSamples: Int): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(config.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                // Headroom for a slow segment rotation (a database write) without overruns.
                .setBufferSizeInBytes(maxOf(minBuffer, readSamples * BYTES_PER_SAMPLE) * 4)
                .build()
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "audio_record_unsupported", e)
            return null
        } catch (e: SecurityException) {
            Log.w(TAG, "audio_record_permission", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "audio_record_start_failed", e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return null
        }
        record.addOnRoutingChangedListener(
            // Route changes (headset, Bluetooth) keep capturing on the new device; recorded so
            // endurance tests can correlate them with gaps.
            { Log.i(TAG, "audio_route_changed") },
            Handler(Looper.getMainLooper()),
        )
        return record
    }

    private fun isInCall(): Boolean = audioManager.mode in CALL_MODES

    private fun fail(sessionId: SessionId, problem: CaptureProblem) {
        onReport(EngineReport.Failed(sessionId, problem))
    }

    /** Another app or a call took the microphone: the platform now feeds us silence. */
    private inner class SilencedCallback(private val audioSessionId: Int) :
        AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val ours = configs.firstOrNull { it.clientAudioSessionId == audioSessionId } ?: return
            if (ours.isClientSilenced) {
                Log.i(TAG, "audio_client_silenced")
                silencedBySystem = true
            }
        }
    }

    private companion object {
        const val TAG = "AudioCaptureEngine"
        const val BYTES_PER_SAMPLE = 2
        const val MILLIS = 1000L
        val CALL_MODES = setOf(
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
        )
    }
}
