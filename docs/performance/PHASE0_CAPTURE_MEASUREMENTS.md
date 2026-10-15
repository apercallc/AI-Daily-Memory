# Phase 0 capture: measurements still owed

The capture pipeline shipped with provisional parameters. Each row below is a claim the
code makes that has **not** been measured on hardware yet. Record results next to each row
(device, OS, build, duration, raw numbers) and adjust the parameter in the named file.

| What to measure | Why it matters | Parameter / code | How |
| --- | --- | --- | --- |
| Segment rotation wall time, p50/p99, on a low-end device under I/O load | The writer backlog (5 s) must cover the worst rotation (close + fsync + rename + dir sync + two DB writes) | `CaptureConfig.writerBacklogMs`, `AudioCaptureEngine.write` | Perfetto trace of `audio-writer`; count `audio_writer_backlog_full` logs over 24 h |
| Overruns over a 24 h run | Must be zero; the check is diagnostic until proven | `OverrunCheck` (`audio_overrun_suspected` log) | `adb logcat -s AudioCaptureEngine` during endurance runs |
| Capture-thread wakeups and CPU per hour | 10 reads/s for 24 h; a larger read may save battery | `CaptureConfig.readMs` (100 ms) | Perfetto CPU + wakeups at 100 / 250 / 500 ms |
| Battery drain per hour (screen off) | Phase 0 exit gate; feeds the battery budget | whole pipeline | `DebugTelemetry` samples every 5 min (debug builds) + Battery Historian |
| Bytes per hour recorded | Storage estimate is 15 MiB/h for AAC-LC 32 kbit/s + ADTS + AEAD | `AppContainer.storagePolicy.estimatedBytesPerHour` | Sum of sealed segment `byte_size` per hour |
| Segment length 30 s vs 60 s | Bounds crash loss vs rotation overhead (ADR-0007) | `CaptureConfig.segmentMs` | Compare rotation cost and loss per crash |
| Codec: AAC-LC ADTS vs Ogg/Opus | ADR-0007 prefers Opus pending a benchmark; AAC-ADTS is the provisional choice | `AacEncoder`, `SegmentFormat.codec` | CPU/battery per hour and transcription quality at equal bit rate |
| Silence threshold on real "quiet" rooms | 4 s of exact zeros must never trip on a real, quiet room | `CaptureConfig.silenceThresholdMs` | Quiet-room recordings on each target device |
| Screen-off capture without a wake lock | The service holds no wake lock of its own; the audio HAL is expected to keep delivering | `RecordingService` | 1 h screen-off run: gaps must be zero |
| Health-tracker cost in long sessions | `observeSession` re-reads every segment row after each write (~1,440 rows at 24 h) | `CaptureHealthTracker` | Query time at 1,440 rows; switch to SQL aggregates if it is material |
| `asShortBuffer()` view allocation per encoder input (~10/s) | Tiny objects; confirm GC impact is nil | `AacEncoder.encode` | Allocation tracker over 10 min |
| Voice-only share of recorded time | Measures local analysis and transcription workload; when a user selects their own provider, it can estimate their usage. It is not product metering. | `SpeechAnalyzer`, `VoiceVad` (`voice-v1`), `SpeechConfig` | Charge overnight after a normal day, then query `segment_speech` (below). `padded_ms / total_ms` is the share selected for transcription |
| Night analysis throughput and battery | The job must finish on one overnight charge | `SpeechAnalysisJobService`, `MediaCodecAacDecoder` | `speech_analysis_batch` logs (counts per batch) + Perfetto CPU while charging |
| Voice-VAD false positives and misses in noise | Detector accuracy must be established on labeled real recordings before it suppresses any audio (ADR-0022) | `VoiceVad` (`voice-v1`) | Compare against hand-labeled recordings in each environment; keep full audio for transcription until measured false-positive and false-negative rates support suppression |

### Reading the speech measurement (debug build)

```bash
adb shell run-as com.apercallc.aidailymemory sqlite3 databases/capture.db \
  "SELECT date(analyzed_at_epoch_ms/1000,'unixepoch') AS day,
          SUM(total_ms)/3600000.0 AS recorded_h,
          SUM(speech_ms)/3600000.0 AS speech_h,
          SUM(padded_ms)/3600000.0 AS transcribed_h,
          ROUND(1.0*SUM(padded_ms)/SUM(total_ms), 3) AS share
   FROM segment_speech WHERE outcome = 'OK' GROUP BY day;"
```

When the user selects their own transcription provider, `transcribed_h` may be combined with that
provider's published rate to estimate the user's usage cost. This is never operator-funded inference
or paid-plan metering. Only numbers are stored: no audio, text, or regions leave the phone in shadow
mode.
