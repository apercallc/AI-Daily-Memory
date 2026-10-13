package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

data class WatchCommandEnvelope(val commandId: String, val command: WatchCommand)

/** JSON contract shared with WearMainActivity; invalid watch payloads are ignored. */
object WatchWireCodec {
    const val COMMAND_PATH = "/adm/command"
    const val ACK_PATH = "/adm/ack"
    const val SNAPSHOT_PATH = "/adm/snapshot"
    const val SNAPSHOT_REQUEST_PATH = "/adm/snapshot/request"

    fun decodeCommand(payload: ByteArray): WatchCommandEnvelope? {
        return try {
            val json = JSONObject(payload.decodeToString())
            val commandId = json.getString(KEY_COMMAND_ID)
            val uuid = UUID.fromString(commandId)
            if (uuid.toString() != commandId) return null
            val action = json.getString(KEY_COMMAND)
            val command = WatchCommand.entries.firstOrNull { it.serviceAction == action }
                ?: return null
            WatchCommandEnvelope(commandId, command)
        } catch (_: JSONException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun encodeAcknowledgement(
        acknowledgement: CommandAcknowledgement,
        snapshot: PhoneRecordingSnapshot,
    ): ByteArray = JSONObject()
        .put(KEY_COMMAND_ID, acknowledgement.commandId)
        .put(KEY_OUTCOME, acknowledgement.outcome.name)
        .put(KEY_REVISION, acknowledgement.revision)
        .put(KEY_SNAPSHOT_REVISION, snapshot.revision)
        .put(KEY_STATUS, snapshot.status)
        .put(KEY_SESSION_STARTED_AT, snapshot.sessionStartedAt ?: JSONObject.NULL)
        .put(KEY_PROBLEM, snapshot.problem ?: JSONObject.NULL)
        .put(KEY_CAPTURE_HEALTH, snapshot.captureHealth)
        .put(KEY_RESET, snapshot.reset)
        .put(KEY_ORIGIN, snapshot.origin ?: JSONObject.NULL)
        .toString()
        .encodeToByteArray()

    fun encodeSnapshot(snapshot: PhoneRecordingSnapshot): ByteArray = JSONObject()
        .put(KEY_REVISION, snapshot.revision)
        .put(KEY_STATUS, snapshot.status)
        .put(KEY_SESSION_STARTED_AT, snapshot.sessionStartedAt ?: JSONObject.NULL)
        .put(KEY_PROBLEM, snapshot.problem ?: JSONObject.NULL)
        .put(KEY_CAPTURE_HEALTH, snapshot.captureHealth)
        .put(KEY_RESET, snapshot.reset)
        .put(KEY_ORIGIN, snapshot.origin ?: JSONObject.NULL)
        .toString()
        .encodeToByteArray()

    private const val KEY_COMMAND_ID = "commandId"
    private const val KEY_COMMAND = "command"
    private const val KEY_OUTCOME = "outcome"
    private const val KEY_REVISION = "revision"
    private const val KEY_SNAPSHOT_REVISION = "snapshotRevision"
    private const val KEY_STATUS = "status"
    private const val KEY_SESSION_STARTED_AT = "sessionStartedAt"
    private const val KEY_PROBLEM = "problem"
    private const val KEY_CAPTURE_HEALTH = "captureHealth"
    private const val KEY_RESET = "reset"
    private const val KEY_ORIGIN = "origin"
}
