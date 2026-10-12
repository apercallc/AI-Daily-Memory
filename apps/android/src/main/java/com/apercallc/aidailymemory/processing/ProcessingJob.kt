package com.apercallc.aidailymemory.processing

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.platform.logFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Schedules on-device processing for when the phone is charging and the battery isn't low
 * (ADR-0020): daytime battery goes to capture only. Scheduling again replaces the pending job.
 */
class ProcessingScheduler(context: Context) {
    private val app = context.applicationContext

    fun schedule() {
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, ProcessingJobService::class.java))
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setBackoffCriteria(RETRY_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()
        val result = app.getSystemService(JobScheduler::class.java).schedule(info)
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "processing_schedule_failed")
    }

    fun cancel() {
        app.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    companion object {
        const val JOB_ID = 1_004
        const val RETRY_BACKOFF_MS = 30_000L
        private const val TAG = "ProcessingScheduler"
    }
}

/** Runs one processing batch; reschedules if sessions remain. */
class ProcessingJobService : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as AiDailyMemoryApplication).container
        var needsReschedule = false
        running = container.appScope.launch(Dispatchers.IO) {
            try {
                needsReschedule = container.processingPipeline.processPending().remaining > 0
                if (container.processingSettings.syncEnabled.value) {
                    // A sync job can run before processing creates today's report and return
                    // successfully with nothing to send. Schedule again after processing completes.
                    container.syncScheduler.schedule()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                needsReschedule = true
                logFailure(TAG, "processing_job_failed", e)
            } finally {
                if (currentCoroutineContext().isActive) {
                    jobFinished(params, needsReschedule)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    private companion object {
        const val TAG = "ProcessingJobService"
    }
}