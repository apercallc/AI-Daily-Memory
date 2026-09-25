package com.apercallc.aidailymemory.capture

import android.util.Log
import com.apercallc.aidailymemory.data.CaptureTimeColumns
import com.apercallc.aidailymemory.data.TransitionDao
import com.apercallc.aidailymemory.data.TransitionEntity
import com.apercallc.aidailymemory.platform.logFailure
import com.apercallc.aidailymemory.recording.CaptureProblem
import com.apercallc.aidailymemory.recording.RecordingCommand
import com.apercallc.aidailymemory.recording.RecordingEvent
import com.apercallc.aidailymemory.recording.RecordingState
import com.apercallc.aidailymemory.recording.RecordingStateMachine
import com.apercallc.aidailymemory.recording.Transition
import com.apercallc.aidailymemory.recording.effects.CaptureEffect
import com.apercallc.aidailymemory.recording.effects.CaptureEffectPlanner
import com.apercallc.aidailymemory.recording.effects.EngineReport
import com.apercallc.aidailymemory.recording.effects.EngineSignals
import com.apercallc.aidailymemory.recording.journal.JournalCodec
import com.apercallc.aidailymemory.recording.journal.JournalRecord
import com.apercallc.aidailymemory.recording.journal.RestoredState
import com.apercallc.aidailymemory.recording.recovery.BootStamp
import com.apercallc.aidailymemory.recording.recovery.RestartClassifier
import com.apercallc.aidailymemory.recording.recovery.StartupRecovery
import com.apercallc.aidailymemory.recording.time.CaptureTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable, append-only transition log (the Room table in production, a list in tests). */
interface TransitionJournal {
    suspend fun latest(): Pair<JournalRecord, CaptureTime>?
    suspend fun append(record: JournalRecord, time: CaptureTime)
}

class RoomTransitionJournal(private val dao: TransitionDao) : TransitionJournal {
    override suspend fun latest(): Pair<JournalRecord, CaptureTime>? =
        dao.latest()?.let { it.toRecord() to it.time.toCaptureTime() }

    override suspend fun append(record: JournalRecord, time: CaptureTime) =
        dao.append(TransitionEntity.of(record, CaptureTimeColumns.of(time)))
}

/**
 * Executes planned effects. Returns follow-up events (e.g. `Stopped` once a segment is
 * sealed) instead of dispatching them, so an effect can never re-enter the controller's lock.
 */
fun interface EffectHandler {
    suspend fun execute(effect: CaptureEffect, state: RecordingState): List<RecordingEvent>
}

/** The persisted state was restored at process start. */
data class RestoreReport(val restored: RestoredState, val recoveryEvents: List<RecordingEvent>)

/**
 * The single writer of recording state. Every event, from any surface, goes through
 * [dispatch]: reduce → append to the journal → publish → run effects. The journal write comes
 * first, so a crash after it replays into the same state and never loses a transition that
 * already had visible effects (TODO.md "Persist state transitions ... before side effects").
 */
