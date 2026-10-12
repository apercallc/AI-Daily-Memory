package com.apercallc.aidailymemory.wear

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.edit
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Text
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
    private val timeout = Runnable {
        controlState.pendingCommandId?.let { commandId ->
            controlState = WearControlReducer.commandUnconfirmed(
                controlState,
                commandId,
                reason = getString(R.string.command_unconfirmed),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePendingCommand()
        setContent {
            MaterialTheme {
                WearHome(
                    state = controlState,
                    onCommand = ::sendCommand,
                    onRefresh = ::refreshConnection,
                    onRetry = ::retryPendingCommand,
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
    }

    override fun onStop() {
        handler.removeCallbacks(timeout)
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

    private fun refreshConnection() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                phoneNodeId = nodes.firstOrNull()?.id
                controlState = WearControlReducer.connection(controlState, phoneNodeId != null)
                if (phoneNodeId == null) {
                    controlState = controlState.copy(
                        error = "Phone not connected. Open the phone app and check Bluetooth.",
                    )
                } else {
                    loadExistingSnapshot()
                    requestSnapshot()
                }
            }
            .addOnFailureListener {
                phoneNodeId = null
                controlState = WearControlReducer.connection(controlState, false).copy(
                    error = "Couldn't check the phone connection. Try again.",
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
            .addOnFailureListener {
                controlState = controlState.copy(error = "Couldn't read the phone's latest state.")
            }
    }

    private fun requestSnapshot() {
        val nodeId = phoneNodeId ?: return
        Wearable.getMessageClient(this).sendMessage(nodeId, SNAPSHOT_REQUEST_PATH, ByteArray(0))
            .addOnFailureListener {
                controlState = controlState.copy(error = "Phone state isn't confirmed yet. Retry.")
            }
    }

    private fun sendCommand(command: WatchCommand) {
        val nodeId = phoneNodeId
        if (nodeId == null) {
            controlState = controlState.copy(
                phoneConnected = false,
                error = "Phone not connected. No command was sent.",
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
                error = "Phone not connected. The command remains unconfirmed.",
            )
            return
        }
        controlState = controlState.copy(error = null)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, COMMAND_TIMEOUT_MS)
        transmitCommand(commandId, command, nodeId)
    }

    private fun transmitCommand(commandId: String, command: WatchCommand, nodeId: String) {
        val payload = JSONObject()
            .put(KEY_COMMAND_ID, commandId)
            .put(KEY_COMMAND, command.serviceAction)
            .toString()
            .toByteArray(Charsets.UTF_8)
        Wearable.getMessageClient(this).sendMessage(nodeId, COMMAND_PATH, payload)
            .addOnFailureListener {
                controlState = WearControlReducer.commandUnconfirmed(
                    controlState,
                    commandId,
                    getString(R.string.command_unconfirmed),
                )
            }
    }

    private fun receiveAcknowledgement(payload: ByteArray) {
        val json = runCatching { JSONObject(payload.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        val commandId = json.optString(KEY_COMMAND_ID).takeIf { it.isNotBlank() } ?: return
        val outcome = runCatching {
            CommandOutcome.valueOf(json.getString(KEY_OUTCOME))
        }.getOrNull() ?: return
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
        val json = runCatching { JSONObject(payload.toString(Charsets.UTF_8)) }.getOrNull() ?: return
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
        )
    }

    private fun restorePendingCommand() {
        val commandId = pendingPreferences.getString(PENDING_ID_KEY, null) ?: return
        val serviceAction = pendingPreferences.getString(PENDING_ACTION_KEY, null) ?: return
        val command = WatchCommand.entries.firstOrNull { it.serviceAction == serviceAction } ?: return
        controlState = WearControlReducer.commandPending(WearControlState(), commandId, command).copy(
            error = getString(R.string.command_unconfirmed),
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
        private const val PENDING_COMMAND_PREFERENCES = "wear_control"
        private const val PENDING_ID_KEY = "pending_command_id"
        private const val PENDING_ACTION_KEY = "pending_command_action"
        private const val COMMAND_TIMEOUT_MS = 10_000L
        private val VALID_STATUSES = RecordingStatus.entries.map { it.name }.toSet()
        private val VALID_PROBLEMS = CaptureProblem.entries.map { it.name }.toSet()
    }
}

@Composable
private fun WearHome(
    state: WearControlState,
    onCommand: (WatchCommand) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
) {
    val snapshot = state.snapshot
    val enabled = state.phoneConnected && snapshot != null && state.pendingCommandId == null
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.app_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.phone_controller))
        Text(
            stringResource(WearStrings.headline(state)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        // Cached details are not current status when the phone is disconnected.
        if (state.phoneConnected) {
            snapshot?.problem?.let { Text(stringResource(WearStrings.problem(it))) }
            when (snapshot?.captureHealth) {
                "CRITICAL" -> Text(stringResource(R.string.health_critical))
                "WARNING" -> Text(stringResource(R.string.health_warning))
            }
        }
        if (state.pendingCommandId != null && state.error == null) {
            Text(stringResource(R.string.command_waiting))
        }
        state.lastAcknowledgement?.let { ack ->
            Text(stringResource(WearStrings.acknowledgement(ack.outcome)))
        }
        state.error?.let { Text(it) }
        if (state.pendingCommandId != null && state.error != null) {
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_retry))
            }
        }

        if (WearControlReducer.needsPhoneCheck(state)) {
            if (state.phoneConnected) {
                // Connected, but the phone's acknowledged state has not arrived; a retry
                // re-requests the snapshot instead of leaving the watch a dead end.
                Text(stringResource(R.string.phone_controls_waiting))
            }
            Button(onClick = onRefresh, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.action_check_phone))
            }
        } else {
            // needsPhoneCheck false means the watch holds a phone-acknowledged snapshot.
            val confirmed = checkNotNull(snapshot)
            WearControlReducer.availableCommands(confirmed).forEach { command ->
                Button(
                    onClick = { onCommand(command) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(command.label))
                }
            }
        }
    }
}

private val WatchCommand.label: Int
    get() = when (this) {
        WatchCommand.Start -> R.string.action_start
        WatchCommand.Pause -> R.string.action_pause
        WatchCommand.Resume -> R.string.action_resume
        WatchCommand.Stop -> R.string.action_stop
        WatchCommand.Highlight -> R.string.action_highlight
        WatchCommand.QuickNote -> R.string.action_quick_note
    }
