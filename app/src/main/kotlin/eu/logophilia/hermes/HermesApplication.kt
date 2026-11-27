package eu.logophilia.hermes

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import eu.logophilia.hermes.api.LogophiliaApi
import eu.logophilia.hermes.sync.ConnectivityManagerGate
import eu.logophilia.hermes.sync.ContentLayout
import eu.logophilia.hermes.sync.SharedPreferencesSyncStore
import eu.logophilia.hermes.sync.SyncCoordinator
import eu.logophilia.hermes.sync.SyncEngine

/**
 * Owns the application-wide synchronization machinery (SPECIFICATION.md §4):
 * the API client, the sync engine over the private content root, and the
 * coordinator that turns triggers into attempts.
 */
class HermesApplication : Application() {

    /** The private content root, shared by the engine and the UI (§6, §8). */
    val contentLayout: ContentLayout by lazy { ContentLayout(filesDir) }

    val syncCoordinator: SyncCoordinator by lazy {
        val api = LogophiliaApi(BuildConfig.API_KEY)
        val gate = ConnectivityManagerGate(this)
        SyncCoordinator(
            engine = SyncEngine(
                client = api,
                layout = contentLayout,
                store = SharedPreferencesSyncStore(this),
                networkGate = gate,
            ),
            networkGate = gate,
            greeting = { api.probeGreeting() },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // §4 trigger: a qualifying network becomes available while the
        // application is running. The engine's gate and cooldown decide
        // whether an attempt actually happens.
        getSystemService(ConnectivityManager::class.java)
            ?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    syncCoordinator.onNetworkAvailable()
                }
            })
    }
}
