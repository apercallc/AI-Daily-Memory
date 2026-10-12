package com.apercallc.aidailymemory.schedule

import com.apercallc.aidailymemory.data.SessionDao
import com.apercallc.aidailymemory.data.SessionInfoEntity
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.schedule.DailyRhythm
import com.apercallc.aidailymemory.recording.schedule.MemoryDays
import com.apercallc.aidailymemory.recording.session.SessionTitles
import com.apercallc.aidailymemory.recording.session.TitleInput
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** Result of a rename; the UI shows the reason when it isn't [Saved]. */
enum class RenameResult { SAVED, RESET, TOO_LONG, INVALID_CHARACTERS, UNKNOWN_SESSION }

/**
 * Registers each session the first time it's seen, fixing its memory day at that moment, and
 * handles renames. Observes state instead of being called by the controller, so the journal
 * stays the only thing on the capture path.
 */
class SessionRegistry(
    private val dao: SessionDao,
    private val rhythm: () -> DailyRhythm,
    private val now: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /**
     * Registers on every state change, not only the first: the insert is idempotent, and a
     * failed registration (a busy or full database) is retried by the session's next state
     * change instead of leaving it missing from the list for good.
     */
    suspend fun observe(state: StateFlow<RecordingState>) {
        var registered: String? = null
        state.collect { current ->
            val sessionId = current.sessionId?.value ?: return@collect
            if (sessionId == registered) return@collect
            try {
                register(sessionId)
                registered = sessionId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Metadata only: capture is unaffected; the next state change tries again.
                logFailure(TAG, "session_register_failed", e)
            }
        }
    }

    suspend fun register(sessionId: String) {
        val at = now()
        val zoneId = zone()
        dao.registerIfJournaled(
            SessionInfoEntity(
                sessionId = sessionId,
                memoryDay = MemoryDays.dayOf(at, zoneId, rhythm()).toString(),
                zoneId = zoneId.id,
                startedAtEpochMs = at.toEpochMilli(),
                customTitle = null,
            ),
        )
    }

    suspend fun rename(sessionId: String, input: String): RenameResult {
        val (title, result) = when (val parsed = SessionTitles.parse(input)) {
            is TitleInput.Valid -> parsed.title to RenameResult.SAVED
            TitleInput.Clear -> null to RenameResult.RESET
            TitleInput.TooLong -> return RenameResult.TOO_LONG
            TitleInput.InvalidCharacters -> return RenameResult.INVALID_CHARACTERS
        }
        return if (dao.setTitle(sessionId, title) == 1) result else RenameResult.UNKNOWN_SESSION
    }

    private companion object {
        const val TAG = "SessionRegistry"
    }
}
