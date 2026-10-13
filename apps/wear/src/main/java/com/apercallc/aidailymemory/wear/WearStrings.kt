package com.apercallc.aidailymemory.wear

import androidx.annotation.StringRes
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.RecordingOrigin
import com.apercallc.aidailymemory.recording.wear.RejectionExplainer
import com.apercallc.aidailymemory.recording.wear.RejectionReason
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import com.apercallc.aidailymemory.recording.wear.intent

/** User-facing descriptions of phone-confirmed state, never raw protocol identifiers. */
internal object WearStrings {
    @StringRes
    fun headline(state: WearControlState): Int {
        val snapshot = state.snapshot
        return when {
            !state.phoneConnected -> R.string.phone_disconnected
            snapshot == null -> R.string.phone_waiting
            snapshot.status == "RECORDING" && snapshot.captureHealth == "CRITICAL" -> R.string.status_check_recording
            else -> status(snapshot.status)
        }
    }

    @StringRes
    fun status(status: String): Int = when (status) {
        "IDLE" -> R.string.status_idle
        "STARTING" -> R.string.status_starting
        "RECORDING" -> R.string.status_recording
        "PAUSED" -> R.string.status_paused
        "RESUMING" -> R.string.status_resuming
        "INTERRUPTED" -> R.string.status_interrupted
        "RECOVERING" -> R.string.status_recovering
        "STOPPING" -> R.string.status_stopping
        "COMPLETED" -> R.string.status_completed
        else -> R.string.status_unknown
    }

    @StringRes
    fun problem(problem: String): Int = when (problem) {
        "PERMISSION_DENIED" -> R.string.problem_permission
        "MICROPHONE_UNAVAILABLE" -> R.string.problem_microphone
        "PHONE_CALL" -> R.string.problem_call
        "FOREGROUND_START_NOT_ALLOWED" -> R.string.problem_open_phone
        "STORAGE_LOW" -> R.string.problem_storage
        "ENCRYPTION_UNAVAILABLE" -> R.string.problem_encryption
        "INDICATOR_HIDDEN" -> R.string.problem_notifications
        "PROCESS_RESTARTED" -> R.string.problem_app_closed
        "DEVICE_REBOOTED" -> R.string.problem_reboot
        else -> R.string.problem_unknown
    }

    @StringRes
    fun acknowledgement(outcome: CommandOutcome): Int = when (outcome) {
        CommandOutcome.Applied -> R.string.command_applied
        CommandOutcome.Ignored -> R.string.command_ignored
        CommandOutcome.Rejected -> R.string.command_rejected
    }

    /**
     * Why the phone refused [command], from the snapshot that came with the answer. Starting
     * on both devices at once is the common case, and it must not read as a fault.
     */
    @StringRes
    fun rejection(command: WatchCommand?, snapshot: PhoneRecordingSnapshot?): Int {
        if (command == null || snapshot == null) return R.string.command_rejected
        return when (RejectionExplainer.explain(command.intent, snapshot.status)) {
            RejectionReason.ALREADY_RECORDING -> when (RecordingOrigin.parse(snapshot.origin)) {
                RecordingOrigin.WATCH -> R.string.rejected_already_from_watch
                RecordingOrigin.PHONE -> R.string.rejected_already_on_phone
                null -> R.string.rejected_already_recording
            }
            RejectionReason.NOT_RECORDING -> R.string.rejected_not_recording
            RejectionReason.SESSION_STARTING -> R.string.rejected_starting
            RejectionReason.SESSION_ENDING -> R.string.rejected_ending
            RejectionReason.OTHER -> R.string.command_rejected
        }
    }

    /** Which device started the open session, shown under the headline. */
    @StringRes
    fun origin(snapshot: PhoneRecordingSnapshot?): Int? = when (RecordingOrigin.parse(snapshot?.origin)) {
        RecordingOrigin.WATCH -> R.string.origin_watch
        RecordingOrigin.PHONE -> R.string.origin_phone
        null -> null
    }

    @StringRes
    fun error(error: WearError): Int = when (error) {
        WearError.PHONE_NOT_CONNECTED -> R.string.error_phone_not_connected
        WearError.CONNECTION_CHECK_FAILED -> R.string.error_connection_check_failed
        WearError.SNAPSHOT_READ_FAILED -> R.string.error_snapshot_read_failed
        WearError.SNAPSHOT_UNCONFIRMED -> R.string.error_snapshot_unconfirmed
        WearError.COMMAND_NOT_SENT -> R.string.error_command_not_sent
        WearError.COMMAND_REMAINS_UNCONFIRMED -> R.string.error_command_remains_unconfirmed
        WearError.COMMAND_UNCONFIRMED -> R.string.command_unconfirmed
        WearError.PHONE_REJECTED -> R.string.error_phone_rejected
    }
}
