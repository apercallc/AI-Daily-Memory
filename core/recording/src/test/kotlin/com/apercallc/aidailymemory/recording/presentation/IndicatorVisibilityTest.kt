package com.apercallc.aidailymemory.recording.presentation

import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IndicatorVisibilityTest {
    private fun evaluate(enabled: Boolean = true, importance: Int? = 3) =
        IndicatorVisibilityPolicy.evaluate(enabled, importance)

    @Test
    fun `visible when enabled and channel is default or higher or missing`() {
        assertEquals(IndicatorVisibility.VISIBLE, evaluate(importance = 3))
        assertEquals(IndicatorVisibility.VISIBLE, evaluate(importance = 4))
        assertEquals(IndicatorVisibility.VISIBLE, evaluate(importance = null))
    }

    @Test
    fun `app notifications off wins over the channel state`() {
        assertEquals(IndicatorVisibility.NOTIFICATIONS_OFF, evaluate(enabled = false, importance = 0))
    }

    @Test
    fun `blocked and lowered channels are distinguished`() {
        assertEquals(IndicatorVisibility.CHANNEL_BLOCKED, evaluate(importance = 0))
        assertEquals(IndicatorVisibility.CHANNEL_LOWERED, evaluate(importance = 1))
        assertEquals(IndicatorVisibility.CHANNEL_LOWERED, evaluate(importance = 2))
    }

    private fun model(status: RecordingStatus) =
        CaptureStatusPresenter.present(
            RecordingState(
                status = status,
                sessionId = if (status == RecordingStatus.IDLE) null else SessionId("s"),
                revision = 1,
                problem = null,
            ),
            CaptureHealth.Unknown,
        )

    @Test
    fun `banner shows only while a session is open or interrupted`() {
        val hidden = IndicatorVisibility.NOTIFICATIONS_OFF
        assertTrue(IndicatorVisibilityPolicy.showBanner(hidden, model(RecordingStatus.RECORDING)))
        assertTrue(IndicatorVisibilityPolicy.showBanner(hidden, model(RecordingStatus.INTERRUPTED)))
        assertFalse(IndicatorVisibilityPolicy.showBanner(hidden, model(RecordingStatus.IDLE)))
        assertFalse(IndicatorVisibilityPolicy.showBanner(IndicatorVisibility.VISIBLE, model(RecordingStatus.RECORDING)))
    }
}
