package com.apercallc.aidailymemory.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.apercallc.aidailymemory.MainActivity

/**
 * Carries a "start" or "resume" tap from an alert notification (no service running) into the
 * foreground, then opens the app so the user sees capture begin.
 *
 * Why an activity: starting a microphone foreground service from a broadcast with no visible
 * activity can be refused (and the refused start can crash the app) on recent Android
 * versions; with an activity resumed, the start is a normal foreground start (ADR-0010).
 * Not exported, so no other app can use it to start recording.
 */
class CommandActivity : Activity() {
    override fun onResume() {
        super.onResume()
        intent?.action?.takeIf { it in ALLOWED }?.let { RecordingService.send(this, it) }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object {
        private val ALLOWED = setOf(RecordingService.ACTION_START, RecordingService.ACTION_RESUME)

        fun intent(context: Context, action: String): Intent =
            Intent(context, CommandActivity::class.java).setAction(action)
    }
}
