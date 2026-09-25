package com.apercallc.aidailymemory.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apercallc.aidailymemory.AiDailyMemoryApplication

/**
 * Receives notification actions. Routing them here instead of straight to the service means
 * every command goes through [RecordingService.send], which never starts the service when its
 * foreground start would fail (a crash on Android). A tap on a notification action still
 * counts as user interaction for the foreground-start exemption (ADR-0010).
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == RecordingService.ACTION_SHOW_STATUS) {
            // The recording indicator was swiped away (allowed on Android 14+). Put it back
            // while a session is open; pausing is how recording stops, not dismissing.
            val container = (context.applicationContext as AiDailyMemoryApplication).container
            container.notifications.repostStatusIfActive(container.currentStatusModel())
            return
        }
        RecordingService.send(context, action)
    }

    companion object {
        fun intent(context: Context, action: String): Intent =
            Intent(context, CommandReceiver::class.java).setAction(action)
    }
}
