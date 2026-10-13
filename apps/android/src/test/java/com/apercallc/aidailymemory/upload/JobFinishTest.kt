package com.apercallc.aidailymemory.upload

import com.apercallc.aidailymemory.recording.upload.NextUpload
import kotlin.test.Test
import kotlin.test.assertEquals

class JobFinishTest {
    @Test
    fun `only backoff asks the system to reschedule`() {
        NextUpload.entries.forEach { next ->
            assertEquals(next == NextUpload.BACKOFF, JobFinish.of(next).reschedule, next.name)
        }
    }

    @Test
    fun `each outcome maps to its follow-up job`() {
        assertEquals(JobFinish.FollowUp.UPLOAD_NOW, JobFinish.of(NextUpload.NOW).followUp)
        assertEquals(JobFinish.FollowUp.UPLOAD_LATER, JobFinish.of(NextUpload.LATER).followUp)
        assertEquals(JobFinish.FollowUp.SIGN_OUT, JobFinish.of(NextUpload.SIGN_OUT).followUp)
        assertEquals(JobFinish.FollowUp.NONE, JobFinish.of(NextUpload.NONE).followUp)
        assertEquals(JobFinish.FollowUp.NONE, JobFinish.of(NextUpload.BACKOFF).followUp)
    }
}
