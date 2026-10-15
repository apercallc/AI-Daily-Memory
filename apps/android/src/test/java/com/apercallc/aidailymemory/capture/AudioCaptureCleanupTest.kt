package com.apercallc.aidailymemory.capture

import org.junit.Test
import kotlin.test.assertEquals

class AudioCaptureCleanupTest {
    @Test
    fun `cleanup attempts every action and returns failures for logging`() {
        val events = mutableListOf<String>()
        val listenerFailure = IllegalStateException("listener removal failed")
        val actions = listOf(
            "unregister" to { events += "unregister" },
            "remove_route_listener" to {
                events += "remove_route_listener"
                throw listenerFailure
            },
            "stop" to { events += "stop" },
            "release" to { events += "release" },
        )

        val failures = runCaptureCleanup(actions)

        assertEquals(listOf("unregister", "remove_route_listener", "stop", "release"), events)
        assertEquals(listOf("remove_route_listener" to listenerFailure), failures)
    }

    @Test
    fun `cleanup continues after recorder stop fails`() {
        val events = mutableListOf<String>()
        val stopFailure = IllegalStateException("stop failed")
        val actions = listOf(
            "stop" to {
                events += "stop"
                throw stopFailure
            },
            "release" to { events += "release" },
        )

        val failures = runCaptureCleanup(actions)

        assertEquals(listOf("stop", "release"), events)
        assertEquals(listOf("stop" to stopFailure), failures)
    }
}
