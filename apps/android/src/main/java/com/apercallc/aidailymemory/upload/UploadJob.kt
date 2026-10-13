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
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Audio no longer leaves the phone (ADR-0026), so there is nothing to upload. This job service
 * keeps its original name because the system stores scheduled jobs by component: renaming it
 * would silently drop a revoke that an older version had queued. It now does one thing, retrying
 * the server-side revoke of a token the person disconnected.
 */
class UploadScheduler(context: Context) {
    private val app = context.applicationContext

    /** Drops the audio-upload job that earlier versions scheduled. */
    fun cancelLegacyUpload() {
        app.getSystemService(JobScheduler::class.java).cancel(LEGACY_UPLOAD_JOB_ID)
    }

    /** Retries a disconnected token's server-side revoke on any network (no charger needed). */
    fun scheduleRevoke() {
        val info = JobInfo.Builder(REVOKE_JOB_ID, ComponentName(app, UploadJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setBackoffCriteria(INITIAL_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()
        val result = app.getSystemService(JobScheduler::class.java).schedule(info)
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "revoke_schedule_failed")
    }

    companion object {
        const val LEGACY_UPLOAD_JOB_ID = 1_002
        const val REVOKE_JOB_ID = 1_003
        /** Doubles per failed attempt; the system caps it at five hours. */
        const val INITIAL_BACKOFF_MS = 10 * 60_000L
        private const val TAG = "UploadJob"
    }
}

class UploadJobService : JobService() {
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId != UploadScheduler.REVOKE_JOB_ID) {
            // A persisted upload job from an earlier version: nothing left to do, never again.
            jobScheduler().cancel(params.jobId)
            return false
        }
        val container = (application as AiDailyMemoryApplication).container
        running[params.jobId] = container.appScope.launch(Dispatchers.IO) {
            val retry = try {
                container.links.ensureLoaded()
                !container.accountLinker.retryPendingRevoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "revoke_run_failed", e)
                true
            }
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    private fun jobScheduler() = getSystemService(JobScheduler::class.java)

    private companion object {
        const val TAG = "UploadJob"
    }
}
