package com.apercallc.aidailymemory.service

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

class BroadcastWorkTest {
    @Test
    fun `finished work reports true`() = runTest {
        var ran = false
        assertTrue(awaitWithin(BROADCAST_BUDGET_MS) { ran = true })
        assertTrue(ran)
    }

    @Test
    fun `work that outlives the budget is abandoned and reports false`() = runTest {
        assertFalse(awaitWithin(BROADCAST_BUDGET_MS) { delay(BROADCAST_BUDGET_MS + 1) })
    }
}
