package eu.logophilia.hermes.sync

import android.util.Log
import eu.logophilia.hermes.api.GreetingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * The Wi-Fi indicator states (§8): neutral only before the very first probe,
 * red without any request when no validated unmetered network exists, amber
 * when the greeting failed on an unmetered network, green on success.
 */
enum class WifiIndicator { NEUTRAL, RED, AMBER, GREEN }

/**
 * Observable synchronization status, shown by the Sync screen and the status
 * indicators (§8). Only in-memory state: nothing but `lastSyncAttempt` is
 * persisted (§2).
 */
data class SyncStatus(
    val running: Boolean = false,
    val phase: SyncPhase? = null,
    val lastOutcome: SyncOutcome? = null,
)

/**
 * Runs the blocking [SyncEngine] on a single background thread, probes the
 * server on the §4 trigger events, and exposes [SyncStatus] and the Wi-Fi
 * indicator state. Implements the §4 triggers:
 *
 * - application opened or returned to the foreground (§4 Triggers),
 * - a network became available while the application is running (§4),
 * - manual synchronization, which obeys the same cooldown (§4).
 *
 * The engine enforces the gate, the cooldown and single-flight, so a burst
 * of trigger events results in at most one attempt and harmless skips.
 */
class SyncCoordinator(
    private val engine: SyncEngine,
    private val networkGate: () -> Boolean,
    private val greeting: () -> GreetingResult,
) {

    private val executor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "hermes-sync").apply { isDaemon = true } }

    private val mutableStatus = MutableStateFlow(SyncStatus())

    /** Current synchronization status, for the UI (§8). */
    val status: StateFlow<SyncStatus> = mutableStatus.asStateFlow()

    private val mutableWifi = MutableStateFlow(WifiIndicator.NEUTRAL)

    /** Wi-Fi indicator state (§8), probed on the §4 trigger events. */
    val wifi: StateFlow<WifiIndicator> = mutableWifi.asStateFlow()

    private val mutableWifiDetail = MutableStateFlow<GreetingResult?>(null)

    /** The greeting result behind the current Wi-Fi indicator, for the detail popup. */
    val wifiDetail: StateFlow<GreetingResult?> = mutableWifiDetail.asStateFlow()

    init {
        engine.phaseListener = { phase ->
            mutableStatus.value = mutableStatus.value.copy(phase = phase)
        }
    }

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
            // §8: the probe runs on the trigger events of §4 and is
            // independent of the sync cooldown.
            probeWifi()
            val outcome = try {
                engine.attempt()
            } catch (t: Throwable) {
                // The engine converts Exception into Failure itself; this is
                // a last resort so the status can never be left stuck.
                SyncOutcome.Failure("Unexpected failure: ${t.javaClass.simpleName}: ${t.message ?: ""}")
            }
            mutableStatus.value = SyncStatus(
                running = false,
                phase = null,
                // A skipped attempt is no attempt (§4): the indicator keeps
                // its previous real outcome (§8 has no skipped state).
                lastOutcome = if (outcome is SyncOutcome.Skipped) {
                    mutableStatus.value.lastOutcome
                } else {
                    outcome
                },
            )
            // One concise line per attempt; never any API key material (§3).
            Log.i(TAG, "sync outcome: $outcome")
        }
    }

    /**
     * §8 Wi-Fi indicator: red without sending anything when no validated
     * unmetered network exists; otherwise the greeting decides green/amber.
     * Server reachability is never synthesized from synchronization requests.
     */
    private fun probeWifi() {
        if (!networkGate()) {
            mutableWifi.value = WifiIndicator.RED
            return
        }
        val result = greeting()
        mutableWifi.value = when (result) {
            GreetingResult.SUCCESS -> WifiIndicator.GREEN
            else -> WifiIndicator.AMBER
        }
        mutableWifiDetail.value = result
    }

    private companion object {
        const val TAG = "hermes-sync"
    }
}
