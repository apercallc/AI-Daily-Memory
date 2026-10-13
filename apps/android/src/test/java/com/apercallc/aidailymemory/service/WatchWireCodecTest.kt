package com.apercallc.aidailymemory.service

import com.apercallc.aidailymemory.recording.wear.CommandAcknowledgement
import com.apercallc.aidailymemory.recording.wear.CommandOutcome
import com.apercallc.aidailymemory.recording.wear.PhoneRecordingSnapshot
import com.apercallc.aidailymemory.recording.wear.WatchCommand
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WatchWireCodecTest {
    @Test
    fun `decodes only a valid UUID and a supported watch command`() {
        val commandId = "123e4567-e89b-12d3-a456-426614174000"

        assertEquals(
            WatchCommandEnvelope(commandId, WatchCommand.Pause),
            WatchWireCodec.decodeCommand(
                JSONObject().put("commandId", commandId).put("command", "PAUSE").toString()
                    .toByteArray(),
            ),
        )
        assertNull(
            WatchWireCodec.decodeCommand(
                JSONObject().put("commandId", "not-a-uuid").put("command", "START")
                    .toString().toByteArray(),
            ),
        )
        assertNull(
            WatchWireCodec.decodeCommand(
                JSONObject().put("commandId", commandId).put("command", "CAPTURE_SIGNAL")
                    .toString().toByteArray(),
            ),
        )
    }

    @Test
    fun `acknowledgement carries reducer outcome and authoritative state fields`() {
        val bytes = WatchWireCodec.encodeAcknowledgement(
            CommandAcknowledgement(
                commandId = "123e4567-e89b-12d3-a456-426614174000",
                outcome = CommandOutcome.Applied,
                revision = 5,
            ),
            PhoneRecordingSnapshot(
                revision = 6,
                status = "INTERRUPTED",
                sessionStartedAt = null,
                problem = "FOREGROUND_START_NOT_ALLOWED",
                captureHealth = "CRITICAL",
            ),
        )
        val json = JSONObject(bytes.decodeToString())

        assertEquals(CommandOutcome.Applied.name, json.getString("outcome"))
        assertEquals(5L, json.getLong("revision"))
        assertEquals(6L, json.getLong("snapshotRevision"))
        assertEquals("INTERRUPTED", json.getString("status"))
        assertEquals("FOREGROUND_START_NOT_ALLOWED", json.getString("problem"))
        assertEquals("CRITICAL", json.getString("captureHealth"))
    }

    @Test
    fun `the snapshot and the acknowledgement both carry the origin of the session`() {
        val snapshot = PhoneRecordingSnapshot(
            revision = 4, status = "RECORDING", sessionStartedAt = null, problem = null,
            captureHealth = "OK", origin = "WATCH",
        )

        assertEquals("WATCH", JSONObject(WatchWireCodec.encodeSnapshot(snapshot).decodeToString()).getString("origin"))
        val ack = WatchWireCodec.encodeAcknowledgement(CommandAcknowledgement("c", CommandOutcome.Applied, 4), snapshot)
        assertEquals("WATCH", JSONObject(ack.decodeToString()).getString("origin"))

        val none = snapshot.copy(origin = null)
        assertEquals(true, JSONObject(WatchWireCodec.encodeSnapshot(none).decodeToString()).isNull("origin"))
    }
}
