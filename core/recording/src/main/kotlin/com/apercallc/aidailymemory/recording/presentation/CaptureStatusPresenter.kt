package com.apercallc.aidailymemory.recording.presentation

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.health.CaptureHealth
import com.apercallc.aidailymemory.recording.health.HealthIssue
import com.apercallc.aidailymemory.recording.health.HealthLevel

/** What the status headline says. UI layers map these to localized strings. */
enum class Headline {
    READY,
    START_FAILED,
    STARTING,
    RECORDING,

    /** RECORDING in the state machine, but health says audio is not being saved. */
    NOT_CAPTURING,
    PAUSED,
    RESUMING,
    INTERRUPTED,
    RECOVERING,
    STOPPING,
    COMPLETED,
}

/** Semantic tone. UI layers map it to design tokens, never to raw colors. */
enum class StatusTone { NEUTRAL, RECORDING, PAUSED, WARNING, ERROR }

enum class PrimaryAction { START, PAUSE, RESUME, NONE }

data class CaptureStatusModel(
    val headline: Headline,
    val tone: StatusTone,
    val primaryAction: PrimaryAction,
    val canStop: Boolean,
    val canMark: Boolean,
    /**
     * INTERRUPTED only: the user may ask to reopen the microphone ("tap to resume"). Pause stays
     * the primary action because the engine may still recover by itself.
     */
    val canRetry: Boolean,
    val problem: CaptureProblem?,
    val issues: List<HealthIssue>,
)

/**
 * Maps domain state and health to a UI model. Shared by the phone UI and notification, and
 * later by the watch, so every surface describes capture the same way.
 */
object CaptureStatusPresenter {
    fun present(state: RecordingState, health: CaptureHealth): CaptureStatusModel {
        val status = state.status
        val capturingFailed = status == RecordingStatus.RECORDING &&
            health.level == HealthLevel.CRITICAL
        val headline = when (status) {
            RecordingStatus.IDLE ->
                if (state.problem != null) Headline.START_FAILED else Headline.READY
            RecordingStatus.STARTING -> Headline.STARTING
            RecordingStatus.RECORDING ->
                if (capturingFailed) Headline.NOT_CAPTURING else Headline.RECORDING
            RecordingStatus.PAUSED -> Headline.PAUSED
            RecordingStatus.RESUMING -> Headline.RESUMING
            RecordingStatus.INTERRUPTED -> Headline.INTERRUPTED
            RecordingStatus.RECOVERING -> Headline.RECOVERING
            RecordingStatus.STOPPING -> Headline.STOPPING
            RecordingStatus.COMPLETED -> Headline.COMPLETED
        }
        return CaptureStatusModel(
            headline = headline,
            tone = toneFor(headline, health),
            primaryAction = when (status) {
                RecordingStatus.IDLE, RecordingStatus.COMPLETED -> PrimaryAction.START
                // A privacy pause is always one tap away while anything could be capturing.
                RecordingStatus.RECORDING,
                RecordingStatus.RESUMING,
                RecordingStatus.INTERRUPTED,
                RecordingStatus.RECOVERING,
                -> PrimaryAction.PAUSE
                RecordingStatus.PAUSED -> PrimaryAction.RESUME
                RecordingStatus.STARTING, RecordingStatus.STOPPING -> PrimaryAction.NONE
            },
            canStop = status in STOPPABLE,
            canMark = status == RecordingStatus.RECORDING && !capturingFailed,
            canRetry = status == RecordingStatus.INTERRUPTED,
            problem = state.problem,
            issues = health.issues,
        )
    }

    private fun toneFor(headline: Headline, health: CaptureHealth): StatusTone = when (headline) {
        Headline.NOT_CAPTURING, Headline.INTERRUPTED, Headline.START_FAILED -> StatusTone.ERROR
        Headline.RECOVERING -> StatusTone.WARNING
        Headline.RECORDING ->
            if (health.level == HealthLevel.WARNING) StatusTone.WARNING else StatusTone.RECORDING
        Headline.PAUSED -> StatusTone.PAUSED
        // Not capturing yet, so never the recording color.
        Headline.STARTING,
        Headline.RESUMING,
        Headline.READY,
        Headline.STOPPING,
        Headline.COMPLETED,
        -> StatusTone.NEUTRAL
    }

    private val STOPPABLE = setOf(
        RecordingStatus.STARTING,
        RecordingStatus.RECORDING,
        RecordingStatus.PAUSED,
        RecordingStatus.RESUMING,
        RecordingStatus.INTERRUPTED,
        RecordingStatus.RECOVERING,
    )
}