class RecordingController(
    private val journal: TransitionJournal,
    private val now: () -> CaptureTime,
    private val currentBoot: () -> BootStamp,
    private val bootOf: (CaptureTime) -> BootStamp,
    private val elapsedRealtimeMs: () -> Long,
    private val scope: CoroutineScope,
    /** Runs effects while no recorder service is attached (e.g. right after a restart). */
    private val detached: EffectHandler,
    /** Cleans up after a dead process before recovery events are applied. */
    private val beforeRecovery: suspend () -> Unit,
) {
    private val lock = Mutex()
    private val restored = CompletableDeferred<RestoreReport>()

    private val _state = MutableStateFlow(RecordingState.Initial)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _statusSinceElapsedMs = MutableStateFlow(elapsedRealtimeMs())

    /** Monotonic time the current status was entered; feeds stall detection. */
    val statusSinceElapsedMs: StateFlow<Long> = _statusSinceElapsedMs.asStateFlow()

    private val _rejections = MutableSharedFlow<Transition.Rejected>(extraBufferCapacity = 16)

    /** Rejected events, for surfaces that must tell the user an action did nothing. */
    val rejections: SharedFlow<Transition.Rejected> = _rejections.asSharedFlow()

    @Volatile private var attached: EffectHandler? = null

    fun attach(handler: EffectHandler) {
        attached = handler
    }

    fun detach(handler: EffectHandler) {
        if (attached === handler) attached = null
    }

    /**
     * Restores persisted state once per process. Every dispatch waits for this to finish, so
     * it always finishes: if the journal itself can't be read, the app starts idle with a
     * visible problem instead of hanging every button and the boot receiver forever. (Writes
     * will then fail loudly too; the database is unusable and the user sees it.)
     */
    fun restore() {
        scope.launch {
            val report = try {
                lock.withLock { withContext(NonCancellable) { restoreLocked() } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logFailure(TAG, "journal_restore_failed", e)
                val fallback = RecordingState.Initial.copy(problem = CaptureProblem.UNKNOWN)
                _state.value = fallback
                RestoreReport(RestoredState.Corrupt(fallback, revision = -1), emptyList())
            }
            restored.complete(report)
        }
    }

    suspend fun awaitRestored(): RestoreReport = restored.await()

    suspend fun dispatch(event: RecordingEvent): Transition = locked { applyWithFollowUps(event) }

    /** Maps an engine observation to a signal against the state it is applied to. */
    suspend fun report(report: EngineReport): Transition =
        locked { applyWithFollowUps(EngineSignals.signalFor(_state.value, report)) }

    /**
     * Once the lock is held, the whole reduce → journal → effects sequence runs to completion
     * even if the caller is cancelled (a destroyed service, a superseded recovery wait).
     * Stopping halfway would leave the journal, the published state, and the microphone
     * disagreeing.
     */
    private suspend fun locked(block: suspend () -> Transition): Transition {
        restored.await()
        return lock.withLock { withContext(NonCancellable) { block() } }
    }

    private suspend fun restoreLocked(): RestoreReport {
        val latest = journal.latest()
        val restoredState = JournalCodec.restore(latest?.first)
        if (restoredState is RestoredState.Corrupt) {
            Log.e(TAG, "journal_corrupt revision=${restoredState.revision}")
        }
        _state.value = restoredState.state
        _statusSinceElapsedMs.value = elapsedRealtimeMs()
        // No engine runs yet in this process, so any WRITING segment is a torn leftover.
        // Cleanup failing must not lose the journal state we already read: the revision
        // has to stay monotonic, or every later journal write would collide.
        try {
            beforeRecovery()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(TAG, "restore_cleanup_failed", e)
        }
        val events = latest?.let { (_, time) ->
            val cause = RestartClassifier.classify(bootOf(time), currentBoot())
            StartupRecovery.eventsFor(restoredState.state, cause)
        }.orEmpty()
        events.forEach { applyWithFollowUps(it) }
        return RestoreReport(restoredState, events)
    }

    private suspend fun applyWithFollowUps(first: RecordingEvent): Transition {
        val result = applyOne(first)
        val queue = ArrayDeque(result.second)
        // Bounded: each follow-up is a signal that moves the session forward, never a loop.
        var budget = MAX_FOLLOW_UPS
        while (queue.isNotEmpty() && budget-- > 0) {
            queue.addAll(applyOne(queue.removeFirst()).second)
        }
        if (queue.isNotEmpty()) Log.e(TAG, "follow_up_budget_exceeded")
        return result.first
    }

    private suspend fun applyOne(event: RecordingEvent): Pair<Transition, List<RecordingEvent>> {
        val transition = RecordingStateMachine.reduce(_state.value, event)
        when (transition) {
            is Transition.Applied -> {
                journal.append(JournalCodec.encode(transition), now())
                _state.value = transition.state
                _statusSinceElapsedMs.value = elapsedRealtimeMs()
                Log.i(TAG, "transition_applied")
            }
            is Transition.Ignored -> return transition to emptyList()
            is Transition.Rejected -> {
                // Never silent: always logged. Only a person's command is surfaced to the UI:
                // a late engine signal is not something the user did, so telling them "that
                // action isn't available" would be wrong.
                val cause = JournalCodec.causeOf(event)
                Log.w(TAG, "transition_rejected cause=$cause status=${transition.state.status}")
                if (event is RecordingCommand) _rejections.tryEmit(transition)
                return transition to emptyList()
            }
        }
        val handler = attached ?: detached
        for (effect in CaptureEffectPlanner.effectsFor(transition)) {
            val followUps = handler.execute(effect, transition.state)
            // A follow-up means the effect failed or finished the state (e.g. the service
            // could not enter the foreground). Later effects were planned for a state that no
            // longer holds, so they must not run: never open the mic without the service.
            if (followUps.isNotEmpty()) return transition to followUps
        }
        return transition to emptyList()
    }

    private companion object {
        const val TAG = "RecordingController"
        const val MAX_FOLLOW_UPS = 8
    }
}
