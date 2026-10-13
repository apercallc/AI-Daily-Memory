package com.apercallc.aidailymemory.wear

import androidx.annotation.StringRes
import com.apercallc.aidailymemory.recording.wear.CommandOutcome

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
