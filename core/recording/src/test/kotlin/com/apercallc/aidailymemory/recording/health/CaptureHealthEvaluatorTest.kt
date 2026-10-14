package com.apercallc.aidailymemory.recording.health

import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.SessionId
import com.apercallc.aidailymemory.recording.storage.StorageHealth
import com.apercallc.aidailymemory.recording.storage.StorageLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CaptureHealthEvaluatorTest {
    private val policy = HealthPolicy(
        expectedSegmentMs = 60_000,
        stallGraceMs = 10_000,
        uploadBacklogWarnMs = 3_600_000,
    )
    private val evaluator = CaptureHealthEvaluator(policy)
    private val okStorage = StorageHealth(StorageLevel.OK, 600, pendingOverBudget = false)

    private fun state(status: RecordingStatus) = RecordingState(
        status = status,
        sessionId = if (status == RecordingStatus.IDLE) null else SessionId("s"),
        revision = 1,
        problem = if (status == RecordingStatus.INTERRUPTED) CaptureProblem.PHONE_CALL else null,
    )

    private fun inputs(
        status: RecordingStatus = RecordingStatus.RECORDING,
        now: Long = 100_000,
        since: Long = 90_000,
        lastSealed: Long? = null,
        storage: StorageHealth = okStorage,
        silent: Boolean = false,
        pendingUploadMs: Long = 0,
        watch: WatchLink = WatchLink.CONNECTED,
    ) = CaptureHealthInputs(
        state(status), now, since, lastSealed, storage, silent, pendingUploadMs, watch,
    )

    @Test
    fun `fresh healthy recording is OK`() {
        val health = evaluator.evaluate(inputs())

        assertEquals(HealthLevel.OK, health.level)
        assertTrue(health.issues.isEmpty())
    }

    @Test
    fun `no sealed segment for longer than a segment plus grace is a stall`() {
        val stalled = evaluator.evaluate(inputs(since = 0, lastSealed = 29_999))
        val boundary = evaluator.evaluate(inputs(since = 0, lastSealed = 30_000))

        assertEquals(listOf(HealthIssue.CAPTURE_STALLED), stalled.issues)
        assertEquals(HealthLevel.CRITICAL, stalled.level)
        assertEquals(HealthLevel.OK, boundary.level)
    }

    @Test
    fun `an old segment from before a pause does not make a fresh resume look stalled`() {
        val resumed = evaluator.evaluate(inputs(now = 1_000_000, since = 995_000, lastSealed = 10))

        assertEquals(HealthLevel.OK, resumed.level)
    }

    @Test
    fun `a blocked microphone is critical while recording but irrelevant while paused`() {
        assertEquals(
            listOf(HealthIssue.MICROPHONE_BLOCKED),
            evaluator.evaluate(inputs(silent = true)).issues,
        )
        assertTrue(
            evaluator.evaluate(inputs(status = RecordingStatus.PAUSED, silent = true, since = 0))
                .issues.isEmpty(),
        )
    }

    @Test
    fun `interrupted and recovering sessions are critical`() {
        listOf(RecordingStatus.INTERRUPTED, RecordingStatus.RECOVERING).forEach {
            assertEquals(
                listOf(HealthIssue.CAPTURE_INTERRUPTED),
                evaluator.evaluate(inputs(status = it)).issues,
            )
        }
    }

    @Test
    fun `storage levels map to matching issues`() {
        val expected = mapOf(
            StorageLevel.CRITICAL to HealthIssue.STORAGE_CRITICAL,
            StorageLevel.LOW to HealthIssue.STORAGE_LOW,
            StorageLevel.UNKNOWN to HealthIssue.STORAGE_UNKNOWN,
        )
        expected.forEach { (level, issue) ->
            val health = evaluator.evaluate(inputs(storage = okStorage.copy(level = level)))
            assertEquals(listOf(issue), health.issues)
        }
    }

    @Test
    fun `issues are ordered most severe first and set the overall level`() {
        val health = evaluator.evaluate(
            inputs(
                since = 0,
                storage = okStorage.copy(level = StorageLevel.LOW),
                pendingUploadMs = 3_600_001,
                watch = WatchLink.DISCONNECTED,
            ),
        )

        assertEquals(
            listOf(
                HealthIssue.CAPTURE_STALLED,
                HealthIssue.STORAGE_LOW,
                HealthIssue.UPLOAD_BACKLOG,
                HealthIssue.WATCH_DISCONNECTED,
            ),
            health.issues,
        )
        assertEquals(HealthLevel.CRITICAL, health.level)
    }

    @Test
    fun `watch problems alone are informational and unavailable watches are not issues`() {
        assertEquals(
            HealthLevel.INFO,
            evaluator.evaluate(inputs(watch = WatchLink.DISCONNECTED)).level,
        )
        assertEquals(HealthLevel.OK, evaluator.evaluate(inputs(watch = WatchLink.UNAVAILABLE)).level)
        assertEquals(HealthLevel.OK, evaluator.evaluate(inputs(pendingUploadMs = 3_600_000)).level)
    }

    @Test
    fun `watch capability errors stay unknown while unavailable and disconnected are distinct`() {
        assertEquals(WatchLink.UNKNOWN, watchLinkFromCapabilityState(null, null))
        assertEquals(WatchLink.UNAVAILABLE, watchLinkFromCapabilityState(false, false))
        assertEquals(WatchLink.DISCONNECTED, watchLinkFromCapabilityState(true, false))
        assertEquals(WatchLink.CONNECTED, watchLinkFromCapabilityState(true, true))
    }

    @Test
    fun `policy validation and provisional defaults`() {
        assertFailsWith<IllegalArgumentException> { policy.copy(expectedSegmentMs = 0) }
        assertFailsWith<IllegalArgumentException> { policy.copy(stallGraceMs = -1) }
        assertFailsWith<IllegalArgumentException> { policy.copy(uploadBacklogWarnMs = 0) }
        assertTrue(HealthPolicy.Provisional.expectedSegmentMs > 0)
        assertEquals(HealthLevel.OK, CaptureHealth.Unknown.level)
    }
}
