package com.apercallc.aidailymemory.wear

import io.sentry.SentryEvent
import io.sentry.protocol.DebugMeta
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentryConfigurationTest {
    @Test
    fun scrubEventRemovesPrivateContextAndPathsButKeepsDiagnosticLocation() {
        val frame = SentryStackFrame().apply {
            filename = "/private/watch/path/WearMainActivity.kt"
            lineno = 23
            absPath = "/private/watch/path/WearMainActivity.kt"
            vars = mapOf("conversation" to "private")
            contextLine = "private source"
        }
        val exception = SentryException().apply {
            type = "IllegalStateException"
            value = "private conversation"
            stacktrace = SentryStackTrace(listOf(frame))
        }
        val event = SentryEvent().apply {
            message = Message().apply { message = "private text" }
            request = Request().apply { url = "https://api.example.test/?q=private" }
            user = User().apply { id = "private-user" }
            transaction = "/users/private-user"
            serverName = "private-watch"
            debugMeta = DebugMeta()
            contexts["device"] = "private"
            exceptions = mutableListOf(exception)
        }

        val scrubbed = SentryConfiguration.scrubEvent(event)

        assertNull(scrubbed.message)
        assertNull(scrubbed.request)
        assertNull(scrubbed.user)
        assertNull(scrubbed.transaction)
        assertNull(scrubbed.serverName)
        assertNull(scrubbed.debugMeta)
        assertTrue(scrubbed.contexts.isEmpty())
        val keptException = scrubbed.exceptions!!.single()
        assertEquals("IllegalStateException", keptException.type)
        assertNull(keptException.value)
        val keptFrame = keptException.stacktrace!!.frames!!.single()
        assertEquals("WearMainActivity.kt", keptFrame.filename)
        assertEquals(23, keptFrame.lineno)
        assertNull(keptFrame.absPath)
        assertNull(keptFrame.vars)
        assertNull(keptFrame.contextLine)
    }
}
