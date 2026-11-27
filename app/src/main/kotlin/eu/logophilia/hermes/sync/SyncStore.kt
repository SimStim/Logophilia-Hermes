package eu.logophilia.hermes.sync

/**
 * The persisted `lastSyncAttempt` timestamp (SPECIFICATION.md §2, §4) — the
 * only persisted synchronization state. It is written when an actual
 * synchronization attempt begins and survives application restarts, so the
 * five-minute cooldown also holds across restarts. Not even the outcome of
 * the last attempt is persisted; the only other persisted state in the app
 * is the greeting-renewed API key (§3.5, api/ApiKeyStore.kt).
 */
interface SyncStore {
    fun lastSyncAttemptMillis(): Long?
    fun writeLastSyncAttempt(millis: Long)
}
