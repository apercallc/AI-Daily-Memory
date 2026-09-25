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
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusPresenter
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
        system.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STATUS,
                context.getString(R.string.channel_status_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
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

    fun status(model: CaptureStatusModel): Notification =
        builder(CHANNEL_STATUS, model)
            .setOngoing(true)
            // Android 14+ lets users swipe away foreground-service notifications. Dismissing
            // the recording indicator re-posts it; pausing is the way to stop recording.
            .setDeleteIntent(broadcast(RecordingService.ACTION_SHOW_STATUS))
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .also { addActions(it, model) }
            .build()

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
            .also { addActions(it, model) }
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

    private fun addActions(builder: NotificationCompat.Builder, model: CaptureStatusModel) {
        when (model.primaryAction) {
            PrimaryAction.PAUSE ->
                builder.addAction(action(R.string.action_pause, RecordingService.ACTION_PAUSE))
            PrimaryAction.RESUME ->
                builder.addAction(action(R.string.action_resume, RecordingService.ACTION_RESUME))
            PrimaryAction.START, PrimaryAction.NONE -> Unit
        }
        if (model.canRetry) {
            builder.addAction(action(R.string.action_retry, RecordingService.ACTION_RESUME))
        }
        if (model.canMark) {
            builder.addAction(action(R.string.action_highlight, RecordingService.ACTION_HIGHLIGHT))
        }
        if (model.canStop) {
            builder.addAction(action(R.string.action_end_session, RecordingService.ACTION_STOP))
        }
    }

    private fun action(label: Int, serviceAction: String) =
        NotificationCompat.Action(0, context.getString(label), broadcast(serviceAction))

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
        const val CHANNEL_STATUS = "capture_status"
        const val CHANNEL_ALERTS = "capture_alerts"
        const val ID_STATUS = 1
        const val ID_ALERT = 2
    }
}
