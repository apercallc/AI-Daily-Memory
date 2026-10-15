package com.apercallc.aidailymemory.capture

import android.annotation.SuppressLint
import android.database.sqlite.SQLiteException
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRouting
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.audio.DigitalSilenceDetector
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.segment.SegmentDefect
import com.apercallc.aidailymemory.recording.segment.SegmentState
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
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
    /** Platform capture buffer. Covers scheduling hiccups on the reader thread. */
    val recordBufferMs: Long = 2_000,
    /**
     * How far the writer (encode, seal, rotate) may fall behind the reader before capture
     * stops. A slow fsync is absorbed here instead of in the platform buffer.
     */
    val writerBacklogMs: Long = 5_000,
) {
    val format = SegmentFormat(codec = "aac-lc-adts", sampleRateHz = sampleRateHz, channelCount = 1)
    val readSamples: Int get() = (sampleRateHz * readMs / MILLIS).toInt()
    val poolSize: Int get() = (writerBacklogMs / readMs).toInt().coerceAtLeast(2)

    private companion object {
        const val MILLIS = 1000L
    }
}

/**
 * An engine report tagged with the microphone open it came from. Reports from an earlier open
 * (queued before a quick Pause → Resume) must never confirm or fail the current one.
 */
data class TaggedReport(val generation: Long, val report: EngineReport)

/** Live engine facts that feed capture health. */
data class EngineHealth(
    val lastSealedElapsedMs: Long? = null,
    val microphoneSilent: Boolean = false,
)

/**
 * Microphone → AAC → encrypted, sealed segments, for one session at a time.
 *
 * Two threads, so disk latency can never cost audio:
 * - **reader** only reads PCM and checks for silence, into buffers from a fixed pool;
 * - **writer** encodes, writes, and rotates segments (fsync, rename, database).
 * If the writer falls [CaptureConfig.writerBacklogMs] behind, the pool runs dry and capture
 * stops with a visible interruption: audio is never dropped silently.
 *
 * It never decides state: it reports observations through [onReport], and the controller
 * turns them into reducer signals (EngineSignals). Nothing is retried here.
 */
