package com.apercallc.aidailymemory.wear

import android.os.Bundle
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.edit

import com.apercallc.aidailymemory.recording.wear.PhoneNodeChoice
import com.apercallc.aidailymemory.recording.wear.WatchNode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.wear.compose.material3.MaterialTheme
import com.apercallc.aidailymemory.recording.design.BrandTokens
import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

class WearMainActivity : ComponentActivity(),
    DataClient.OnDataChangedListener,
    MessageClient.OnMessageReceivedListener {

    private var phoneNodeId: String? = null
    private var controlState by mutableStateOf(WearControlState())
    private val pendingPreferences by lazy {
        getSharedPreferences(PENDING_COMMAND_PREFERENCES, Context.MODE_PRIVATE)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val connectionWatcher = object : Runnable {
        override fun run() {
            refreshConnection(forceSnapshot = false)
            handler.postDelayed(this, CONNECTION_POLL_INTERVAL_MS)
        }
    }
    private val timeout = Runnable {
        controlState.pendingCommandId?.let { commandId ->
            controlState = WearControlReducer.commandUnconfirmed(
                controlState,
                commandId,
                reason = WearError.COMMAND_UNCONFIRMED,
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePendingCommand()
        setContent {
            val palette = BrandTokens.Dark
            val colorScheme = MaterialTheme.colorScheme.copy(
                primary = Color(palette.RED),
                primaryDim = Color(palette.RED_DEEP),
                primaryContainer = Color(palette.RED_CONTAINER),
                onPrimary = Color(palette.ON_RED),
                onPrimaryContainer = Color(palette.ON_RED_CONTAINER),
                secondary = Color(palette.SLATE),
                secondaryDim = Color(palette.SLATE),
                secondaryContainer = Color(palette.SLATE_CONTAINER),
                onSecondary = Color(palette.ON_SLATE),
                onSecondaryContainer = Color(palette.ON_SLATE_CONTAINER),
                tertiary = Color(palette.OK),
                tertiaryDim = Color(palette.OK),
                tertiaryContainer = Color(palette.SLATE_CONTAINER),
                onTertiary = Color(palette.ON_SLATE),
                onTertiaryContainer = Color(palette.ON_SLATE_CONTAINER),
                surfaceContainerLow = Color(palette.WATCH_BACKGROUND),
                surfaceContainer = Color(palette.PAPER),
                surfaceContainerHigh = Color(palette.CARD),
                onSurface = Color(palette.INK),
                onSurfaceVariant = Color(palette.INK_SOFT),
                outline = Color(palette.OUTLINE),
                outlineVariant = Color(palette.RULE),
                background = Color(palette.WATCH_BACKGROUND),
                onBackground = Color(palette.INK),
                error = Color(palette.ERROR),
                errorDim = Color(palette.ERROR),
                errorContainer = Color(palette.ERROR_CONTAINER),
                onError = Color(palette.ON_ERROR),
                onErrorContainer = Color(palette.ON_ERROR_CONTAINER),
            )
            val type = MaterialTheme.typography.copy(
                displayLarge = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Serif),
                displayMedium = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Serif),
                displaySmall = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Serif),
                titleLarge = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif),
                titleMedium = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif),
                titleSmall = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Serif),
                labelLarge = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                labelMedium = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                labelSmall = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            )
            MaterialTheme(colorScheme = colorScheme, typography = type) {
                WearHome(
                    state = controlState,
                    onCommand = ::sendCommand,
                    onRefresh = ::refreshConnection,
                    onRetry = ::retryPendingCommand,
                    onDismiss = ::dismissPendingCommand,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Wearable.getDataClient(this).addListener(this)
        Wearable.getMessageClient(this).addListener(this)
        if (controlState.pendingCommandId != null) {
            // Defensive: onStop() always removes this first, but never leave a second
            // scheduled run possible if that ordering assumption ever breaks.
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, COMMAND_TIMEOUT_MS)
        }
        refreshConnection()
        handler.removeCallbacks(connectionWatcher)
        handler.postDelayed(connectionWatcher, CONNECTION_POLL_INTERVAL_MS)
    }

    override fun onStop() {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(connectionWatcher)
        Wearable.getDataClient(this).removeListener(this)
        Wearable.getMessageClient(this).removeListener(this)
        super.onStop()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        try {
            for (event in events) {
                if (event.type != DataEvent.TYPE_CHANGED || event.dataItem.uri.path != SNAPSHOT_PATH) {
                    continue
                }
                val data = DataMapItem.fromDataItem(event.dataItem).dataMap
                val incoming = data.toPhoneSnapshot() ?: continue
                controlState = WearControlReducer.phoneSnapshot(controlState, incoming)
            }
        } finally {
            events.release()
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            ACK_PATH -> receiveAcknowledgement(event.data)
            SNAPSHOT_MESSAGE_PATH -> receiveSnapshot(event.data)
        }
    }

    private fun refreshConnection() = refreshConnection(forceSnapshot = true)

    private fun refreshConnection(forceSnapshot: Boolean) {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                val previousNodeId = phoneNodeId
                phoneNodeId = PhoneNodeChoice.pick(nodes.map { WatchNode(it.id, it.isNearby) })?.id
                controlState = WearControlReducer.connection(controlState, phoneNodeId != null)
                if (phoneNodeId == null) {
                    controlState = controlState.copy(error = WearError.PHONE_NOT_CONNECTED)
                } else if (forceSnapshot || previousNodeId != phoneNodeId) {
                    loadExistingSnapshot()
                    requestSnapshot()
                }
            }
            .addOnFailureListener { error ->
                logWarning("connection_check_failed", error)
                phoneNodeId = null
                controlState = WearControlReducer.connection(controlState, false).copy(
                    error = WearError.CONNECTION_CHECK_FAILED,
                )
            }
    }

    private fun loadExistingSnapshot() {
        Wearable.getDataClient(this).dataItems
            .addOnSuccessListener { items ->
                try {
                    for (item in items) {
                        if (item.uri.path != SNAPSHOT_PATH) continue
                        val data = DataMapItem.fromDataItem(item).dataMap
                        val incoming = data.toPhoneSnapshot() ?: continue
                        controlState = WearControlReducer.phoneSnapshot(controlState, incoming)
                    }
                } finally {
                    items.release()
                }
            }
            .addOnFailureListener { error ->
                logWarning("snapshot_read_failed", error)
                controlState = controlState.copy(error = WearError.SNAPSHOT_READ_FAILED)
            }
    }

    private fun requestSnapshot() {
        val nodeId = phoneNodeId ?: return
        Wearable.getMessageClient(this).sendMessage(nodeId, SNAPSHOT_REQUEST_PATH, ByteArray(0))
            .addOnFailureListener { error ->
                logWarning("snapshot_request_failed", error)
                controlState = controlState.copy(error = WearError.SNAPSHOT_UNCONFIRMED)
            }
    }

    private fun sendCommand(command: WatchCommand) {
        val nodeId = phoneNodeId
        if (nodeId == null) {
            controlState = controlState.copy(
                phoneConnected = false,
                error = WearError.COMMAND_NOT_SENT,
            )
            return
        }
        if (controlState.snapshot == null || controlState.pendingCommandId != null) return

        val commandId = UUID.randomUUID().toString()
        controlState = WearControlReducer.commandPending(controlState, commandId, command)
        persistPendingCommand(commandId, command)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, COMMAND_TIMEOUT_MS)
        transmitCommand(commandId, command, nodeId)
    }

    private fun retryPendingCommand() {
        val commandId = controlState.pendingCommandId ?: return
        val command = controlState.pendingCommand ?: return
        val nodeId = phoneNodeId ?: run {
            controlState = controlState.copy(
                phoneConnected = false,
                error = WearError.COMMAND_REMAINS_UNCONFIRMED,
            )
            return
        }
        controlState = controlState.copy(error = null)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, COMMAND_TIMEOUT_MS)
        transmitCommand(commandId, command, nodeId)
    }

    private fun dismissPendingCommand() {
        handler.removeCallbacks(timeout)
        controlState = WearControlReducer.commandDismissed(controlState)
        clearPendingCommand()
    }

    private fun transmitCommand(commandId: String, command: WatchCommand, nodeId: String) {
        val payload = JSONObject()
            .put(KEY_COMMAND_ID, commandId)
            .put(KEY_COMMAND, command.serviceAction)
            .toString()
            .toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(this).sendMessage(nodeId, COMMAND_PATH, payload)
            .addOnFailureListener { error ->
                logWarning("command_send_failed", error)
                controlState = WearControlReducer.commandUnconfirmed(
                    controlState,
                    commandId,
                    WearError.COMMAND_UNCONFIRMED,
                )
            }
    }

    /** A message that isn't valid JSON is dropped, but never silently: the type is logged. */
    private fun parseJson(payload: ByteArray, event: String): JSONObject? = try {
        JSONObject(payload.toString(Charsets.UTF_8))
    } catch (e: JSONException) {
        logWarning(event, e)
        null
    }

    /** Exception type only: Data Layer errors never carry recording content. */
    private fun logWarning(event: String, error: Exception) {
        Log.w(LOG_TAG, "$event type=${error.javaClass.simpleName}")
    }

    private fun receiveAcknowledgement(payload: ByteArray) {
        val json = parseJson(payload, "acknowledgement_unreadable") ?: return
        val commandId = json.optString(KEY_COMMAND_ID).takeIf { it.isNotBlank() } ?: return
        val outcome = try {
            CommandOutcome.valueOf(json.getString(KEY_OUTCOME))
        } catch (e: JSONException) {
            logWarning("acknowledgement_outcome_missing", e)
            return
        } catch (e: IllegalArgumentException) {
            logWarning("acknowledgement_outcome_unknown", e)
            return
        }
        val revision = json.optLong(KEY_REVISION, -1L)
        if (revision < 0) return
        val acknowledgement = CommandAcknowledgement(commandId, outcome, revision)
        val snapshotRevision = json.optLong(KEY_SNAPSHOT_REVISION, revision)
        val snapshot = json.toPhoneSnapshot(snapshotRevision) ?: return
        if (snapshot.revision < acknowledgement.revision) return
        if (controlState.pendingCommandId != commandId) return
        handler.removeCallbacks(timeout)
        controlState = WearControlReducer.acknowledgement(controlState, acknowledgement, snapshot)
        if (controlState.pendingCommandId == null) clearPendingCommand()
    }

    private fun receiveSnapshot(payload: ByteArray) {
        val json = parseJson(payload, "snapshot_unreadable") ?: return
        json.toPhoneSnapshot()?.let { controlState = WearControlReducer.phoneSnapshot(controlState, it) }
    }

    private fun JSONObject.toPhoneSnapshot(revisionOverride: Long? = null): PhoneRecordingSnapshot? {
        val revision = revisionOverride ?: optLong(KEY_REVISION, -1L)
        val status = optString(KEY_STATUS).takeIf { it.isNotBlank() } ?: return null
        if (revision < 0 || status !in VALID_STATUSES) return null
        val problem = optString(KEY_PROBLEM).takeUnless { it.isBlank() || it == "null" }
        if (problem != null && problem !in VALID_PROBLEMS) return null
        return PhoneRecordingSnapshot(
            revision = revision,
            status = status,
            sessionStartedAt = if (isNull(KEY_SESSION_STARTED_AT)) null else optLong(KEY_SESSION_STARTED_AT),
            problem = problem,
            captureHealth = optString(KEY_CAPTURE_HEALTH, "UNKNOWN"),
            reset = optBoolean(KEY_RESET, false),
            origin = optString(KEY_ORIGIN).takeUnless { it.isBlank() || it == "null" },
        )
    }

    private fun DataMap.toPhoneSnapshot(): PhoneRecordingSnapshot? {
        if (!containsKey(KEY_REVISION)) return null
        val revision = getLong(KEY_REVISION)
        val status = getString(KEY_STATUS) ?: return null
        val problem = getString(KEY_PROBLEM)
        if (revision < 0 || status !in VALID_STATUSES) return null
        if (problem != null && problem !in VALID_PROBLEMS) return null
        return PhoneRecordingSnapshot(
            revision = revision,
            status = status,
            sessionStartedAt = getLong(KEY_SESSION_STARTED_AT).takeIf {
                containsKey(KEY_SESSION_STARTED_AT)
            },
            problem = problem,
            captureHealth = getString(KEY_CAPTURE_HEALTH) ?: "UNKNOWN",
            reset = getBoolean(KEY_RESET, false),
            origin = getString(KEY_ORIGIN),
        )
    }

    private fun restorePendingCommand() {
        val commandId = pendingPreferences.getString(PENDING_ID_KEY, null) ?: return
        val serviceAction = pendingPreferences.getString(PENDING_ACTION_KEY, null) ?: return
        val command = WatchCommand.entries.firstOrNull { it.serviceAction == serviceAction } ?: return
        controlState = WearControlReducer.commandPending(WearControlState(), commandId, command).copy(
            error = WearError.COMMAND_UNCONFIRMED,
        )
    }

    private fun persistPendingCommand(commandId: String, command: WatchCommand) {
        pendingPreferences.edit {
            putString(PENDING_ID_KEY, commandId)
            putString(PENDING_ACTION_KEY, command.serviceAction)
        }
    }

    private fun clearPendingCommand() {
        pendingPreferences.edit {
            remove(PENDING_ID_KEY)
            remove(PENDING_ACTION_KEY)
        }
    }

    companion object {
        private const val LOG_TAG = "WearMain"
        const val COMMAND_PATH = "/adm/command"
        const val ACK_PATH = "/adm/ack"
        const val SNAPSHOT_PATH = "/adm/snapshot"
        const val SNAPSHOT_MESSAGE_PATH = "/adm/snapshot"
        const val SNAPSHOT_REQUEST_PATH = "/adm/snapshot/request"
        const val KEY_COMMAND_ID = "commandId"
        const val KEY_COMMAND = "command"
        const val KEY_OUTCOME = "outcome"
        const val KEY_REVISION = "revision"
        const val KEY_SNAPSHOT_REVISION = "snapshotRevision"
        const val KEY_STATUS = "status"
        const val KEY_SESSION_STARTED_AT = "sessionStartedAt"
        const val KEY_PROBLEM = "problem"
        const val KEY_CAPTURE_HEALTH = "captureHealth"
        const val KEY_RESET = "reset"
        const val KEY_ORIGIN = "origin"
        private const val PENDING_COMMAND_PREFERENCES = "wear_control"
        private const val PENDING_ID_KEY = "pending_command_id"
        private const val PENDING_ACTION_KEY = "pending_command_action"
        private const val COMMAND_TIMEOUT_MS = 10_000L
        private const val CONNECTION_POLL_INTERVAL_MS = 5_000L
        private val VALID_STATUSES = RecordingStatus.entries.map { it.name }.toSet()
        private val VALID_PROBLEMS = CaptureProblem.entries.map { it.name }.toSet()
    }
}
