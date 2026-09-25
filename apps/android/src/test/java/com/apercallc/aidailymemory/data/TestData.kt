package com.apercallc.aidailymemory.data

import com.apercallc.aidailymemory.recording.time.CaptureTime
import java.time.Instant
import java.time.ZoneId

internal fun captureTime(
    epochMs: Long = 1_790_000_000_000,
    elapsedMs: Long = 10_000,
    zone: String = "America/New_York",
    bootId: String = "boot-1",
) = CaptureTimeColumns.of(
    CaptureTime.of(Instant.ofEpochMilli(epochMs), ZoneId.of(zone), elapsedMs, bootId),
)

internal fun segmentEntity(
    sessionId: String = "session-1",
    sequence: Int = 0,
    state: String = "WRITING",
    byteSize: Long = 0,
) = SegmentEntity(
    segmentId = "$sessionId-$sequence",
    sessionId = sessionId,
    sequence = sequence,
    state = state,
    byteSize = byteSize,
    durationMs = 0,
    sha256 = null,
    acknowledgedAtEpochMs = null,
    defect = null,
    fileName = "$sessionId-$sequence.seg",
    codec = "aac-adts",
    sampleRateHz = 16_000,
    channelCount = 1,
    continuesPrevious = sequence > 0,
    start = captureTime(elapsedMs = 10_000L + sequence * 60_000L),
)
