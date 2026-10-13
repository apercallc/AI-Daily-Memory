package com.apercallc.aidailymemory.service

import android.content.Context
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable

/** Phone-authored durable snapshot and command acknowledgement transport. */
object WatchDataLayerTransport {
    /**
     * [onPublished] runs only once the Data Layer has accepted the snapshot, so the "last
     * published" watermark never claims a revision the watch could not have received. A failure
     * (or a synchronous throw from the client) is logged and leaves the watermark alone; the next
     * state change publishes again.
     */
    fun publishSnapshot(context: Context, snapshot: PhoneRecordingSnapshot, onPublished: () -> Unit = {}) {
        val request = PutDataMapRequest.create(WatchWireCodec.SNAPSHOT_PATH).apply {
            dataMap.putLong(KEY_REVISION, snapshot.revision)
            dataMap.putString(KEY_STATUS, snapshot.status)
            snapshot.sessionStartedAt?.let { dataMap.putLong(KEY_SESSION_STARTED_AT, it) }
            snapshot.problem?.let { dataMap.putString(KEY_PROBLEM, it) }
            dataMap.putString(KEY_CAPTURE_HEALTH, snapshot.captureHealth)
            dataMap.putBoolean(KEY_RESET, snapshot.reset)
            snapshot.origin?.let { dataMap.putString(KEY_ORIGIN, it) }
        }.asPutDataRequest()
            .setUrgent()
        try {
            Wearable.getDataClient(context.applicationContext)
                .putDataItem(request)
                .addOnSuccessListener { onPublished() }
                .addOnFailureListener { error ->
                    logFailure(TAG, "watch_snapshot_publish_failed", error)
                }
        } catch (e: RuntimeException) {
            logFailure(TAG, "watch_snapshot_publish_threw", e)
        }
    }

    fun sendAcknowledgement(
        context: Context,
        nodeId: String,
        acknowledgement: CommandAcknowledgement,
        snapshot: PhoneRecordingSnapshot,
    ) {
        Wearable.getMessageClient(context.applicationContext)
            .sendMessage(
                nodeId,
                WatchWireCodec.ACK_PATH,
                WatchWireCodec.encodeAcknowledgement(acknowledgement, snapshot),
            )
            .addOnFailureListener { error ->
                logFailure(TAG, "watch_ack_send_failed", error)
            }
    }

    private const val TAG = "WatchDataLayer"
    private const val KEY_REVISION = "revision"
    private const val KEY_STATUS = "status"
    private const val KEY_SESSION_STARTED_AT = "sessionStartedAt"
    private const val KEY_PROBLEM = "problem"
    private const val KEY_CAPTURE_HEALTH = "captureHealth"
    private const val KEY_RESET = "reset"
    private const val KEY_ORIGIN = "origin"
}
