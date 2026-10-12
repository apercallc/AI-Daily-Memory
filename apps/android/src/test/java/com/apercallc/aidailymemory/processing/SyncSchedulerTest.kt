package com.apercallc.aidailymemory.processing

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val jobs = context.getSystemService(JobScheduler::class.java)

    @Test
    fun `processing job retries with exponential backoff`() {
        try {
            ProcessingScheduler(context).schedule()

            val job = jobs.getPendingJob(ProcessingScheduler.JOB_ID)!!
            assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.backoffPolicy)
            assertEquals(ProcessingScheduler.RETRY_BACKOFF_MS, job.initialBackoffMillis)
            assertTrue(job.isPersisted)
            assertTrue(job.isRequireCharging)
        } finally {
            jobs.cancel(ProcessingScheduler.JOB_ID)
        }
    }

    @Test
    fun `sync job retries failed batches with exponential backoff`() {
        try {
            SyncScheduler(context).schedule()

            val job = jobs.getPendingJob(SyncScheduler.JOB_ID)!!
            assertEquals(JobInfo.BACKOFF_POLICY_EXPONENTIAL, job.backoffPolicy)
            assertEquals(SyncScheduler.RETRY_BACKOFF_MS, job.initialBackoffMillis)
            assertTrue(job.isPersisted)
            assertTrue(job.isRequireCharging)
        } finally {
            jobs.cancel(SyncScheduler.JOB_ID)
        }
    }
}
