package com.apercallc.aidailymemory.analysis

import android.app.job.JobScheduler
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SpeechAnalysisSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val jobs = context.getSystemService(JobScheduler::class.java)

    @Test
    fun `analysis waits for the charger and a healthy battery, and survives reboot`() {
        SpeechAnalysisScheduler(context).schedule()

        val job = jobs.getPendingJob(SpeechAnalysisScheduler.JOB_ID)!!
        assertTrue(job.isRequireCharging)
        assertTrue(job.isRequireBatteryNotLow)
        assertTrue(job.isPersisted)
        assertEquals(SpeechAnalysisJobService::class.java.name, job.service.className)
    }

    @Test
    fun `scheduling again replaces the pending job instead of stacking`() {
        val scheduler = SpeechAnalysisScheduler(context)

        scheduler.schedule()
        scheduler.schedule()

        assertEquals(1, jobs.allPendingJobs.size)
    }
}
