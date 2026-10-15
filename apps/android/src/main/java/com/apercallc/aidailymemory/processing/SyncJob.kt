package com.apercallc.aidailymemory.processing

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.apercallc.aidailymemory.AiDailyMemoryApplication
import com.apercallc.aidailymemory.data.CaptureDatabase
import com.apercallc.aidailymemory.data.DailyReportEntity
import com.apercallc.aidailymemory.data.LocalTranscriptLineEntity
import com.apercallc.aidailymemory.data.MemoryItemEntity
import com.apercallc.aidailymemory.net.ServerLink
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.processing.SyncCoverage
import com.apercallc.aidailymemory.recording.processing.SyncItem
import com.apercallc.aidailymemory.recording.processing.SyncPayload
import com.apercallc.aidailymemory.recording.processing.SyncReport
import com.apercallc.aidailymemory.recording.processing.SyncSession
import com.apercallc.aidailymemory.recording.processing.SyncTranscriptLine
import com.apercallc.aidailymemory.settings.ProcessingSettings
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Schedules the optional API sync (ADR-0020). Runs while charging on an unmetered
 * network, matching the upload policy (ADR-0015): no mobile data and no daytime battery.
 * Scheduling again replaces the pending job, so calling this often is cheap and never stacks.
 */
class SyncScheduler(context: Context) {
    private val app = context.applicationContext

