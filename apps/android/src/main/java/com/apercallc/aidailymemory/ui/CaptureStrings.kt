package com.apercallc.aidailymemory.ui

import androidx.annotation.StringRes
import com.apercallc.aidailymemory.R
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.health.HealthIssue
import com.apercallc.aidailymemory.recording.permission.StartBlocker
import com.apercallc.aidailymemory.recording.presentation.CaptureStatusModel
import com.apercallc.aidailymemory.recording.presentation.Headline

/**
 * Presenter output → string resources. Shared by the app screen and the notification so every
 * surface names capture state with the same words.
 */
object CaptureStrings {
    @StringRes
    fun headline(headline: Headline): Int = when (headline) {
        Headline.READY -> R.string.status_ready
        Headline.START_FAILED -> R.string.status_start_failed
        Headline.STARTING -> R.string.status_starting
        Headline.RECORDING -> R.string.status_recording
        Headline.NOT_CAPTURING -> R.string.status_not_capturing
        Headline.PAUSED -> R.string.status_paused
        Headline.QUIET_TIME -> R.string.status_quiet_time
        Headline.RESUMING -> R.string.status_resuming
        Headline.INTERRUPTED -> R.string.status_interrupted
        Headline.RECOVERING -> R.string.status_recovering
        Headline.STOPPING -> R.string.status_stopping
        Headline.COMPLETED -> R.string.status_completed
    }

    @StringRes
    fun detail(model: CaptureStatusModel): Int = model.problem?.let(::problem)
        ?: model.issues.firstOrNull()?.let(::issue)
        ?: when (model.headline) {
            Headline.READY, Headline.COMPLETED, Headline.START_FAILED -> R.string.detail_idle
            Headline.PAUSED -> R.string.detail_paused
            Headline.QUIET_TIME -> R.string.detail_quiet_time
            else -> R.string.detail_active
        }

    @StringRes
    fun problem(problem: CaptureProblem): Int = when (problem) {
        CaptureProblem.PERMISSION_DENIED -> R.string.problem_permission_denied
        CaptureProblem.MICROPHONE_UNAVAILABLE -> R.string.problem_microphone_unavailable
        CaptureProblem.PHONE_CALL -> R.string.problem_phone_call
        CaptureProblem.FOREGROUND_START_NOT_ALLOWED ->
            R.string.problem_foreground_start_not_allowed
        CaptureProblem.STORAGE_LOW -> R.string.problem_storage_low
        CaptureProblem.ENCRYPTION_UNAVAILABLE -> R.string.problem_encryption_unavailable
        CaptureProblem.INDICATOR_HIDDEN -> R.string.problem_indicator_hidden
        CaptureProblem.PROCESS_RESTARTED -> R.string.problem_process_restarted
        CaptureProblem.DEVICE_REBOOTED -> R.string.problem_device_rebooted
        CaptureProblem.UNKNOWN -> R.string.problem_unknown
    }

    @StringRes
    fun issue(issue: HealthIssue): Int = when (issue) {
        HealthIssue.CAPTURE_STALLED -> R.string.issue_capture_stalled
        HealthIssue.MICROPHONE_BLOCKED -> R.string.issue_microphone_blocked
        HealthIssue.CAPTURE_INTERRUPTED -> R.string.issue_capture_interrupted
        HealthIssue.STORAGE_CRITICAL -> R.string.issue_storage_critical
        HealthIssue.STORAGE_LOW -> R.string.issue_storage_low
        HealthIssue.STORAGE_UNKNOWN -> R.string.issue_storage_unknown
        HealthIssue.UPLOAD_BACKLOG -> R.string.issue_upload_backlog
        HealthIssue.WATCH_DISCONNECTED -> R.string.issue_watch_disconnected
    }

    @StringRes
    fun blocker(blocker: StartBlocker): Int = when (blocker) {
        StartBlocker.DEVICE_LOCKED -> R.string.blocker_device_locked
        StartBlocker.MICROPHONE_PERMISSION -> R.string.blocker_microphone
        StartBlocker.NOTIFICATION_PERMISSION -> R.string.blocker_notifications
        StartBlocker.STORAGE_FULL -> R.string.blocker_storage_full
    }
}
