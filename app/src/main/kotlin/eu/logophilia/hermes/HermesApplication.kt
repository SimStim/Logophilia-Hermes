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

    val syncCoordinator: SyncCoordinator by lazy {
        SyncCoordinator(
            SyncEngine(
                client = LogophiliaApi(BuildConfig.API_KEY),
                layout = ContentLayout(filesDir),
                store = SharedPreferencesSyncStore(this),
                networkGate = ConnectivityManagerGate(this),
            ),
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
