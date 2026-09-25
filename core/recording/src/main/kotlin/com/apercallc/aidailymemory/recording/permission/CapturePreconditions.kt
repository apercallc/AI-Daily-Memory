package com.apercallc.aidailymemory.recording.permission

import com.apercallc.aidailymemory.recording.storage.StorageHealth
import com.apercallc.aidailymemory.recording.storage.StorageLevel

/**
 * Runtime-permission status as the app can observe it.
 *
 * Android reports "should show rationale = false" both before the first request and after a
 * permanent denial, so the caller also passes whether it has asked before (persisted).
 */
data class PermissionSnapshot(
    val granted: Boolean,
    val shouldShowRationale: Boolean,
    val requestedBefore: Boolean,
) {
    companion object {
        /** Permission that does not exist on this API level (e.g. notifications before 33). */
        val NotRequired = PermissionSnapshot(
            granted = true,
            shouldShowRationale = false,
            requestedBefore = false,
        )
    }
}

enum class PermissionStep {
    GRANTED,

    /** Explain why before the system dialog (first request, or after a single denial). */
    EDUCATE_THEN_REQUEST,

    /** The system will not show its dialog again. Only app settings can grant it now. */
    OPEN_SETTINGS,
}

object PermissionFlow {
    fun next(snapshot: PermissionSnapshot): PermissionStep = when {
        snapshot.granted -> PermissionStep.GRANTED
        snapshot.shouldShowRationale -> PermissionStep.EDUCATE_THEN_REQUEST
        // Asked before, no rationale allowed: the user chose "don't ask again" (or denied twice).
        snapshot.requestedBefore -> PermissionStep.OPEN_SETTINGS
        else -> PermissionStep.EDUCATE_THEN_REQUEST
    }
}

/** Why a session cannot start right now. Ordered by what the user should fix first. */
enum class StartBlocker {
    /** Credential-encrypted storage is locked (before first unlock after reboot). */
    DEVICE_LOCKED,
    MICROPHONE_PERMISSION,

    /**
     * Without notifications the recording indicator would be invisible, which the product
     * forbids (AGENTS.md rule 1), so recording does not start without them.
     */
    NOTIFICATION_PERMISSION,
    STORAGE_FULL,
}

data class CapturePreconditionInputs(
    val userUnlocked: Boolean,
    val microphone: PermissionSnapshot,
    val notifications: PermissionSnapshot,
    val storage: StorageHealth,
)

object CapturePreconditions {
    /** Empty when a session may start. Unknown storage does not block; it is a health warning. */
    fun blockers(inputs: CapturePreconditionInputs): List<StartBlocker> = buildList {
        if (!inputs.userUnlocked) add(StartBlocker.DEVICE_LOCKED)
        if (!inputs.microphone.granted) add(StartBlocker.MICROPHONE_PERMISSION)
        if (!inputs.notifications.granted) add(StartBlocker.NOTIFICATION_PERMISSION)
        if (inputs.storage.level == StorageLevel.CRITICAL) add(StartBlocker.STORAGE_FULL)
    }
}