    fun schedule() {
        val info = JobInfo.Builder(JOB_ID, ComponentName(app, SyncJobService::class.java))
            .setRequiresCharging(true)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setBackoffCriteria(RETRY_BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .setPersisted(true)
            .build()
        val result = app.getSystemService(JobScheduler::class.java).schedule(info)
        if (result != JobScheduler.RESULT_SUCCESS) Log.w(TAG, "sync_schedule_failed")
    }

    fun cancel() {
        app.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }

    companion object {
        const val JOB_ID = 1_005
        const val RETRY_BACKOFF_MS = 30_000L
        private const val TAG = "SyncJob"
    }
}

/** Runs one sync batch; retries on the next schedule if it fails. Stops when the system asks. */
class SyncJobService : JobService() {
    private var running: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val container = (application as AiDailyMemoryApplication).container
        var needsReschedule = false
        running = container.appScope.launch(Dispatchers.IO) {
            try {
                needsReschedule = !container.syncManager.syncPending()
                if (needsReschedule) Log.w(TAG, "sync_pending_retry")
            } catch (e: CancellationException) {
                needsReschedule = true
                throw e
            } catch (e: Exception) {
                needsReschedule = true
                container.processingSettings.setLastSyncFailed(true)
                logFailure(TAG, "sync_failed", e)
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
        // Unplugged or off Wi-Fi: retry in the next eligible window.
        return true
    }

    private companion object {
        const val TAG = "SyncJob"
    }
}

/**
 * Builds a [SyncPayload] from local daily reports and memory items, then POSTs it to the
 * user-configured API endpoint. Only structured memory leaves the phone — never audio
 * (ADR-0020). Raw transcript lines are included only when the user opts in.
 *
 * The report cursor and last successful request time are tracked separately in
 * [ProcessingSettings]. A deletions-only success updates the request time without moving the
 * report cursor; failures leave both unchanged so the next schedule retries the same changes.
 *
 * Locally deleted sessions are queued (see `sync_deletion_queue`) and sent as
 * [SyncPayload.deletedSessions]; the queue clears only after the target accepted the payload.
 */
class SyncManager(
    private val database: CaptureDatabase,
    private val settings: ProcessingSettings,
    private val deviceId: String,
    /** The linked hosted account, or null while offline. Sync has no address of its own. */
    private val link: () -> ServerLink? = { null },
    /**
     * Reports generated before this time stay on the phone: connecting must not quietly send
     * everything written before (the same promise as the audio backlog choice). 0 = everything.
     */
    private val notBeforeEpochMs: () -> Long = { 0L },
    private val json: Json = Json { encodeDefaults = false },
    /**
     * Transport seam for tests. Null in production, where [post] opens the real connection.
     */
    private val poster: (suspend (url: String, token: String?, body: String) -> Unit)? = null,
    private val nowEpochMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Syncs all daily reports generated since the last successful sync and drains the deletion
     * queue. Returns true if everything was sent (or there was nothing to send); false if sync
     * is misconfigured or the endpoint rejected the payload (the next schedule retries).
     */
    suspend fun syncPending(): Boolean {
        // Disabling new copies never cancels a queued user-requested deletion.
        // Offline: nothing leaves the phone, which is the point of offline. Not a failure either,
        // or sync being on by default would show an error to everyone who never connected.
        val account = link() ?: return true
        val url = account.baseUrl.trimEnd('/') + SYNC_PATH
        val token = account.token
        val enabled = settings.syncEnabled.value
        val includeTranscripts = enabled && settings.syncIncludeTranscripts.value

        val since = maxOf(settings.lastSyncEpochMs ?: 0L, notBeforeEpochMs())
        val reports = if (enabled) database.dailyReports().since(since) else emptyList()
        val deletions = database.syncDeletions().pendingSessionIds()
        if (reports.isEmpty() && deletions.isEmpty()) return true

        val payload = buildPayload(reports, includeTranscripts, deletions)
        val body = json.encodeToString(SyncPayload.serializer(), payload)
        return try {
            if (poster != null) poster.invoke(url, token, body) else post(url, token, body)
            settings.setLastSyncSucceededAtEpochMs(nowEpochMs())
            // The cursor is the newest generated_at actually sent, never the wall clock: a
            // report generated while the POST was in flight must still be picked up next time.
            // A deletions-only payload leaves it untouched.
            reports.maxOfOrNull { it.generatedAtEpochMs }?.let { settings.lastSyncEpochMs = it }
            if (deletions.isNotEmpty()) database.syncDeletions().clear(deletions)
            settings.setLastSyncFailed(false)
            Log.i(TAG, "sync_succeeded reports=${reports.size} deletions=${deletions.size}")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(TAG, "sync_post_failed", e)
            settings.setLastSyncFailed(true)
            false
        }
    }

    private suspend fun buildPayload(
        reports: List<DailyReportEntity>,
        includeTranscripts: Boolean,
        deletedSessions: List<String>,
    ): SyncPayload {
        val syncReports = reports.map { report ->
            val items = database.memoryItems().observeForDay(report.memoryDay).first()
            val transcriptLines = if (includeTranscripts) {
                transcriptLinesForReport(report)
            } else {
                null
            }
            SyncReport(
                memoryDay = report.memoryDay,
                zoneId = report.zoneId,
                headline = report.headline,
                summary = report.summary,
                items = items.map { it.toSyncItem() },
                coverage = SyncCoverage(
                    recordedMs = 0L,
                    speechMs = 0L,
                    transcribedMs = 0L,
                    sessions = items.distinctBy { it.sessionId }.size,
                ),
                generatedAtEpochMs = report.generatedAtEpochMs,
                extractionModel = report.extractionModel,
                extractionVersion = report.extractionVersion,
                transcriptLines = transcriptLines,
                sessions = sessionsForReport(report.memoryDay, items),
            )
        }
        return SyncPayload(
            device = deviceId,
            generatedAtEpochMs = System.currentTimeMillis(),
            reports = syncReports,
            includeTranscripts = includeTranscripts,
            deletedSessions = deletedSessions,
        )
    }

    /**
     * The day's sessions that have words or items, with the person's own name for each. Sessions
     * still being recorded or not yet transcribed are left out: they would only be empty notes.
     */
    private suspend fun sessionsForReport(
        memoryDay: String,
        items: List<MemoryItemEntity>,
    ): List<SyncSession> {
        val info = database.sessions().infoForDay(memoryDay)
        if (info.isEmpty()) return emptyList()
        val withWords = database.localTranscripts().forSessions(info.map { it.sessionId })
            .map { it.sessionId }.toSet()
        val withItems = items.map { it.sessionId }.toSet()
        return info
            .filter { it.sessionId in withWords || it.sessionId in withItems }
            .map { SyncSession(it.sessionId, it.customTitle, it.startedAtEpochMs) }
    }

    /**
     * Includes every transcript line in the report's local calendar day, including sessions
     * whose extractor produced no memory items.
     */
    private suspend fun transcriptLinesForReport(
        report: DailyReportEntity,
    ): List<SyncTranscriptLine> {
        val sessionIds = database.sessions().sessionIdsForDay(report.memoryDay)
        if (sessionIds.isEmpty()) return emptyList()
        return database.localTranscripts().forSessions(sessionIds).map { it.toSyncTranscriptLine() }
    }

    /**
     * POSTs [body] to [url] with a Bearer auth header. Throws [IOException] on anything but a
     * full 2xx; the caller logs and leaves the timestamp unchanged for a retry.
     */
    private fun post(url: String, token: String?, body: String) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            // A redirect must never carry the bearer token to another host.
            connection.instanceFollowRedirects = false
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("sync_rejected status=$status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TAG = "SyncManager"
        const val SYNC_PATH = "/v1/sync"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

private fun MemoryItemEntity.toSyncItem(): SyncItem = SyncItem(
    kind = kind,
    text = text,
    confidence = confidence,
    quote = quote,
    owner = owner,
    due = due,
    direction = direction,
    counterpart = counterpart,
    sessionId = sessionId,
    evidenceEpochMs = evidenceEpochMs,
)

private fun LocalTranscriptLineEntity.toSyncTranscriptLine(): SyncTranscriptLine = SyncTranscriptLine(
    sessionId = sessionId,
    startEpochMs = startEpochMs,
    speaker = speaker,
    text = text,
)