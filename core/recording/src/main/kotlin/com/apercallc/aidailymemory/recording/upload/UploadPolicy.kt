package com.apercallc.aidailymemory.recording.upload

/** Why an upload run stopped early. */
enum class UploadStop {
    /** No account linked: nothing to do until the user connects. */
    NOT_LINKED,

    /** Network unreachable or a request timed out. */
    OFFLINE,

    /** The server refused the device token itself (not storage, not a clip). */
    UNAUTHORIZED,

    /** Daily quota reached. */
    QUOTA,

    /** Server error or processing unavailable. */
    SERVER,
}

/** What the upload job does after a run. */
enum class NextUpload {
    /** Done, or nothing can happen until something changes (new audio, reconnect). */
    NONE,

    /** Progress was made and more work is waiting: run again right away. */
    NOW,

    /** Transient trouble: let the system retry with exponential backoff. */
    BACKOFF,

    /** Uploads are done but transcripts are still being made: check back after a while. */
    LATER,

    /** The token was refused: forget it and ask the user to reconnect. */
    SIGN_OUT,
}

/**
 * Pure decision for the upload job, so the job never spins: an immediate re-run only follows a
 * run that moved something forward. Anything else waits for backoff or for a new trigger.
 */
object UploadPolicy {
    /**
     * @param received clips the server accepted in this run
     * @param remaining clips or segments still to upload
     * @param awaiting clips on the server whose transcripts haven't reached the phone yet
     */
    fun next(
        received: Int,
        remaining: Int,
        stoppedBy: UploadStop?,
        crashed: Boolean,
        awaiting: Int = 0,
    ): NextUpload {
        require(received >= 0 && remaining >= 0 && awaiting >= 0) { "counts must not be negative" }
        return when {
            crashed -> NextUpload.BACKOFF
            stoppedBy == UploadStop.UNAUTHORIZED -> NextUpload.SIGN_OUT
            stoppedBy == UploadStop.NOT_LINKED -> NextUpload.NONE
            stoppedBy != null -> NextUpload.BACKOFF
            remaining > 0 && received > 0 -> NextUpload.NOW
            // Work remains but nothing moved (only retries pending): don't hammer the server.
            remaining > 0 -> NextUpload.BACKOFF
            awaiting > 0 -> NextUpload.LATER
            else -> NextUpload.NONE
        }
    }
}