class AudioCaptureEngine(
    private val audioManager: AudioManager,
    private val store: SegmentStore,
    private val storage: suspend () -> StorageHealth,
    private val elapsedRealtimeMs: () -> Long,
    private val scope: CoroutineScope,
    private val onReport: (TaggedReport) -> Unit,
    private val config: CaptureConfig = CaptureConfig(),
) {
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "audio-reader") }
        .asCoroutineDispatcher()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "audio-writer") }
        .asCoroutineDispatcher()
    private val lock = Mutex()
    private var job: Job? = null

    @Volatile private var stopRequested = false

    @Volatile private var silencedBySystem = false

    /** Incremented on every open; see [TaggedReport]. */
    @Volatile var generation = 0L
        private set

    private val _health = MutableStateFlow(EngineHealth())
    val health: StateFlow<EngineHealth> = _health.asStateFlow()

    /** One pooled PCM buffer and how many samples it holds. */
    private class Chunk(val samples: ShortArray, var count: Int = 0)

    /**
     * Opens the mic for [sessionId]. Every open ends in exactly one report: audio flowing, or
     * a failure. That includes unexpected crashes of the capture job, so the controller can
     * never be left claiming capture that stopped.
     */
    suspend fun open(sessionId: SessionId) = lock.withLock {
        if (job?.isActive == true) {
            // Only reachable if an open was planned without a close; say so instead of
            // leaving the controller waiting for a confirmation that never comes.
            Log.e(TAG, "engine_open_while_running")
            val failed = EngineReport.Failed(sessionId, CaptureProblem.UNKNOWN)
            onReport(TaggedReport(generation, failed))
            return@withLock
        }
        stopRequested = false
        silencedBySystem = false
        val openGeneration = ++generation
        val report: (EngineReport) -> Unit = { onReport(TaggedReport(openGeneration, it)) }
        job = scope.launch(reader) {
            val problem = try {
                capture(sessionId, report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Deliberately broad and never silent: whatever broke, the user must see that
                // recording stopped, and the operator gets the type and frame.
                logFailure(TAG, "capture_crashed", e)
                CaptureProblem.UNKNOWN
            }
            problem?.let { report(EngineReport.Failed(sessionId, it)) }
        }
    }

    /** Stops capture and waits until the in-progress segment is sealed. Idempotent. */
    suspend fun close() = lock.withLock {
        stopRequested = true
        job?.join()
        job = null
    }

    /** Runs one capture; returns the problem that ended it, or null for a requested stop. */
    @SuppressLint("MissingPermission") // Checked by the caller; a revocation is caught below.
    private suspend fun capture(
        sessionId: SessionId,
        report: (EngineReport) -> Unit,
    ): CaptureProblem? {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        if (isInCall()) return CaptureProblem.PHONE_CALL
        if (storage().mustStopCapture) return CaptureProblem.STORAGE_LOW
        // Keystore unwrap before the mic opens, so it never eats into the audio buffer.
        try {
            store.warmUp()
        } catch (e: EncryptionUnavailableException) {
            logFailure(TAG, "segment_encryption_unavailable", e)
            return CaptureProblem.ENCRYPTION_UNAVAILABLE
        }
        val openedRecord = openRecord() ?: return CaptureProblem.MICROPHONE_UNAVAILABLE
        val record = openedRecord.record
        val callback = SilencedCallback(record.audioSessionId)
        var callbackRegistered = false
        try {
            audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            callbackRegistered = true
            val encoder = try {
                AacEncoder(config.sampleRateHz, 1, config.bitRate, config.readSamples * 2)
            } catch (e: IOException) {
                logFailure(TAG, "encoder_unavailable", e)
                return CaptureProblem.UNKNOWN
            } catch (e: IllegalStateException) {
                logFailure(TAG, "encoder_unavailable", e)
                return CaptureProblem.UNKNOWN
            } catch (e: IllegalArgumentException) {
                logFailure(TAG, "encoder_unavailable", e)
                return CaptureProblem.UNKNOWN
            }
            return encoder.use { pipeline(sessionId, record, it, report) }
        } finally {
            val failures = runCaptureCleanup(
                listOfNotNull(
                    if (callbackRegistered) "audio_callback_unregister" to {
                        audioManager.unregisterAudioRecordingCallback(callback)
                    } else {
                        null
                    },
                    "audio_route_listener_remove" to {
                        record.removeOnRoutingChangedListener(openedRecord.routeListener)
                    },
                    "audio_record_stop" to { record.stop() },
                    "audio_record_release" to { record.release() },
                ),
            )
            _health.value = _health.value.copy(microphoneSilent = false)
            failures.forEach { (event, error) -> logFailure(TAG, event, error) }
        }
    }

    private suspend fun pipeline(
        sessionId: SessionId,
        record: AudioRecord,
        encoder: AacEncoder,
        report: (EngineReport) -> Unit,
    ): CaptureProblem? = coroutineScope {
        val problem = AtomicReference<CaptureProblem?>(null)
        val pool = Channel<Chunk>(config.poolSize)
        repeat(config.poolSize) { pool.trySend(Chunk(ShortArray(config.readSamples))) }
        val filled = Channel<Chunk>(config.poolSize)

        val writing = launch(writer) {
            val failure = write(sessionId, encoder, filled, pool)
            if (failure != null) problem.compareAndSet(null, failure)
        }
        read(sessionId, record, filled, pool, problem, report)
        filled.close()
        writing.join()
        problem.get()
    }

    /** Reader thread: PCM in, silence checks, nothing that touches the disk. */
    private fun read(
        sessionId: SessionId,
        record: AudioRecord,
        filled: Channel<Chunk>,
        pool: Channel<Chunk>,
        problem: AtomicReference<CaptureProblem?>,
        report: (EngineReport) -> Unit,
    ) {
        val silence = DigitalSilenceDetector(config.sampleRateHz, config.silenceThresholdMs)
        val overrun = OverrunCheck(record)
        var announced = false
        while (!stopRequested && problem.get() == null) {
            val chunk = pool.tryReceive().getOrNull()
            if (chunk == null) {
                // The writer is a full backlog behind (storage stalled). Stop visibly rather
                // than drop audio without a record of it.
                Log.e(TAG, "audio_writer_backlog_full")
                problem.compareAndSet(null, CaptureProblem.UNKNOWN)
                break
            }
            val read = record.read(chunk.samples, 0, chunk.samples.size)
            if (read <= 0) {
                pool.trySend(chunk)
                if (read == 0) continue
                Log.w(TAG, "audio_read_failed code=$read")
                problem.compareAndSet(null, CaptureProblem.MICROPHONE_UNAVAILABLE)
                break
            }
            // Only real audio confirms capture. A blocked or silenced mic delivers zeros from
            // the first buffer; announcing that as "flowing" would show Recording while nothing
            // is captured and reset the recovery budget on every retry.
            if (!announced && !silencedBySystem && hasSignal(chunk.samples, read)) {
                announced = true
                report(EngineReport.AudioFlowing(sessionId))
            }
            val silent = silence.process(chunk.samples, read)
            if (silent != _health.value.microphoneSilent) {
                _health.value = _health.value.copy(microphoneSilent = silent)
            }
            if (silent || silencedBySystem) {
                // Never record digital silence as if it were captured audio (ADR-0010).
                pool.trySend(chunk)
                val cause = if (isInCall()) {
                    CaptureProblem.PHONE_CALL
                } else {
                    CaptureProblem.MICROPHONE_UNAVAILABLE
                }
                problem.compareAndSet(null, cause)
                break
            }
            chunk.count = read
            // Capacity equals the pool size, so this cannot fail while the chunk is ours.
            filled.trySend(chunk)
            overrun.afterRead(read)
        }
    }

    /** Writer thread: encode, write frames, seal and rotate segments. */
    private suspend fun write(
        sessionId: SessionId,
        encoder: AacEncoder,
        filled: Channel<Chunk>,
        pool: Channel<Chunk>,
    ): CaptureProblem? {
        var segment = when (val opened = begin(sessionId, continuesPrevious = false)) {
            is Opened.Segment -> opened.segment
            is Opened.Failed -> return opened.problem
        }
        val sink: (ByteBuffer, Int) -> Unit = { data, size -> segment.writeFrame(data, size) }
        try {
            for (chunk in filled) {
                encoder.encode(chunk.samples, chunk.count, sink)
                pool.trySend(chunk)
                if (segment.durationMs >= config.segmentMs) {
                    sealOrProblem(segment)?.let { return it }
                    if (storage().mustStopCapture) return CaptureProblem.STORAGE_LOW
                    segment = when (val next = begin(sessionId, continuesPrevious = true)) {
                        is Opened.Segment -> next.segment
                        is Opened.Failed -> return next.problem
                    }
                }
            }
            encoder.finish(sink)
            return sealOrProblem(segment)
        } catch (e: IllegalStateException) {
            logFailure(TAG, "encoder_failed", e)
            return CaptureProblem.UNKNOWN
        } catch (e: MediaCodec.CodecException) {
            logFailure(TAG, "encoder_failed", e)
            return CaptureProblem.UNKNOWN
        } catch (e: IOException) {
            logFailure(TAG, "segment_write_failed", e)
            return storageOr(CaptureProblem.UNKNOWN)
        } catch (e: SQLiteException) {
            // Includes SQLiteFullException: the segment row could not be written.
            logFailure(TAG, "segment_record_failed", e)
            return storageOr(CaptureProblem.UNKNOWN)
        } finally {
            // Failure paths: seal whatever was captured, it is real audio. Its own outcome
            // cannot change the problem already being reported.
            if (!segment.isClosed) sealOrProblem(segment)
        }
    }

    /**
     * Seals [segment]; returns a problem if it did not become SEALED because of a write
     * failure. An empty segment (stopped before any frame) is quarantined by design and is
     * not a failure. Health only counts real seals as progress.
     */
    private suspend fun sealOrProblem(segment: OpenSegment): CaptureProblem? {
        val sealed = store.seal(segment)
        return when {
            sealed.state == SegmentState.SEALED.name -> {
                _health.value = _health.value.copy(lastSealedElapsedMs = elapsedRealtimeMs())
                null
            }
            sealed.defect == SegmentDefect.INCOMPLETE.name -> storageOr(CaptureProblem.UNKNOWN)
            else -> null
        }
    }

    private suspend fun storageOr(problem: CaptureProblem): CaptureProblem =
        if (storage().mustStopCapture) CaptureProblem.STORAGE_LOW else problem

    private sealed interface Opened {
        class Segment(val segment: OpenSegment) : Opened
        class Failed(val problem: CaptureProblem) : Opened
    }

    private suspend fun begin(sessionId: SessionId, continuesPrevious: Boolean): Opened = try {
        Opened.Segment(store.begin(sessionId, continuesPrevious, config.format))
    } catch (e: EncryptionUnavailableException) {
        logFailure(TAG, "segment_encryption_unavailable", e)
        Opened.Failed(CaptureProblem.ENCRYPTION_UNAVAILABLE)
    } catch (e: IOException) {
        logFailure(TAG, "segment_begin_failed", e)
        Opened.Failed(storageOr(CaptureProblem.UNKNOWN))
    } catch (e: SQLiteException) {
        logFailure(TAG, "segment_begin_failed", e)
        Opened.Failed(storageOr(CaptureProblem.UNKNOWN))
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(): OpenedRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return null
        val bufferBytes = (config.sampleRateHz * BYTES_PER_SAMPLE * config.recordBufferMs / 1000)
            .toInt()
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
                .setBufferSizeInBytes(maxOf(minBuffer, bufferBytes))
                .build()
        } catch (e: UnsupportedOperationException) {
            logFailure(TAG, "audio_record_unsupported", e)
            return null
        } catch (e: SecurityException) {
            logFailure(TAG, "audio_record_permission", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            logFailure(TAG, "audio_record_start_failed", e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            record.release()
            return null
        }
        val routeListener = AudioRouting.OnRoutingChangedListener {
            // Route changes (headset, Bluetooth) keep capturing on the new device; logged so
            // endurance tests can correlate them with gaps.
            Log.i(TAG, "audio_route_changed")
        }
        try {
            record.addOnRoutingChangedListener(routeListener, Handler(Looper.getMainLooper()))
        } catch (error: RuntimeException) {
            logFailure(TAG, "audio_route_listener_registration_failed", error)
            val failures = runCaptureCleanup(
                listOf(
                    "audio_route_listener_remove" to {
                        record.removeOnRoutingChangedListener(routeListener)
                    },
                    "audio_record_stop" to { record.stop() },
                    "audio_record_release" to { record.release() },
                ),
            )
            failures.forEach { (event, failure) -> logFailure(TAG, event, failure) }
            return null
        }
        return OpenedRecord(record, routeListener)
    }

    private fun isInCall(): Boolean = audioManager.mode in CALL_MODES

    private fun hasSignal(samples: ShortArray, count: Int): Boolean {
        for (i in 0 until count) if (samples[i].toInt() != 0) return true
        return false
    }

    /**
     * Diagnostic: compares frames the platform captured with frames we read. With a 2 s
     * buffer and a reader that never touches the disk this should never fire; it exists so
     * endurance runs can prove that (docs/performance/). Checked about once a second.
     */
    private inner class OverrunCheck(private val record: AudioRecord) {
        private val timestamp = AudioTimestamp()
        private val bufferFrames = record.bufferSizeInFrames.toLong()
        private var framesRead = 0L
        private var sinceCheck = 0

        fun afterRead(frames: Int) {
            framesRead += frames
            sinceCheck += frames
            if (sinceCheck < config.sampleRateHz) return
            sinceCheck = 0
            val status = record.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
            if (status == AudioRecord.SUCCESS &&
                timestamp.framePosition - framesRead > bufferFrames
            ) {
                Log.w(TAG, "audio_overrun_suspected")
            }
        }
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

    private data class OpenedRecord(
        val record: AudioRecord,
        val routeListener: AudioRouting.OnRoutingChangedListener,
    )

    private companion object {
        const val TAG = "AudioCaptureEngine"
        const val BYTES_PER_SAMPLE = 2
        val CALL_MODES = setOf(
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
        )
    }
}

internal fun runCaptureCleanup(
    actions: List<Pair<String, () -> Unit>>,
): List<Pair<String, Exception>> {
    val failures = mutableListOf<Pair<String, Exception>>()
    actions.forEach { (event, action) ->
        try {
            action()
        } catch (error: Exception) {
            failures += event to error
        }
    }
    return failures
}
