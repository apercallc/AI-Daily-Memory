package com.apercallc.aidailymemory.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apercallc.aidailymemory.AiDailyMemoryApplication

/**
 * Wakes the process after a reboot or an app update, both of which kill an active session.
 * The application's restore turns that into a visible interruption and a "tap to resume"
 * notification. It never starts capture: the platform forbids it and so does ADR-0010.
 *
 * The app is not direct-boot aware, so this only runs after the first unlock, which is also
 * when credential-encrypted storage (database and segments) becomes readable.
 */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val container = (context.applicationContext as AiDailyMemoryApplication).container
        finishAfter(container) { container.controller.awaitRestored() }
    }

    private companion object {
        val HANDLED = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
