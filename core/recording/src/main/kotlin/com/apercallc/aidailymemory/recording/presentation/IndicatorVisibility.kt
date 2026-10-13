package com.apercallc.aidailymemory.recording.presentation

/**
 * Whether the recording indicator (the status notification) can actually be seen. It is a
 * product requirement (AGENTS.md rule 1), so the app warns in its own UI when the user's
 * notification settings hide it. A notification alone can never be the only signal: if
 * notifications are off, the notification that says so is off too.
 */
enum class IndicatorVisibility {
    VISIBLE,

    /** App notifications are switched off. */
    NOTIFICATIONS_OFF,

    /** The status channel is set to "block". */
    CHANNEL_BLOCKED,

    /** The user lowered the status channel so it no longer appears in the status bar. */
    CHANNEL_LOWERED,
}

object IndicatorVisibilityPolicy {
    // Values of NotificationManager.IMPORTANCE_*; the core module has no Android dependency.
    const val IMPORTANCE_NONE = 0
    const val IMPORTANCE_MIN = 1
    const val IMPORTANCE_LOW = 2

    /** @param channelImportance null when the channel does not exist yet. */
    fun evaluate(appNotificationsEnabled: Boolean, channelImportance: Int?): IndicatorVisibility = when {
        !appNotificationsEnabled -> IndicatorVisibility.NOTIFICATIONS_OFF
        channelImportance == null -> IndicatorVisibility.VISIBLE
        channelImportance == IMPORTANCE_NONE -> IndicatorVisibility.CHANNEL_BLOCKED
        // Below DEFAULT a notification is "silent" and can be hidden from the status bar.
        channelImportance <= IMPORTANCE_LOW -> IndicatorVisibility.CHANNEL_LOWERED
        else -> IndicatorVisibility.VISIBLE
    }

    /**
     * The warning shows while a session is open (it could be recording unseen) and, for an
     * interrupted session, always: the "tap to resume" alert is a notification too.
     */
    fun showBanner(visibility: IndicatorVisibility, model: CaptureStatusModel): Boolean =
        visibility != IndicatorVisibility.VISIBLE &&
            (model.canStop || model.headline == Headline.INTERRUPTED)
}
