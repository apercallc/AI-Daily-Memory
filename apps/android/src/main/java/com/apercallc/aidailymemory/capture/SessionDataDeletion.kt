package com.apercallc.aidailymemory.capture

import com.apercallc.aidailymemory.data.SessionDao

sealed interface SessionDataDeletionResult {
    data object Deleted : SessionDataDeletionResult
    data object SessionActive : SessionDataDeletionResult
    data object RemoteCopyExists : SessionDataDeletionResult
}

/** Deletes one stopped session locally, audio first and derived database evidence second. */
class SessionDataDeletion(
    private val sessions: SessionDao,
    private val deleteAudio: suspend (String) -> UserAudioDeletionResult,
    private val hasServerSideData: suspend (String) -> Boolean,
    private val isActive: (String) -> Boolean,
    /**
     * Invoked after a successful local deletion, so derived data already pushed to a sync
     * target can be queued for server-side purge (ADR-0020). Never called for refused
     * deletions — those leave everything in place.
     */
    private val onDeleted: (suspend (String) -> Unit)? = null,
) {
    suspend fun delete(sessionId: String): SessionDataDeletionResult {
        if (isActive(sessionId)) return SessionDataDeletionResult.SessionActive
        if (hasServerSideData(sessionId)) return SessionDataDeletionResult.RemoteCopyExists
        return when (deleteAudio(sessionId)) {
            is UserAudioDeletionResult.Deleted -> {
                sessions.deleteSession(sessionId)
                onDeleted?.invoke(sessionId)
                SessionDataDeletionResult.Deleted
            }
            UserAudioDeletionResult.SessionActive -> SessionDataDeletionResult.SessionActive
            UserAudioDeletionResult.RemoteCopyExists -> SessionDataDeletionResult.RemoteCopyExists
        }
    }
}
