# ADR-0015: Transcribe Speech, Not the Day — Speech Gating and Night Processing

- Status: Accepted — makes README "Speech Detection" concrete
- Date: 2026-09-25

## Context

A recorded day is ~16 h; most of it is silence, noise, or the user alone. Transcribing all of
it wastes provider cost, upload data, and battery, and puts bystander audio in the pipeline
for no benefit. But README is right that missing one important sentence is worse than
transcribing extra audio, and "important" cannot be judged before the words are known.

## Decision

1. **Gate on speech, not importance.** Every region that contains speech is transcribed.
   Importance is decided later by extraction and the report. Silence and non-speech are the
   cost lever; that is where most of the day goes.
2. **Keep the recording hot path unchanged.** Daytime capture stays "PCM → AAC segment"
   (ADR-0007) with the existing digital-silence detector. No neural model runs all day.
3. **Process at night, on the charger.** A WorkManager job (charging + unmetered + battery not
   low) runs after the memory day ends (ADR-0012):
   decode sealed segments (MediaCodec) → VAD → speaker spans (ADR-0016) → cut speech clips →
   upload. Deadline fallback: if the constraints never hold, run at wake time + 2 h on any
   network, and mark the report "late".
4. **VAD:** a cheap energy gate first; then Silero VAD (ONNX Runtime Android, stable
   release) only on frames above the energy floor. Regions get **1.0 s pre-roll, 2.0 s
   post-roll**, gaps under **3 s** merge, and blips under **0.3 s** are kept but marked low
   confidence. All thresholds are config, tuned on a labeled set; the starting values are
   assumptions until measured.
5. **Clips are cut on AAC frame boundaries** (`AdtsScanner`, 64 ms frames at 16 kHz). There's
   no re-encode, and each clip carries `segment_id` + offset so timestamps map back exactly
   (ADR-0006). Regions that span segments are stitched on the session timeline.
6. **Fallbacks go to "more", never "less".** If the VAD model fails to load or crashes, the
   energy gate alone decides, and capture health records it. The **High-fidelity** setting
   uploads all non-silent audio.
7. **Honest coverage.** The report distinguishes *not recording* (paused, quiet time, asleep),
   *recording, no speech*, *speech not yet processed*, and *speech over plan limit*. Nothing
   is dropped silently.
8. **Plan limits are server-side** (AGENTS rule 3). Speech over the plan's daily minutes is
   left untranscribed and shown as a gap. It is kept on the phone under the normal retention
   rules so a later upgrade can process it.
9. Optional **"Only conversations I'm in"** (off by default): speech regions with no owner
   voice (ADR-0016) for **10+ minutes** are not uploaded. This cuts cost and bystander
   exposure, but misses meetings and lectures where the user only listens, so it's opt-in.

## Consequences

- Daytime battery cost is capture only. Night work runs on the charger.
- A full day's audio stays on the phone until night. At 32 kbps that's ~14 MB per recorded
  hour, ~230 MB for 16 h, which is already covered by storage-pressure handling.
- The report arrives after the night job, which fits "due at bedtime, delivered at wake"
  (ADR-0012). A "process during the day" mode would need inline VAD and is deferred until
  users ask for fresher reports.
- Night decode and VAD throughput must be measured on the device matrix (docs/performance)
  before the thresholds and deadline are fixed.

## Edge cases designed for

- TV, radio, podcasts, or music with vocals all day: transcribed as speech. The report
  groups long monologues with no owner voice as "Background media?" and collapses them. The
  opt-in in item 9 stops them being uploaded.
- Café, car, or loud office: VAD fires often. The plan limit and the owner-voice option bound
  the cost.
- Phone in a pocket or bag: muffled audio. Capture health gets a "muffled" signal (spectral
  tilt), which is distinct from silence.
- Whispered or distant speech: VAD false negatives. Measured on the labeled set, with
  High-fidelity as the escape hatch.
- Phone never charged overnight, or no Wi-Fi: the deadline fallback runs and the report is
  marked late.
- Clock or time-zone change mid-region: offsets are monotonic per segment (ADR-0006).
