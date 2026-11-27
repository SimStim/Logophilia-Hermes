package eu.logophilia.hermes.sync

import eu.logophilia.hermes.api.ApiException
import eu.logophilia.hermes.api.DownloadedFile
import eu.logophilia.hermes.api.ManifestEntry
import eu.logophilia.hermes.api.ManifestException
import eu.logophilia.hermes.api.SyncClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Offline tests of the synchronization engine (SPECIFICATION.md §4 and §5)
 * against a fake client, including the acceptance scenarios that concern
 * local state (§11).
 */
class SyncEngineTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var layout: ContentLayout
    private val store = FakeStore()
    private var now = 0L
    private val clock = { now }

    private fun bytes(vararg text: String) = text.joinToString("\n").toByteArray()

    private fun zipBytes(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------
    // Fake client
    // ------------------------------------------------------------------

    private class FakeEntry(val name: String, val content: ByteArray) {
        val sha256: String = MessageDigest.getInstance("SHA-256")
            .digest(content).joinToString("") { "%02x".format(it) }
    }

    private inner class FakeClient(vararg entries: FakeEntry) : SyncClient {
        var manifest = entries.toList()
        var manifestCalls = 0
        var downloads = mutableListOf<String>()
        var failDownloadsFor: Set<String> = emptySet()
        var corruptDownloadFor: String? = null
        var manifestFailure: Exception? = null
        var downloadStarted: java.util.concurrent.CountDownLatch? = null
        var releaseDownload: java.util.concurrent.CountDownLatch? = null

        override fun fetchManifest(): List<ManifestEntry> {
            manifestCalls++
            manifestFailure?.let { throw it }
            return manifest.map { ManifestEntry(it.name, it.content.size.toLong(), it.sha256) }
        }

        override fun download(fileName: String, destination: File): DownloadedFile {
            downloads += fileName
            downloadStarted?.countDown()
            releaseDownload?.await()
            if (fileName in failDownloadsFor) {
                throw ApiException.Transport(IOException("connection reset"))
            }
            val entry = manifest.first { it.name == fileName }
            val content =
                if (fileName == corruptDownloadFor) entry.content + "CORRUPTED".toByteArray()
                else entry.content
            destination.parentFile?.mkdirs()
            destination.writeBytes(content)
            return DownloadedFile(destination, sha256(content), content.size.toLong())
        }

        fun resetCounters() {
            manifestCalls = 0
            downloads.clear()
        }
    }

    private class FakeStore : SyncStore {
        var last: Long? = null
        var writes = 0
        override fun lastSyncAttemptMillis(): Long? = last
        override fun writeLastSyncAttempt(millis: Long) {
            last = millis
            writes++
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun engine(client: FakeClient, gate: () -> Boolean = { true }) =
        SyncEngine(client, layout, store, gate, clock)

    private fun before(): ContentLayout {
        layout = ContentLayout(temp.root)
        layout.createDirectories()
        return layout
    }

    private fun advancePastCooldown() {
        now += COOLDOWN_MILLIS + 1000
    }

    private fun plain(target: File) = target

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------

    @Test
    fun `fresh install downloads everything into its categories`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("book"))
        val track = FakeEntry("Track.opus", bytes("audio"))
        val notes = FakeEntry("Notes.txt", bytes("notes"))
        val binary = FakeEntry("Tool.bin", bytes("binary"))
        val ost = FakeEntry("The Pitch 2025 OST.zip", zipBytes("album/track.opus" to "ost-audio"))
        val bundle = FakeEntry("Bundle.zip", zipBytes("data/x.bin" to "data"))
        val client = FakeClient(book, track, notes, binary, ost, bundle)

        val outcome = engine(client).attempt()

        assertEquals(SyncOutcome.Success(added = 6, updated = 0, removed = 0), outcome)
        assertEquals("book", File(layout.books, "Book.epub").readText())
        assertEquals(book.sha256, File(layout.books, "Book.epub.hash").readText())
        assertEquals("audio", File(layout.music, "Track.opus").readText())
        assertEquals("notes", File(layout.documents, "Notes.txt").readText())
        assertEquals("binary", File(layout.other, "Tool.bin").readText())
        // OST archive extracted under its basename, marker in the root:
        assertEquals("ost-audio", File(layout.music, "The Pitch 2025 OST/album/track.opus").readText())
        assertEquals(ost.sha256, File(layout.music, "The Pitch 2025 OST/${ost.sha256}.hash").readText())
        // Non-OST archive lands in Other the same way:
        assertEquals("data", File(layout.other, "Bundle/data/x.bin").readText())
        assertEquals(bundle.sha256, File(layout.other, "Bundle/${bundle.sha256}.hash").readText())
        // No archive is ever retained, and Temp was discarded (§5 Phase E 4):
        assertTrue(temp.root.walkTopDown().none { it.isFile && it.name.endsWith(".zip") })
        assertTrue(layout.temp.isDirectory)
        assertTrue(layout.temp.listFiles().isNullOrEmpty())
    }

    @Test
    fun `unchanged content is not downloaded or re-extracted`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("book"))
        val ost = FakeEntry("The Pitch 2025 OST.zip", zipBytes("album/track.opus" to "ost-audio"))
        val client = FakeClient(book, ost)

        assertTrue(engine(client).attempt() is SyncOutcome.Success)
        advancePastCooldown()
        client.resetCounters()

        val second = engine(client).attempt()

        assertEquals(SyncOutcome.Success(added = 0, updated = 0, removed = 0), second)
        // Phase A always consults the manifest, but nothing changed, so
        // no download and no re-extraction happen (§11: 3, 23).
        assertEquals(1, client.manifestCalls)
        assertEquals(0, client.downloads.size)
    }

    @Test
    fun `changed content is re-downloaded and fully swapped`() {
        layout = before()
        val bookV1 = FakeEntry("Book.epub", bytes("book v1"))
        val ostV1 = FakeEntry("The Pitch 2025 OST.zip", zipBytes("album/track.opus" to "v1"))
        val client = FakeClient(bookV1, ostV1)
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        val bookV2 = FakeEntry("Book.epub", bytes("book v2"))
        val ostV2 = FakeEntry("The Pitch 2025 OST.zip", zipBytes("album/track2.opus" to "v2"))
        client.manifest = listOf(bookV2, ostV2)
        advancePastCooldown()

        val outcome = engine(client).attempt()

        assertEquals(SyncOutcome.Success(added = 0, updated = 2, removed = 0), outcome)
        assertEquals("book v2", File(layout.books, "Book.epub").readText())
        assertEquals(bookV2.sha256, File(layout.books, "Book.epub.hash").readText())
        // Full swap, never a merge: the old inner file is gone, the marker updated.
        assertFalse(File(layout.music, "The Pitch 2025 OST/album/track.opus").exists())
        assertEquals("v2", File(layout.music, "The Pitch 2025 OST/album/track2.opus").readText())
        assertEquals(ostV2.sha256, File(layout.music, "The Pitch 2025 OST/${ostV2.sha256}.hash").readText())
        assertFalse(File(layout.music, "The Pitch 2025 OST/${ostV1.sha256}.hash").exists())
    }

    @Test
    fun `a missing sidecar self-heals by hashing without a download`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("book"))
        val file = File(layout.books, "Book.epub").apply { writeBytes(book.content) }

        val outcome = engine(FakeClient(book)).attempt()

        assertEquals(SyncOutcome.Success(added = 0, updated = 0, removed = 0), outcome)
        assertEquals(book.sha256, File(file.parentFile, "Book.epub.hash").readText())
    }

    @Test
    fun `content with a missing sidecar is re-downloaded when its hash differs`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("actual book"))
        val file = File(layout.books, "Book.epub").apply { writeBytes("stale content".toByteArray()) }

        val outcome = engine(FakeClient(book)).attempt()

        assertEquals(SyncOutcome.Success(added = 0, updated = 1, removed = 0), outcome)
        assertEquals("actual book", file.readText())
        assertEquals(book.sha256, File(file.parentFile, "Book.epub.hash").readText())
    }

    @Test
    fun `a corrupted download fails the attempt and retains valid content`() {
        layout = before()
        val bookV1 = FakeEntry("Book.epub", bytes("book v1"))
        val client = FakeClient(bookV1)
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        val bookV2 = FakeEntry("Book.epub", bytes("book v2"))
        client.manifest = listOf(bookV2)
        client.corruptDownloadFor = "Book.epub"
        advancePastCooldown()

        val outcome = engine(client).attempt()

        assertTrue("expected Failure, got $outcome", outcome is SyncOutcome.Failure)
        assertEquals("book v1", File(layout.books, "Book.epub").readText())
        assertEquals(bookV1.sha256, File(layout.books, "Book.epub.hash").readText())
        assertTrue(layout.temp.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a download transport failure retains existing content`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("book"))
        val client = FakeClient(book)
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        val extra = FakeEntry("Extra.epub", bytes("extra"))
        client.manifest = listOf(book, extra)
        client.failDownloadsFor = setOf("Extra.epub")
        advancePastCooldown()

        val outcome = engine(client).attempt()

        assertTrue("expected Failure, got $outcome", outcome is SyncOutcome.Failure)
        assertEquals("book", File(layout.books, "Book.epub").readText())
        assertFalse(File(layout.books, "Extra.epub").exists())
    }

    @Test
    fun `an invalid manifest fails the attempt and preserves everything`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("book"))
        val client = FakeClient(book)
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        client.manifestFailure = ManifestException("corrupted body")
        advancePastCooldown()

        val outcome = engine(client).attempt()

        assertTrue("expected Failure, got $outcome", outcome is SyncOutcome.Failure)
        assertEquals("book", File(layout.books, "Book.epub").readText())
        // The timestamp was still written when the attempt began (§4).
        assertEquals(now, store.last)
    }

    @Test
    fun `files removed from the manifest are removed locally after success`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("book"))
        val track = FakeEntry("Track.opus", bytes("audio"))
        val client = FakeClient(book, track)
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        client.manifest = listOf(book)
        advancePastCooldown()

        val outcome = engine(client).attempt()

        assertEquals(SyncOutcome.Success(added = 0, updated = 0, removed = 1), outcome)
        assertFalse(File(layout.music, "Track.opus").exists())
        assertFalse(File(layout.music, "Track.opus.hash").exists())
        assertEquals("book", File(layout.books, "Book.epub").readText())
    }

    @Test
    fun `an empty successful manifest removes all content`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("book"))
        val ost = FakeEntry("The Pitch 2025 OST.zip", zipBytes("album/track.opus" to "audio"))
        val client = FakeClient(book, ost)
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        client.manifest = emptyList()
        advancePastCooldown()

        val outcome = engine(client).attempt()

        assertEquals(SyncOutcome.Success(added = 0, updated = 0, removed = 2), outcome)
        assertTrue(layout.books.listFiles().isNullOrEmpty())
        assertTrue(layout.music.listFiles().isNullOrEmpty())
    }

    @Test
    fun `no qualifying network skips without contacting the API`() {
        layout = before()
        val client = FakeClient(FakeEntry("Book.epub", bytes("book")))

        val outcome = engine(client, gate = { false }).attempt()

        assertEquals(SyncOutcome.Skipped.NoQualifyingNetwork, outcome)
        assertEquals(0, client.manifestCalls)
        assertEquals(0, client.downloads.size)
        assertNull(store.last)
    }

    @Test
    fun `the cooldown skips a new attempt within five minutes`() {
        layout = before()
        val client = FakeClient(FakeEntry("Book.epub", bytes("book")))
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        now = COOLDOWN_MILLIS - 1
        val outcome = engine(client).attempt()

        assertEquals(SyncOutcome.Skipped.CoolingDown(remainingMillis = 1), outcome)
        assertEquals(1, client.manifestCalls) // only the first attempt
        assertEquals(1, client.downloads.size)
    }

    @Test
    fun `a new attempt is permitted after the cooldown expires`() {
        layout = before()
        val client = FakeClient(FakeEntry("Book.epub", bytes("book")))
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        now = COOLDOWN_MILLIS + 1
        val outcome = engine(client).attempt()

        assertTrue("expected Success, got $outcome", outcome is SyncOutcome.Success)
        assertEquals(2, client.manifestCalls)
    }

    @Test
    fun `simultaneous attempts never run concurrently`() {
        layout = before()
        val client = FakeClient(FakeEntry("Book.epub", bytes("book")))
        client.downloadStarted = java.util.concurrent.CountDownLatch(1)
        client.releaseDownload = java.util.concurrent.CountDownLatch(1)
        val engine = engine(client)

        var first: SyncOutcome? = null
        val worker = Thread {
            first = engine.attempt()
        }.apply {
            start()
            // The worker is inside its download; the engine is "running".
            client.downloadStarted!!.await()
        }

        val second = engine.attempt()
        client.releaseDownload!!.countDown()
        worker.join(10_000)

        assertEquals(SyncOutcome.Skipped.AlreadyRunning, second)
        assertTrue("expected Success, got $first", first is SyncOutcome.Success)
    }

    @Test
    fun `a zip slip archive fails the attempt and preserves the previous version`() {
        layout = before()
        val ostV1 = FakeEntry("The Pitch 2025 OST.zip", zipBytes("album/track.opus" to "v1"))
        val client = FakeClient(ostV1)
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        // v2 archive contains an entry trying to escape the extraction dir.
        client.manifest = listOf(
            FakeEntry("The Pitch 2025 OST.zip", zipBytes("album/track.opus" to "v2", "../evil.txt" to "evil")),
        )
        advancePastCooldown()

        val outcome = engine(client).attempt()

        assertTrue("expected Failure, got $outcome", outcome is SyncOutcome.Failure)
        assertEquals("v1", File(layout.music, "The Pitch 2025 OST/album/track.opus").readText())
        assertEquals(ostV1.sha256, File(layout.music, "The Pitch 2025 OST/${ostV1.sha256}.hash").readText())
        assertFalse(File(temp.root, "evil.txt").exists())
        assertFalse(File(layout.music, "evil.txt").exists())
    }

    @Test
    fun `stray sidecars without their file are cleaned up`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("book"))
        val client = FakeClient(book)
        assertTrue(engine(client).attempt() is SyncOutcome.Success)

        // Simulate a stray sidecar left by an interrupted state.
        File(layout.books, "Orphan.epub.hash").writeText("stale")
        advancePastCooldown()

        val outcome = engine(client).attempt()

        assertEquals(SyncOutcome.Success(added = 0, updated = 0, removed = 0), outcome)
        assertFalse(File(layout.books, "Orphan.epub.hash").exists())
        assertTrue(File(layout.books, "Book.epub.hash").exists())
    }

    @Test
    fun `a manifest file may itself end in hash`() {
        layout = before()
        val hashFile = FakeEntry("release-notes.hash", bytes("deadbeef"))
        val client = FakeClient(hashFile)

        val outcome = engine(client).attempt()

        assertEquals(SyncOutcome.Success(added = 1, updated = 0, removed = 0), outcome)
        assertEquals("deadbeef", File(layout.other, "release-notes.hash").readText())
        assertEquals(hashFile.sha256, File(layout.other, "release-notes.hash.hash").readText())
    }

    @Test
    fun `phases progress from manifest request through commit`() {
        layout = before()
        val phases = mutableListOf<SyncPhase>()
        val engine = engine(FakeClient(FakeEntry("Book.epub", bytes("book"))))

        engine.phaseListener = { phases += it }
        val outcome = engine.attempt()

        assertEquals(SyncOutcome.Success(added = 1, updated = 0, removed = 0), outcome)
        assertEquals(
            listOf(SyncPhase.MANIFEST_REQUESTED, SyncPhase.TRANSFERRING, SyncPhase.COMMITTING),
            phases,
        )
    }

    @Test
    fun `no phases are signalled when the attempt is skipped`() {
        layout = before()
        val phases = mutableListOf<SyncPhase>()
        val engine = engine(FakeClient(FakeEntry("Book.epub", bytes("book"))), gate = { false })

        engine.phaseListener = { phases += it }
        val outcome = engine.attempt()

        assertEquals(SyncOutcome.Skipped.NoQualifyingNetwork, outcome)
        assertTrue(phases.isEmpty())
    }

    @Test
    fun `the network gate is re-checked before every file commit`() {
        layout = before()
        val book = FakeEntry("Book.epub", bytes("book"))
        val track = FakeEntry("Track.opus", bytes("audio"))
        val client = FakeClient(book, track)
        var gateCalls = 0
        // The gate flips back to metered once the first file is downloaded:
        // the re-check before the second file's commit must refuse it.
        val flipAfterFirstDownload: () -> Boolean = {
            gateCalls++
            if (client.downloads.contains("Book.epub")) false else true
        }

        val outcome = engine(client, flipAfterFirstDownload).attempt()

        assertTrue("expected Failure, got $outcome", outcome is SyncOutcome.Failure)
        assertEquals(NETWORK_GATE_LOST_MESSAGE, (outcome as SyncOutcome.Failure).message)
        // preflight + one re-check before each of the two files' commits:
        assertEquals(3, gateCalls)
        // The second file never reached the network (§5 Phase C 1):
        assertEquals(listOf("Book.epub"), client.downloads)
        // Nothing was committed, nothing deleted, Temp discarded (§4, §5):
        assertTrue(File(layout.books, "Book.epub").exists().not())
        assertTrue(layout.temp.listFiles().isNullOrEmpty())
    }
}
