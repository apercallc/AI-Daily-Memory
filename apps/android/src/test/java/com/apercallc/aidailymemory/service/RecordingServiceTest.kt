package com.apercallc.aidailymemory.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class RecordingServiceTest {
    private val app = ApplicationProvider.getApplicationContext<AiDailyMemoryApplication>()

    @Test
    fun `without the microphone permission the service is never started`() = runBlocking {
        // A startForegroundService whose startForeground then throws would crash the app.
        RecordingService.send(app, RecordingService.ACTION_START)

        assertNull(shadowOf(app as Application).nextStartedService)
        val controller = app.container.controller
        val state = withTimeout(5_000) {
            controller.state.first { it.problem == CaptureProblem.PERMISSION_DENIED }
        }
        assertEquals(RecordingStatus.IDLE, state.status)
    }

    @Test
    fun `with permissions a command starts the foreground service`() {
        shadowOf(app as Application).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )

        RecordingService.send(app, RecordingService.ACTION_START)

        val started = shadowOf(app as Application).nextStartedService
        assertEquals(RecordingService.ACTION_START, started?.action)
    }

    @Test
    fun `pause and stop never start a service that isn't running`() {
        // Regression: starting the service only to pause or stop it is a background
        // foreground-service start, which the platform can refuse and then crash the app for.
        shadowOf(app as Application).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )

        RecordingService.send(app, RecordingService.ACTION_PAUSE)
        RecordingService.send(app, RecordingService.ACTION_STOP)

        assertNull(shadowOf(app as Application).nextStartedService)
    }

    @Test
    fun `a sticky restart neither enters the foreground nor attaches to the controller`() {
        val controller = Robolectric.buildService(RecordingService::class.java).create()
        val service = controller.get()

        service.onStartCommand(null, 0, 1)

        assertNull(shadowOf(service).lastForegroundNotification)
        assertEquals(true, shadowOf(service).isStoppedBySelf)
    }
}
