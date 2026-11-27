package eu.logophilia.hermes.sync

/**
 * The single persisted state of the whole application: the `lastSyncAttempt`
 * timestamp (SPECIFICATION.md §2, §4). It is written when an actual
 * synchronization attempt begins and survives application restarts, so the
 * five-minute cooldown also holds across restarts. No other state is
 * persisted — not even the outcome of the last attempt.
 */
interface SyncStore {
    fun lastSyncAttemptMillis(): Long?
    fun writeLastSyncAttempt(millis: Long)
}
