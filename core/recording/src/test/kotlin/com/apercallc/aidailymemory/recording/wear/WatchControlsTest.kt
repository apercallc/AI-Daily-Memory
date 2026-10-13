package com.apercallc.aidailymemory.recording.wear

import com.apercallc.aidailymemory.recording.RecordingStatus
import com.apercallc.aidailymemory.recording.health.HealthLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchControlsTest {
    private fun snapshot(status: String, health: String = HealthLevel.OK.name) = PhoneRecordingSnapshot(
        revision = 1,
        status = status,
        sessionStartedAt = null,
        problem = null,
        captureHealth = health,
    )

    private fun commands(status: RecordingStatus, health: HealthLevel = HealthLevel.OK) =
        WatchControls.availableCommands(snapshot(status.name, health.name))

    @Test
    fun `every status has a defined control set`() {
        assertEquals(listOf(WatchCommand.Start), commands(RecordingStatus.IDLE))
        assertEquals(listOf(WatchCommand.Start), commands(RecordingStatus.COMPLETED))
        assertEquals(listOf(WatchCommand.Stop), commands(RecordingStatus.STARTING))
        assertEquals(
            listOf(WatchCommand.Pause, WatchCommand.Stop, WatchCommand.Highlight, WatchCommand.QuickNote),
            commands(RecordingStatus.RECORDING),
        )
        assertEquals(listOf(WatchCommand.Pause, WatchCommand.Stop), commands(RecordingStatus.RESUMING))
        assertEquals(listOf(WatchCommand.Pause, WatchCommand.Stop), commands(RecordingStatus.RECOVERING))
        assertEquals(listOf(WatchCommand.Resume, WatchCommand.Stop), commands(RecordingStatus.PAUSED))
        assertEquals(listOf(WatchCommand.Resume, WatchCommand.Stop), commands(RecordingStatus.INTERRUPTED))
        assertEquals(emptyList(), commands(RecordingStatus.STOPPING))
    }

    @Test
    fun `markers are withheld when capture health is critical`() {
        assertEquals(
            listOf(WatchCommand.Pause, WatchCommand.Stop),
            commands(RecordingStatus.RECORDING, HealthLevel.CRITICAL),
        )
        assertEquals(4, commands(RecordingStatus.RECORDING, HealthLevel.WARNING).size)
    }

    @Test
    fun `an unknown status offers nothing`() {
        assertEquals(emptyList(), WatchControls.availableCommands(snapshot("FUTURE_STATE")))
    }

    @Test
    fun `a nearby node is preferred over an earlier cloud node`() {
        val nodes = listOf(WatchNode("cloud", false), WatchNode("near", true))
        assertEquals("near", PhoneNodeChoice.pick(nodes)?.id)
        assertEquals("cloud", PhoneNodeChoice.pick(listOf(WatchNode("cloud", false)))?.id)
        assertNull(PhoneNodeChoice.pick(emptyList()))
    }

    @Test
    fun `acknowledgements round trip and bad lines are skipped`() {
        val acks = listOf(
            CommandAcknowledgement("a-1", CommandOutcome.Applied, 4),
            CommandAcknowledgement("b-2", CommandOutcome.Rejected, 9),
        )
        assertEquals(acks, AcknowledgementCodec.decode(AcknowledgementCodec.encode(acks)))
        assertEquals(
            listOf(acks[0]),
            AcknowledgementCodec.decode("garbage\n|Applied|1\nx|NOPE|1\nx|Applied|-1\na-1|Applied|4"),
        )
        assertEquals(emptyList(), AcknowledgementCodec.decode(null))
    }

    @Test
    fun `command ids that would corrupt the store are not encodable`() {
        assertEquals(true, AcknowledgementCodec.isEncodable("123e4567-e89b"))
        assertEquals(false, AcknowledgementCodec.isEncodable("a|b"))
        assertEquals(false, AcknowledgementCodec.isEncodable("a\nb"))
        assertEquals(false, AcknowledgementCodec.isEncodable(" "))
    }

    @Test
    fun `recent acknowledgements keep insertion order for persistence`() {
        val cache = CommandAcknowledgements<String>(capacity = 2)
        cache.remember("1", "a")
        cache.remember("2", "b")
        cache.remember("3", "c")
        assertEquals(listOf("2" to "b", "3" to "c"), cache.snapshot())
    }
}
