package eu.logophilia.hermes.sync

import eu.logophilia.hermes.api.ApiException
import eu.logophilia.hermes.api.DownloadedFile
import eu.logophilia.hermes.api.LogophiliaApi
import eu.logophilia.hermes.api.ManifestEntry
import eu.logophilia.hermes.api.SyncClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end synchronization against the real API
 * (SPECIFICATION.md §12: test against the real API wherever possible).
 *
 * Runs only when an API key is available — from the HERMES_API_KEY
 * environment variable or the git-ignored secrets.properties at the repo
 * root — and is skipped otherwise, so fresh checkouts and CI stay offline.
 *
 * The manifest is filtered to keep test traffic modest: by default only the
 * entries up to [SMALL_FILE_CAP_BYTES] (~15 MB in total today) are
 * synchronized. Set HERMES_LIVE_SYNC=full to also download the smallest OST
 * archive (~55 MB) and exercise the real extraction path.
 */
class SyncLiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val apiKey: String? by lazy {
        val fromEnvironment = System.getenv("HERMES_API_KEY")
        if (!fromEnvironment.isNullOrBlank()) return@lazy fromEnvironment
        val secrets = listOf("../secrets.properties", "secrets.properties")
            .map(::File)
            .firstOrNull { it.isFile } ?: return@lazy null
        val line = secrets.useLines { lines ->
            lines.firstOrNull { it.startsWith("API_KEY=") }
        } ?: return@lazy null
        line.substringAfter('=', "").trim().ifBlank { null }
    }

    private fun assumeKey(): String {
        assumeTrue("no API key available; skipping live sync test", apiKey != null)
        return apiKey!!
    }

    /**
     * Serves the real manifest filtered to a size budget, plus optionally the
     * smallest archive; downloads always come from the real API.
     */
    private class SelectiveApi(
        private val real: LogophiliaApi,
        private val maxBytes: Long,
        private val includeSmallestArchive: Boolean = false,
    ) : SyncClient {
        var downloads = 0
        var lastManifest: List<ManifestEntry> = emptyList()

        override fun fetchManifest(): List<ManifestEntry> {
            val manifest = real.fetchManifest()
            val smallestArchive = manifest
                .filter { it.name.endsWith(".zip") }
                .minByOrNull { it.size }
            lastManifest = manifest.filter { entry ->
                entry.size <= maxBytes ||
                    (includeSmallestArchive && entry == smallestArchive)
            }
            return lastManifest
        }

        override fun download(fileName: String, destination: File): DownloadedFile {
            downloads++
            return real.download(fileName, destination)
        }
    }

    /** Real manifest, but every download fails — for the transactional failure path. */
    private class RefusingDownloads(private val real: LogophiliaApi) : SyncClient {
        override fun fetchManifest() = real.fetchManifest()

        override fun download(fileName: String, destination: File): DownloadedFile =
            throw ApiException.Download("smoke test refuses to download \"$fileName\"")
    }

    private fun engine(client: SyncClient, root: File = temp.root): Pair<SyncEngine, ContentLayout> {
        val layout = ContentLayout(root)
        val engine = SyncEngine(
            client = client,
            layout = layout,
            store = MemoryStore(),
            networkGate = { true },
        )
        return engine to layout
    }

    @Test
    fun `synchronizes real content end to end`() {
        val api = SelectiveApi(LogophiliaApi(assumeKey()), maxBytes = SMALL_FILE_CAP_BYTES)
        val (engine, layout) = engine(api)

        val outcome = engine.attempt()

        assertTrue("expected Success, got $outcome", outcome is SyncOutcome.Success)
        assertTrue("expected several entries, got ${api.lastManifest.size}", api.lastManifest.size > 1)
        assertEquals(api.lastManifest.size, api.downloads)
        // Every synchronized plain file sits in a category directory with a
        // sidecar written from the manifest value; no archive is retained:
        for (entry in api.lastManifest) {
            val placement = ContentClassifier.classify(entry.name)
            val file = when (placement) {
                is Placement.Plain -> File(layout.dirFor(placement.category), entry.name)
                is Placement.Archive ->
                    error("no archive expected in the small-file budget: ${entry.name}")
            }
            assertTrue("missing $file", file.isFile)
            assertEquals(entry.size, file.length())
            assertEquals(entry.sha256, layout.sidecarOf(file).readText())
        }
        // Temp was discarded (§5 Phase E 4):
        assertTrue(layout.temp.listFiles().isNullOrEmpty())

        // A second attempt over unchanged content downloads nothing (§11: 3).
        // The cooldown is passed with a fresh engine and store:
        val (secondEngine, _) = engine(api, root = temp.root)
        val second = secondEngine.attempt()
        assertEquals(SyncOutcome.Success(added = 0, updated = 0, removed = 0), second)
        assertEquals(api.lastManifest.size, api.downloads)
    }

    @Test
    fun `full run extracts a real OST archive when opted in`() {
        assumeTrue("opt-in with HERMES_LIVE_SYNC=full", System.getenv("HERMES_LIVE_SYNC") == "full")
        val api = SelectiveApi(
            LogophiliaApi(assumeKey()),
            maxBytes = SMALL_FILE_CAP_BYTES,
            includeSmallestArchive = true,
        )
        val (engine, layout) = engine(api)

        val outcome = engine.attempt()

        assertTrue("expected Success, got $outcome", outcome is SyncOutcome.Success)
        val archiveEntry = api.lastManifest.first { it.name.endsWith(".zip") }
        val archiveDir = File(layout.dirFor(Category.Music), archiveEntry.name.removeSuffix(".zip"))
        assertTrue(archiveDir.isDirectory)
        // The archive is replaced by the marker named after its checksum:
        val marker = File(archiveDir, "${archiveEntry.sha256}.hash")
        assertTrue("marker $marker missing", marker.isFile)
        assertEquals(archiveEntry.sha256, marker.readText())
        // Extracted content exists next to it, and no .zip remains anywhere:
        assertTrue(archiveDir.listFiles()!!.any { it.name != marker.name })
        assertTrue(temp.root.walkTopDown().none { it.isFile && it.name.endsWith(".zip") })
    }

    @Test
    fun `a failed required download preserves the empty library`() {
        val api = RefusingDownloads(LogophiliaApi(assumeKey()))
        val (engine, layout) = engine(api)

        val outcome = engine.attempt()

        assertTrue("expected Failure, got $outcome", outcome is SyncOutcome.Failure)
        // Nothing was committed; the library stays empty (§11: 5, 10):
        assertTrue(layout.categoryDirectories.all { it.listFiles().isNullOrEmpty() })
        // The timestamp was written when the attempt began (§4):
        assertNotNull(engine.lastSyncAttemptMillis())
        // Temp was discarded despite the failure (§4 Temp discipline):
        assertTrue(layout.temp.listFiles().isNullOrEmpty())
    }

    private class MemoryStore : SyncStore {
        private var last: Long? = null
        override fun lastSyncAttemptMillis(): Long? = last
        override fun writeLastSyncAttempt(millis: Long) {
            last = millis
        }
    }

    private companion object {
        /** The smallest real entries (epub/pdf) together are ~15 MB today. */
        const val SMALL_FILE_CAP_BYTES = 3L * 1024 * 1024
    }
}
