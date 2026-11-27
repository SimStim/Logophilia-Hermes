package eu.logophilia.hermes.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * The persisted `lastSyncAttempt` timestamp in SharedPreferences — the only
 * persisted state in the whole application (SPECIFICATION.md §2, §4).
 */
class SharedPreferencesSyncStore(context: Context) : SyncStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun lastSyncAttemptMillis(): Long? =
        if (prefs.contains(KEY_LAST_SYNC_ATTEMPT)) {
            prefs.getLong(KEY_LAST_SYNC_ATTEMPT, 0L)
        } else {
            null
        }

    override fun writeLastSyncAttempt(millis: Long) {
        prefs.edit().putLong(KEY_LAST_SYNC_ATTEMPT, millis).apply()
    }

    private companion object {
        const val PREFS_NAME = "hermes_sync"
        const val KEY_LAST_SYNC_ATTEMPT = "lastSyncAttempt"
    }
}

/**
 * §4 Network gate: the active network must report NET_CAPABILITY_NOT_METERED
 * and NET_CAPABILITY_VALIDATED. Nothing is ever downloaded over a metered
 * connection. Accepted limitation (§4): a hotspot fed by someone else's
 * mobile data still appears unmetered; no finer client-side signal exists.
 */
class ConnectivityManagerGate(context: Context) : () -> Boolean {

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun invoke(): Boolean {
        val network = connectivity?.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
