package com.apercallc.aidailymemory.service

import android.util.Log
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives only the watch's command and snapshot-request message paths. */
class WatchDataLayerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val container = (application as AiDailyMemoryApplication).container
        when (event.path) {
            WatchWireCodec.COMMAND_PATH -> {
                val envelope = WatchWireCodec.decodeCommand(event.data)
                if (envelope == null) {
                    Log.w(TAG, "watch_command_invalid")
                    return
                }
                RecordingService.sendWatchCommand(
                    applicationContext,
                    event.sourceNodeId,
                    envelope,
                )
            }
            WatchWireCodec.SNAPSHOT_REQUEST_PATH -> container.publishCurrentWatchSnapshot()
        }
    }

    private companion object {
        const val TAG = "WatchDataLayer"
    }
}
