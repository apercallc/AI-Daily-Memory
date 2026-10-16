package com.apercallc.aidailymemory.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.presentation.IndicatorVisibility
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class CaptureNotificationsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val system = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `reminder and resumed notices use different ids`() {
        assertNotEquals(CaptureNotifications.ID_REMINDER, CaptureNotifications.ID_RESUMED)
        val notifications = CaptureNotifications(context).also { it.createChannels() }

        notifications.morningReminder()
        notifications.resumedAfterSleep()

        val ids = shadowOf(system).allNotifications.size
        assertEquals(2, ids)
    }

    @Test
    fun `a partial daily report notice mentions the day is still processing`() {
        val notifications = CaptureNotifications(context).also { it.createChannels() }

        notifications.dailyReportReady(headline = "2 decisions", partial = true)

        val posted = shadowOf(system).allNotifications.single()
        assertEquals(
            context.getString(R.string.report_ready_body_partial, "2 decisions"),
            shadowOf(posted).contentText,
        )
    }

    @Test
    fun `a complete daily report notice just states the headline`() {
        val notifications = CaptureNotifications(context).also { it.createChannels() }

        notifications.dailyReportReady(headline = "2 decisions", partial = false)

        val posted = shadowOf(system).allNotifications.single()
        assertEquals("2 decisions", shadowOf(posted).contentText)
    }

    @Test
    fun `indicator is visible with default notifications and flagged when the channel is lowered`() {
        val notifications = CaptureNotifications(context).also { it.createChannels() }
        assertEquals(IndicatorVisibility.VISIBLE, notifications.indicatorVisibility())

        system.getNotificationChannel(CaptureNotifications.CHANNEL_STATUS).importance =
            NotificationManager.IMPORTANCE_LOW

        assertEquals(IndicatorVisibility.CHANNEL_LOWERED, notifications.indicatorVisibility())
    }
}
