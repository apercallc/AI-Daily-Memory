package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.Headline
import com.apercallc.aidailymemory.recording.presentation.PrimaryAction
import com.apercallc.aidailymemory.recording.presentation.StatusTone
import kotlin.test.Test
import kotlin.test.assertEquals

class RecordingTileLookTest {
    private fun model(headline: Headline) = CaptureStatusModel(
        headline = headline,
        tone = StatusTone.NEUTRAL,
        primaryAction = PrimaryAction.NONE,
        canStop = false,
        canMark = false,
        canRetry = false,
        problem = if (headline == Headline.INTERRUPTED) CaptureProblem.PHONE_CALL else null,
        issues = emptyList(),
    )

    @Test
    fun `the tile is lit only while audio is being saved`() {
        Headline.entries.forEach { headline ->
            val expected = if (headline == Headline.RECORDING) TileLook.ACTIVE else TileLook.INACTIVE
            assertEquals(expected, tileLook(model(headline)), "$headline")
        }
    }
}
