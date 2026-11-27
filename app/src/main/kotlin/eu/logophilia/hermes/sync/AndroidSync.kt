package eu.logophilia.hermes.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

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
 * §4 Network gate: the active network must report NET_CAPABILITY_VALIDATED and
 * either NET_CAPABILITY_NOT_METERED or — API 30+ — the equally never-billing
 * NET_CAPABILITY_TEMPORARILY_NOT_METERED. Nothing is ever downloaded over a
 * metered connection. Because a "temporarily not metered" network may flip
 * back to metered mid-transfer, the gate is re-checked before every file
 * commit (§5 Phase C).
 * Accepted limitation (§4): a hotspot fed by someone else's mobile data still
 * appears unmetered; no finer client-side signal exists.
 */
class ConnectivityManagerGate(context: Context) : () -> Boolean {

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun invoke(): Boolean {
        val network = connectivity?.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED,
                    )
                )
    }
}
