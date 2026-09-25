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
import com.apercallc.aidailymemory.recording.upload.NextUpload
import com.apercallc.aidailymemory.recording.upload.UploadPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Uploads run while charging on an unmetered network (ADR-0015): no mobile data and no
 * daytime battery. Only scheduled once the phone is linked to an account. Transient failures
 * use the system's exponential backoff, so a down server is never hammered.
 */
class UploadScheduler(context: Context) {
    private val app = context.applicationContext

    fun schedule() {
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, UploadJobService::class.java))
            .setRequiresCharging(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setBackoffCriteria(INITIAL_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
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
        /** Doubles per failed attempt; the system caps it at five hours. */
        const val INITIAL_BACKOFF_MS = 10 * 60_000L
        private const val TAG = "UploadJob"
    }
}

class UploadJobService : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as AiDailyMemoryApplication).container
        running = container.appScope.launch {
            val next = try {
                container.links.ensureLoaded()
                val run = container.clipUploader.run()
                UploadPolicy.next(run.received, run.remaining, run.stoppedBy, crashed = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "upload_run_failed", e)
                NextUpload.BACKOFF
            }
            when (next) {
                NextUpload.NOW -> {
                    jobFinished(params, false)
                    container.uploadScheduler.schedule()
                }
                NextUpload.BACKOFF -> jobFinished(params, true)
                NextUpload.SIGN_OUT -> {
                    container.clipUploader.lastLink?.let(container::onLinkRejected)
                    jobFinished(params, false)
                }
                NextUpload.NONE -> jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        // Unplugged or off Wi-Fi: retry in the next eligible window.
        return true
    }

    private companion object {
        const val TAG = "UploadJob"
    }
}
