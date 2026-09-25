package com.apercallc.aidailymemory.recording.permission

import com.apercallc.aidailymemory.recording.storage.StorageHealth
import com.apercallc.aidailymemory.recording.storage.StorageLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionFlowTest {
    private fun snapshot(granted: Boolean, rationale: Boolean, asked: Boolean) =
        PermissionSnapshot(granted, rationale, asked)

    @Test
    fun `granted wins regardless of history`() {
        assertEquals(PermissionStep.GRANTED, PermissionFlow.next(snapshot(true, false, true)))
        assertEquals(PermissionStep.GRANTED, PermissionFlow.next(PermissionSnapshot.NotRequired))
    }

    @Test
    fun `first request is educated before the system dialog`() {
        assertEquals(
            PermissionStep.EDUCATE_THEN_REQUEST,
            PermissionFlow.next(snapshot(granted = false, rationale = false, asked = false)),
        )
    }

    @Test
    fun `a single denial allows asking again with a rationale`() {
        assertEquals(
            PermissionStep.EDUCATE_THEN_REQUEST,
            PermissionFlow.next(snapshot(granted = false, rationale = true, asked = true)),
        )
    }

    @Test
    fun `permanent denial sends the user to settings instead of a dead dialog`() {
        assertEquals(
            PermissionStep.OPEN_SETTINGS,
            PermissionFlow.next(snapshot(granted = false, rationale = false, asked = true)),
        )
    }
}

class CapturePreconditionsTest {
    private val granted = PermissionSnapshot.NotRequired
    private val denied = PermissionSnapshot(false, false, true)
    private val okStorage = StorageHealth(StorageLevel.OK, 600, pendingOverBudget = false)

    private fun inputs(
        unlocked: Boolean = true,
        mic: PermissionSnapshot = granted,
        notifications: PermissionSnapshot = granted,
        storage: StorageHealth = okStorage,
    ) = CapturePreconditionInputs(unlocked, mic, notifications, storage)

    @Test
    fun `nothing blocks a ready device`() {
        assertEquals(emptyList(), CapturePreconditions.blockers(inputs()))
    }

    @Test
    fun `all blockers are reported together in fix-first order`() {
        val blockers = CapturePreconditions.blockers(
            inputs(
                unlocked = false,
                mic = denied,
                notifications = denied,
                storage = StorageHealth(StorageLevel.CRITICAL, 0, pendingOverBudget = false),
            ),
        )

        assertEquals(StartBlocker.entries.toList(), blockers)
    }

    @Test
    fun `missing notifications block recording because the indicator would be hidden`() {
        assertEquals(
            listOf(StartBlocker.NOTIFICATION_PERMISSION),
            CapturePreconditions.blockers(inputs(notifications = denied)),
        )
    }

    @Test
    fun `low or unknown storage warns but does not block`() {
        listOf(StorageLevel.LOW, StorageLevel.UNKNOWN).forEach { level ->
            val storage = StorageHealth(level, null, pendingOverBudget = true)
            assertEquals(emptyList(), CapturePreconditions.blockers(inputs(storage = storage)))
        }
    }
}
