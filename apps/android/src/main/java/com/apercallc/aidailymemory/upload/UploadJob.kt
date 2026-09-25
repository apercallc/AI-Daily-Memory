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
import com.apercallc.aidailymemory.recording.upload.UploadStop
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

    /** Transcripts are still being made on the server: look again after [DELIVERY_POLL_MS]. */
    fun scheduleLater() {
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, UploadJobService::class.java))
            .setRequiresCharging(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setMinimumLatency(DELIVERY_POLL_MS)
            .setBackoffCriteria(INITIAL_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()
        val result = app.getSystemService(JobScheduler::class.java).schedule(info)
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "upload_schedule_failed")
    }

    fun cancel() {
        app.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
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
        const val JOB_ID = 1_002
        const val REVOKE_JOB_ID = 1_003
        /** Doubles per failed attempt; the system caps it at five hours. */
        const val INITIAL_BACKOFF_MS = 10 * 60_000L
        const val DELIVERY_POLL_MS = 30 * 60_000L
        private const val TAG = "UploadJob"
    }
}

class UploadJobService : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as AiDailyMemoryApplication).container
        if (params.jobId == UploadScheduler.REVOKE_JOB_ID) {
            running = container.appScope.launch(Dispatchers.IO) {
                container.links.ensureLoaded()
                jobFinished(params, !container.accountLinker.retryPendingRevoke())
            }
            return true
        }
        running = container.appScope.launch(Dispatchers.IO) {
            val next = try {
                container.links.ensureLoaded()
                container.accountLinker.retryPendingRevoke()
                // Phone storage modes need the key registered before any upload (ADR-0014).
                container.links.link.value?.let { link ->
                    try {
                        container.deliveryReceiver.ensureRegistered(link)
                    } catch (e: IOException) {
                        // The uploads that need it retry; the delivery run reports the stop.
                        logFailure(TAG, "device_key_register_failed", e, warning = true)
                    }
                }
                val upload = container.clipUploader.run()
                // Collect whatever the server has finished, even if uploading stopped early.
                val delivery = container.deliveryReceiver.run()
                val stop = listOfNotNull(upload.stoppedBy, delivery.stoppedBy)
                    .firstOrNull { it == UploadStop.UNAUTHORIZED } ?: upload.stoppedBy ?: delivery.stoppedBy
                UploadPolicy.next(
                    received = upload.received,
                    remaining = upload.remaining,
                    stoppedBy = stop,
                    crashed = false,
                    awaiting = container.database.transcripts().awaitingCount(),
                )
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
                NextUpload.LATER -> {
                    jobFinished(params, false)
                    container.uploadScheduler.scheduleLater()
                }
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
