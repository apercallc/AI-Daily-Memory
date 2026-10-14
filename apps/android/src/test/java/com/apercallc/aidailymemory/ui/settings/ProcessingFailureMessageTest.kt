package com.apercallc.aidailymemory.ui.settings

import com.apercallc.aidailymemory.R
import kotlin.test.Test
import kotlin.test.assertEquals

class ProcessingFailureMessageTest {
    @Test
    fun `provider failures map to specific safe guidance`() {
        assertEquals(
            R.string.processing_provider_quota_exhausted,
            processingFailureMessageResource("provider_quota_exhausted"),
        )
        assertEquals(
            R.string.processing_provider_rate_limited,
            processingFailureMessageResource("provider_rate_limited"),
        )
        assertEquals(
            R.string.processing_provider_auth_failed,
            processingFailureMessageResource("provider_auth_failed"),
        )
        assertEquals(R.string.processing_provider_failed_hint, processingFailureMessageResource("unknown"))
    }
}
