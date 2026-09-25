package com.apercallc.aidailymemory.upload

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Uploads run while charging on an unmetered network (ADR-0015): no mobile data and no
 * daytime battery. Only scheduled once the phone is linked to an account.
 */
class UploadScheduler(context: Context) {
    private val app = context.applicationContext

    fun schedule() {
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, UploadJobService::class.java))
            .setRequiresCharging(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setPersisted(true)
            .build()
        val result = app.getSystemService(JobScheduler::class.java).schedule(info)
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "upload_schedule_failed")
    }

    fun cancel() {
        app.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    companion object {
        const val JOB_ID = 1_002
        private const val TAG = "UploadJob"
    }
}

class UploadJobService : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as AiDailyMemoryApplication).container
        running = container.appScope.launch {
            var reschedule = false
            try {
                val run = container.clipUploader.run()
                // More work, or a transient stop: try again on the next eligible window.
                reschedule = run.remaining > 0 && run.stoppedBy in RETRY_LATER
                if (run.stoppedBy == UploadStop.UNAUTHORIZED) container.onLinkRejected()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "upload_run_failed", e)
                reschedule = true
            } finally {
                jobFinished(params, false)
            }
            if (reschedule) container.uploadScheduler.schedule()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        return true
    }

    private companion object {
        const val TAG = "UploadJob"
        val RETRY_LATER = setOf(null, UploadStop.OFFLINE, UploadStop.SERVER, UploadStop.QUOTA)
    }
}
