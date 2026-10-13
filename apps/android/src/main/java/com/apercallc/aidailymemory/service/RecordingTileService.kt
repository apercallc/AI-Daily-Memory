package com.apercallc.aidailymemory.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.MainActivity
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.Headline
import com.apercallc.aidailymemory.recording.presentation.PrimaryAction
import com.apercallc.aidailymemory.ui.CaptureStrings

/** How the Quick Settings tile looks for a recording state; kept pure so it can be tested. */
enum class TileLook { ACTIVE, INACTIVE }

internal fun tileLook(model: CaptureStatusModel): TileLook = when (model.headline) {
    // Lit only while audio is really being saved, so the tile never claims more than the app does.
    Headline.RECORDING -> TileLook.ACTIVE
    else -> TileLook.INACTIVE
}

/**
 * A second place to see that the microphone is on: a Quick Settings tile, lit while recording,
 * reading Recording / Paused / Interrupted / Ready. Tapping it pauses; resuming a paused or
 * interrupted session needs the phone unlocked; with nothing running it opens the app, because
 * starting the microphone has to happen with the app in front.
 */
class RecordingTileService : TileService() {
    private val container get() = (applicationContext as AiDailyMemoryApplication).container

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val model = container.currentStatusModel()
        when {
            model.primaryAction == PrimaryAction.PAUSE -> sendBroadcast(
                CommandReceiver.intent(this, RecordingService.ACTION_PAUSE),
            )
            model.primaryAction == PrimaryAction.RESUME || model.canRetry -> unlockAndRun {
                open(CommandActivity.intent(this, RecordingService.ACTION_RESUME))
            }
            else -> open(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val model = container.currentStatusModel()
        tile.state = if (tileLook(model) == TileLook.ACTIVE) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = getString(CaptureStrings.headline(model.headline))
        tile.updateTile()
    }

    // The PendingIntent overload only exists from Android 14; older versions need the Intent one.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun open(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
