package eu.logophilia.hermes.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * End-to-end check of the client against the real API
 * (SPECIFICATION.md §12: test against the real API wherever possible).
 *
 * Runs only when an API key is available — from the HERMES_API_KEY
 * environment variable or the git-ignored secrets.properties at the repo
 * root — and is skipped otherwise, so fresh checkouts and CI stay offline.
 */
class LiveApiTest {

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

    /** Records every persisted key without ever printing it (§3). */
    private class RecordingApiKeyStore : ApiKeyStore {
        val storedKeys = mutableListOf<String>()
        override fun load(): String? = null
        override fun store(key: String) {
            storedKeys.add(key)
        }
    }

    /**
     * A client that has greeted once, in the app's own greet-first order
     * (§4): a configured key that the server has retired was already renewed
     * through the greeting's "usha" property (§3.5) before any real endpoint
     * is called.
     */
    private fun greetedApi(): LogophiliaApi {
        val store = RecordingApiKeyStore()
        val api = LogophiliaApi(assumeKey(), keyStore = store)
        assertEquals(
            "the greeting must succeed before any other endpoint",
            GreetingResult.SUCCESS,
            api.probeGreeting(),
        )
        if (store.storedKeys.isNotEmpty()) {
            // A renewal happened: the configured key was retired server-side
            // and the current key was persisted above. Key material is never
            // printed (§3); update secrets.properties at your leisure.
            println("NOTE: the greeting renewed the API key (usha); the configured key is retired.")
        }
        return api
    }

    private fun assumeKey(): String {
        assumeTrue("no API key available; skipping live API test", apiKey != null)
        return apiKey!!
    }

    /** Expected failure helper; JUnit 4 without kotlin-test has no assertFailsWith. */
    private fun failure(block: () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("expected the call to fail")
        } catch (t: Throwable) {
            t
        }

    @Test
    fun `greeting probe succeeds`() {
        assertEquals(GreetingResult.SUCCESS, LogophiliaApi(assumeKey()).probeGreeting())
    }

    @Test
    fun `the greeting delivers the current key under usha and the app adopts it`() {
        val store = RecordingApiKeyStore()
        val api = LogophiliaApi(assumeKey(), keyStore = store)
        assertEquals(GreetingResult.SUCCESS, api.probeGreeting())
        // The manifest endpoint accepts only the current key: success proves
        // the app is using it — adopted from "usha" when the configured key
        // was retired, or already holding it otherwise.
        assertTrue(api.fetchManifest().isNotEmpty())
        // Whatever was persisted is a real key, never blank (§3.5).
        store.storedKeys.forEach { key -> assertTrue(key.isNotBlank()) }
    }

    @Test
    fun `manifest parses and validates`() {
        val entries = greetedApi().fetchManifest()
        // There is normally always free loot (TODO.md); with the current
        // server an empty list would even arrive as status "error" and be
        // rejected, so a validated manifest is non-empty today.
        assertTrue(entries.isNotEmpty())
        assertTrue(entries.all { it.name.isNotBlank() && it.size >= 0 && it.sha256.length == 64 })
    }

    @Test
    fun `wrong key is a plain authentication failure without retry`() {
        val failure = failure { LogophiliaApi("definitely-not-a-valid-key").fetchManifest() }
        assertTrue("expected ApiException.Auth, got $failure", failure is ApiException.Auth)
        assertEquals(403, (failure as ApiException.Auth).httpCode)
    }

    @Test
    fun `downloaded file matches the manifest checksum`() {
        val entries = greetedApi().fetchManifest()
        val smallest = entries.minBy { it.size }
        assumeTrue("smallest file too large for a smoke test", smallest.size <= 100L * 1024 * 1024)
        val target = File(temp.root, "download.bin")
        val downloaded = greetedApi().download(smallest.name, target)
        assertEquals(smallest.size, downloaded.size)
        assertEquals(smallest.sha256, downloaded.sha256)
    }

    @Test
    fun `bogus fileName is a failed download and writes nothing`() {
        val target = File(temp.root, "bogus.bin")
        val failure = failure { greetedApi().download("no/such/file.zip", target) }
        // The fixed API answers a real 404 (ApiException.Server); the former
        // 200 + JSON error body is caught by the Content-Type sniff
        // (ApiException.Download). Either way: a typed failure, and the JSON
        // error body was never written to disk (§3, §5 Phase C).
        assertTrue(
            "expected a typed download failure, got $failure",
            failure is ApiException.Server || failure is ApiException.Download,
        )
        if (failure is ApiException.Server) assertEquals(404, failure.httpCode)
        assertFalse(target.exists())
    }
}
