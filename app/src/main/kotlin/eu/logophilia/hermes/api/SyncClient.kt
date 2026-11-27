package eu.logophilia.hermes.api

import java.io.File

/**
 * The slice of the API client that the synchronization engine uses (§5), as
 * an interface so the engine can be exercised offline against a fake and
 * live against [LogophiliaApi] alike.
 */
interface SyncClient {
    /** §5 Phase A: retrieve and validate the content manifest. */
    fun fetchManifest(): List<ManifestEntry>

    /** §5 Phase C: stream one file into [destination], hashed on the way. */
    fun download(fileName: String, destination: File): DownloadedFile
}
