package com.apercallc.aidailymemory.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.platform.logFailure
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Wakes [RhythmRunner] for a scheduled change, and after the clock or time zone changes (both
 * shift wall-clock windows). Never starts capture itself: resuming a schedule pause happens in
 * the already-running recorder service (ADR-0010).
 */
class RhythmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in HANDLED) return
        val container = (context.applicationContext as AiDailyMemoryApplication).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.rhythm.catchUp()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "rhythm_catch_up_failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "RhythmReceiver"
        const val ACTION_TICK = "com.apercallc.aidailymemory.action.RHYTHM_TICK"
        private val HANDLED = setOf(
            ACTION_TICK,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}

/**
 * Inexact, idle-allowed alarms: no exact-alarm permission, and no battery cost beyond the
 * system's own maintenance windows. Pausing on time doesn't depend on these (see RhythmRunner).
 */
class AndroidRhythmAlarms(private val context: Context) : RhythmAlarms {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(at: Instant?) {
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RhythmReceiver::class.java).setAction(RhythmReceiver.ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            alarmManager.cancel(intent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), intent)
        }
    }
}
