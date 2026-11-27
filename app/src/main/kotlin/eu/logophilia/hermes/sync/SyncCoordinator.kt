package eu.logophilia.hermes.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log
import java.util.concurrent.Executors

/**
 * Observable synchronization status, shown by the Sync screen and the status
 * indicators (§8). Only in-memory state: nothing but `lastSyncAttempt` is
 * persisted (§2).
 */
data class SyncStatus(
    val running: Boolean = false,
    val lastOutcome: SyncOutcome? = null,
)

/**
 * Runs the blocking [SyncEngine] on a single background thread and exposes
 * the resulting [SyncStatus]. Implements the §4 triggers:
 *
 * - application opened or returned to the foreground (§4 Triggers),
 * - a network became available while the application is running (§4),
 * - manual synchronization, which obeys the same cooldown (§4).
 *
 * The engine enforces the gate, the cooldown and single-flight, so a burst
 * of trigger events results in at most one attempt and harmless skips.
 */
class SyncCoordinator(private val engine: SyncEngine) {

    private val executor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hermes-sync").apply { isDaemon = true } }

    private val mutableStatus = MutableStateFlow(SyncStatus())

    /** Current synchronization status, for the UI (§8). */
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    /** §4 trigger: application opened or returned to the foreground. */
    fun onForeground() = request()

    /** §4 trigger: a qualifying network became available while running. */
    fun onNetworkAvailable() = request()

    /** §4: manual synchronization obeys the same cooldown. */
    fun requestSync() = request()

    /** Last attempt time from the persisted store (§8 Sync screen). */
    fun lastSyncAttemptMillis(): Long? = engine.lastSyncAttemptMillis()

    /** Milliseconds until manual synchronization becomes possible again. */
    fun cooldownRemainingMillis(): Long = engine.cooldownRemainingMillis()

    private fun request() {
        mutableStatus.value = mutableStatus.value.copy(running = true)
        executor.execute {
            val outcome = try {
                engine.attempt()
            } catch (t: Throwable) {
                // The engine converts Exception into Failure itself; this is
                // a last resort so the status can never be left stuck.
                SyncOutcome.Failure("Unexpected failure: ${t.javaClass.simpleName}: ${t.message ?: ""}")
            }
            mutableStatus.value = SyncStatus(running = false, lastOutcome = outcome)
            // One concise line per attempt; never any API key material (§3).
            Log.i(TAG, "sync outcome: $outcome")
        }
    }

    private companion object {
        const val TAG = "hermes-sync"
    }
}
