package com.apercallc.aidailymemory.upload

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.upload.NextUpload
import com.apercallc.aidailymemory.recording.upload.UploadPolicy
import com.apercallc.aidailymemory.recording.upload.UploadStop
import java.util.concurrent.ConcurrentHashMap
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

/** What to tell the JobScheduler and which follow-up job to schedule after an upload run. */
internal data class JobFinish(val reschedule: Boolean, val followUp: FollowUp) {
    enum class FollowUp { NONE, UPLOAD_NOW, UPLOAD_LATER, SIGN_OUT }

    companion object {
        fun of(next: NextUpload): JobFinish = when (next) {
            NextUpload.NOW -> JobFinish(false, FollowUp.UPLOAD_NOW)
            NextUpload.BACKOFF -> JobFinish(true, FollowUp.NONE)
            NextUpload.LATER -> JobFinish(false, FollowUp.UPLOAD_LATER)
            NextUpload.SIGN_OUT -> JobFinish(false, FollowUp.SIGN_OUT)
            NextUpload.NONE -> JobFinish(false, FollowUp.NONE)
        }
    }
}

class UploadJobService : JobService() {
    /** One handle per job id: the upload and revoke jobs run concurrently and stop separately. */
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as AiDailyMemoryApplication).container
        if (params.jobId == UploadScheduler.REVOKE_JOB_ID) {
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
        running[params.jobId] = container.appScope.launch(Dispatchers.IO) {
            val next = try {
                container.links.ensureLoaded()
                container.accountLinker.retryPendingRevoke()
                // No upload before this phone's key is registered for this token (ADR-0014):
                // otherwise the server would seal the batch to whatever key the token has.
                val registration = container.links.link.value?.let { link ->
                    container.deliveryReceiver.registrationStop(link)
                }
                if (registration != null) {
                    UploadPolicy.next(0, 0, registration, crashed = false)
                } else {
                    runUploads(container)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "upload_run_failed", e)
                NextUpload.BACKOFF
            }
            val finish = JobFinish.of(next)
            jobFinished(params, finish.reschedule)
            when (finish.followUp) {
                JobFinish.FollowUp.UPLOAD_NOW -> container.uploadScheduler.schedule()
                JobFinish.FollowUp.UPLOAD_LATER -> container.uploadScheduler.scheduleLater()
                JobFinish.FollowUp.SIGN_OUT -> container.clipUploader.lastLink?.let(container::onLinkRejected)
                JobFinish.FollowUp.NONE -> Unit
            }
        }
        return true
    }

    private suspend fun runUploads(container: AppContainer): NextUpload {
        val upload = container.clipUploader.run()
        // Collect whatever the server has finished, even if uploading stopped early.
        val delivery = container.deliveryReceiver.run()
        val stop = listOfNotNull(upload.stoppedBy, delivery.stoppedBy)
            .firstOrNull { it == UploadStop.UNAUTHORIZED } ?: upload.stoppedBy ?: delivery.stoppedBy
        return UploadPolicy.next(
            received = upload.received,
            remaining = upload.remaining,
            stoppedBy = stop,
            crashed = false,
            awaiting = container.database.transcripts().awaitingCount(),
        )
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        // Unplugged or off Wi-Fi: retry in the next eligible window.
        return true
    }

    private companion object {
        const val TAG = "UploadJob"
    }
}
