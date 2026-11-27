package eu.logophilia.hermes.api

import android.content.Context

/**
 * The persisted, currently valid API key (SPECIFICATION.md §3.5). The
 * compiled-in key is only the starting point: whenever a greeting response
 * carries the "usha" property, [LogophiliaApi] overwrites the stored key
 * with that value and persists it immediately, so a server-side rotation
 * survives application restarts without an app release. The store holds a
 * secret: it lives in the app's private storage only, and the key is never
 * logged or exposed in diagnostic messages (§3).
 */
interface ApiKeyStore {

    /** The stored key, or null while no renewed key has been stored yet. */
    fun load(): String?

    /** Overwrites the stored key; called immediately on every renewal. */
    fun store(key: String)
}

/** The [ApiKeyStore] in a private SharedPreferences file ("hermes_api"). */
class SharedPreferencesApiKeyStore(context: Context) : ApiKeyStore {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): String? =
        prefs.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    override fun store(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    private companion object {
        const val PREFS_NAME = "hermes_api"
        const val KEY_API_KEY = "apiKey"
    }
}
