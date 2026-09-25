package com.apercallc.aidailymemory.analysis

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
import kotlinx.coroutines.launch

/**
 * Schedules speech analysis for when the phone is charging and the battery isn't low
 * (ADR-0015): daytime battery goes to capture only. Scheduling again replaces the pending job,
 * so calling this often is cheap and never stacks work.
 */
class SpeechAnalysisScheduler(context: Context) {
    private val app = context.applicationContext

    fun schedule() {
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, SpeechAnalysisJobService::class.java))
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setPersisted(true)
            .build()
        val result = app.getSystemService(JobScheduler::class.java).schedule(info)
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "speech_analysis_schedule_failed")
    }

    companion object {
        const val JOB_ID = 1_001
        private const val TAG = "SpeechAnalysis"
    }
}

/** Runs one batch; schedules the next batch if segments remain. Stops when the system asks. */
class SpeechAnalysisJobService : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as AiDailyMemoryApplication).container
        // Decoding and file reads block: keep them off the shared Default pool.
        running = container.appScope.launch(Dispatchers.IO) {
            var more = false
            try {
                more = container.speechAnalyzer.analyzePending().remaining > 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The segments stay pending; the next charge retries them.
                logFailure(TAG, "speech_analysis_failed", e)
            } finally {
                jobFinished(params, false)
            }
            if (more) container.speechScheduler.schedule() else container.afterSpeechAnalysis()
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running?.cancel()
        // Unplugged or the system needs resources: retry on the next charge.
        return true
    }

    private companion object {
        const val TAG = "SpeechAnalysis"
    }
}
