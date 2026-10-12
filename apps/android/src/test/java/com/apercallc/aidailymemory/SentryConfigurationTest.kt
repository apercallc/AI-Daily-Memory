package com.apercallc.aidailymemory

import io.sentry.SentryEvent
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
    fun scrubEventRemovesSensitiveContextButKeepsExceptionTypeAndStackLocation() {
        val frame = SentryStackFrame().apply {
            filename = "/private/device/path/ProcessingJob.kt"
            lineno = 41
            absPath = "/private/device/path"
            vars = mapOf("transcript" to "private conversation")
            contextLine = "private source context"
        }
        val exception = SentryException().apply {
            type = "IllegalStateException"
            value = "private conversation"
            stacktrace = SentryStackTrace(listOf(frame))
        }
        val event = SentryEvent().apply {
            message = Message().apply { message = "private message" }
            request = Request().apply { url = "https://api.example.test/?query=private" }
            user = User().apply { id = "private-user" }
            transaction = "/users/private-user"
            serverName = "private-device-host"
            debugMeta = io.sentry.protocol.DebugMeta()
            contexts["private"] = "context"
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
        assertEquals("ProcessingJob.kt", keptFrame.filename)
        assertEquals(41, keptFrame.lineno)
        assertNull(keptFrame.absPath)
        assertNull(keptFrame.vars)
        assertNull(keptFrame.contextLine)
    }
}
