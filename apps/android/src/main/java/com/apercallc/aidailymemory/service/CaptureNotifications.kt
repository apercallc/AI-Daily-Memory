package com.apercallc.aidailymemory.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.apercallc.aidailymemory.MainActivity
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.Headline
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusPresenter
import com.apercallc.aidailymemory.recording.presentation.IndicatorVisibility
import com.apercallc.aidailymemory.recording.presentation.IndicatorVisibilityPolicy
import com.apercallc.aidailymemory.recording.presentation.PrimaryAction
import com.apercallc.aidailymemory.ui.CaptureStrings

/**
 * Recording notifications. The status notification is the foreground-service notification and
 * the always-visible recording indicator (AGENTS.md rule 1): it is public on the lock screen
 * (it names a state, never content) and always carries Pause/Resume and Stop.
 */
class CaptureNotifications(private val context: Context) {
    private val manager = NotificationManagerCompat.from(context)

    fun createChannels() {
        val system = context.getSystemService(NotificationManager::class.java)
        // The first status channel was IMPORTANCE_LOW, a "silent" notification that users can
        // hide from the status bar and lock screen. Importance can't be raised on an existing
        // channel, so it is replaced.
        system.deleteNotificationChannel(LEGACY_CHANNEL_STATUS)
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status_name),
                // Default importance keeps the indicator in the status bar and on the lock
                // screen even with "hide silent notifications" on; setSilent() keeps it quiet.
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                setSound(null, null)
                enableVibration(false)
                description = context.getString(R.string.channel_status_description)
                setShowBadge(false)
            },
        )
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.channel_alerts_description) },
        )
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RHYTHM,
                context.getString(R.string.channel_rhythm_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.channel_rhythm_description) },
        )
    }

    /**
     * Wake time after a day ended at bedtime (ADR-0012). Only a suggestion: recording starts
     * when the user taps Start, never by itself.
     */
    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled().
    fun morningReminder() {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RHYTHM)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.getString(R.string.reminder_body))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(activityAction(R.string.action_start, RecordingService.ACTION_START))
            .build()
        manager.notify(ID_REMINDER, notification)
    }

    fun cancelMorningReminder() = manager.cancel(ID_REMINDER)

    /**
     * Wake time after a day that ended at bedtime, and that day's report finished generating
     * (TODO.md "deliver them at wake time"): names what the day held, and says plainly when
     * some of it is still processing rather than implying the report is the final word.
     */
    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled().
    fun dailyReportReady(headline: String, partial: Boolean) {
        if (!manager.areNotificationsEnabled()) return
        val body = if (partial) {
            context.getString(R.string.report_ready_body_partial, headline)
        } else {
            context.getString(R.string.report_ready_body, headline)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_RHYTHM)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(R.string.report_ready_title))
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(ID_REPORT_READY, notification)
    }

    /**
     * Recording reopened by itself after a night paused (the day isn't ended at bedtime). The
     * status notification is silent, so say so once, audibly, on the rhythm channel.
     */
    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled().
    fun resumedAfterSleep() {
        if (!manager.areNotificationsEnabled()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_RHYTHM)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(R.string.resumed_after_sleep_title))
            .setContentText(context.getString(R.string.resumed_after_sleep_body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(ID_RESUMED, notification)
    }

    /**
     * Whether the recording indicator can actually be seen: app notifications on *and* the
     * status channel not blocked. The service checks this before and during capture.
     */
    fun canShowStatus(): Boolean {
        if (!manager.areNotificationsEnabled()) return false
        val channel = manager.getNotificationChannel(CHANNEL_STATUS) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    /**
     * What the user's notification settings do to the recording indicator, for the in-app
     * warning. The alert and status notifications cannot report their own absence.
     */
    fun indicatorVisibility(): IndicatorVisibility = IndicatorVisibilityPolicy.evaluate(
        appNotificationsEnabled = manager.areNotificationsEnabled(),
        channelImportance = manager.getNotificationChannel(CHANNEL_STATUS)?.importance,
    )

    fun status(model: CaptureStatusModel): Notification =
        builder(CHANNEL_STATUS, model)
            .setOngoing(true)
            // Android 14+ lets users swipe away foreground-service notifications. Dismissing
            // the recording indicator re-posts it; pausing is the way to stop recording.
            .setDeleteIntent(broadcast(RecordingService.ACTION_SHOW_STATUS))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .also { showRecordingTimer(it, model) }
            .also { addActions(it, model) }
            .build()

    @Volatile private var recordingSince: Long? = null

    /**
     * While audio is really being saved, the notification counts up how long this stretch has
     * been recording. It disappears the moment recording stops, pauses or is interrupted, so a
     * running clock always means the microphone is on.
     */
    private fun showRecordingTimer(builder: NotificationCompat.Builder, model: CaptureStatusModel) {
        if (model.headline != Headline.RECORDING) {
            recordingSince = null
            return
        }
        val since = recordingSince ?: System.currentTimeMillis().also { recordingSince = it }
        builder.setWhen(since).setShowWhen(true).setUsesChronometer(true)
    }

    /**
     * A session needs the user while no service runs (after a restart, or when automatic
     * recovery gave up): "tap to resume". Never auto-resumes (ADR-0010).
     */
    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled().
    fun alert(state: RecordingState) {
        if (!manager.areNotificationsEnabled()) return
        val model = CaptureStatusPresenter.present(state, CaptureHealth.Unknown)
        val notification = builder(CHANNEL_ALERTS, model)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .also { addActions(it, model, fromAlert = true) }
            .build()
        manager.notify(ID_ALERT, notification)
    }

    fun cancelAlert() = manager.cancel(ID_ALERT)

    @SuppressLint("MissingPermission") // Only called while the service is in the foreground.
    fun update(model: CaptureStatusModel) {
        if (manager.areNotificationsEnabled()) manager.notify(ID_STATUS, status(model))
    }

    /** Re-posts a dismissed indicator while a session can still capture or resume. */
    fun repostStatusIfActive(model: CaptureStatusModel) {
        if (model.canStop) update(model)
    }

    private fun builder(channel: String, model: CaptureStatusModel) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_recording)
            .setContentTitle(context.getString(CaptureStrings.headline(model.headline)))
            .setContentText(context.getString(CaptureStrings.detail(model)))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openApp())

    /**
     * @param fromAlert no service is running: actions that open the microphone go through
     * [CommandActivity] so they start in the foreground (see there).
     */
    private fun addActions(
        builder: NotificationCompat.Builder,
        model: CaptureStatusModel,
        fromAlert: Boolean = false,
    ) {
        val resume: (Int) -> NotificationCompat.Action = { label ->
            if (fromAlert) {
                activityAction(label, RecordingService.ACTION_RESUME)
            } else {
                micAction(label, RecordingService.ACTION_RESUME)
            }
        }
        when (model.primaryAction) {
            PrimaryAction.PAUSE ->
                builder.addAction(action(R.string.action_pause, RecordingService.ACTION_PAUSE))
            PrimaryAction.RESUME -> builder.addAction(resume(R.string.action_resume))
            PrimaryAction.START, PrimaryAction.NONE -> Unit
        }
        if (model.canRetry) builder.addAction(resume(R.string.action_retry))
        if (model.canMark) {
            builder.addAction(action(R.string.action_highlight, RecordingService.ACTION_HIGHLIGHT))
        }
        if (model.canStop) {
            builder.addAction(action(R.string.action_end_session, RecordingService.ACTION_STOP))
        }
    }

    private fun action(label: Int, serviceAction: String) =
        NotificationCompat.Action(0, context.getString(label), broadcast(serviceAction))

    /** Opens the microphone, so it requires an unlocked phone (see [micAction]). */
    private fun activityAction(label: Int, serviceAction: String) = NotificationCompat.Action.Builder(
        0,
        context.getString(label),
        PendingIntent.getActivity(
            context,
            serviceAction.hashCode(),
            CommandActivity.intent(context, serviceAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    ).setAuthenticationRequired(true).build()

    /**
     * Actions that open the microphone require the device to be unlocked, so neither someone
     * holding a locked phone nor an app with notification access can start recording through
     * a button. Pause and Stop never require it: stopping must always be one tap.
     */
    private fun micAction(label: Int, serviceAction: String) = NotificationCompat.Action.Builder(
        0,
        context.getString(label),
        broadcast(serviceAction),
    ).setAuthenticationRequired(true).build()

    /** Explicit and immutable: only this app's own receiver can get it, and nobody can edit it. */
    private fun broadcast(serviceAction: String) = PendingIntent.getBroadcast(
        context,
        serviceAction.hashCode(),
        CommandReceiver.intent(context, serviceAction),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openApp() = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_STATUS = "capture_status_v2"
        private const val LEGACY_CHANNEL_STATUS = "capture_status"
        const val CHANNEL_ALERTS = "capture_alerts"
        const val CHANNEL_RHYTHM = "daily_rhythm"
        const val ID_STATUS = 1
        const val ID_ALERT = 2
        const val ID_REMINDER = 3
        const val ID_RESUMED = 4
        const val ID_REPORT_READY = 5
    }
}
