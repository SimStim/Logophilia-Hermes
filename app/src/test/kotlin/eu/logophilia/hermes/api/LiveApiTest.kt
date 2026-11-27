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

    private fun api() = LogophiliaApi(assumeKey())

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
        assertEquals(GreetingResult.SUCCESS, api().probeGreeting())
    }

    @Test
    fun `manifest parses and validates`() {
        val entries = api().fetchManifest()
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
        val entries = api().fetchManifest()
        val smallest = entries.minBy { it.size }
        assumeTrue("smallest file too large for a smoke test", smallest.size <= 100L * 1024 * 1024)
        val target = File(temp.root, "download.bin")
        val downloaded = api().download(smallest.name, target)
        assertEquals(smallest.size, downloaded.size)
        assertEquals(smallest.sha256, downloaded.sha256)
    }

    @Test
    fun `bogus fileName is a failed download despite HTTP 200`() {
        val target = File(temp.root, "bogus.bin")
        val failure = failure { api().download("no/such/file.zip", target) }
        assertTrue("expected ApiException.Download, got $failure", failure is ApiException.Download)
        // The JSON error body was sniffed before anything was written.
        assertFalse(target.exists())
    }
}
