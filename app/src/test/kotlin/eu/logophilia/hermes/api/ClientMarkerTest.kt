package eu.logophilia.hermes.api

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests of the §3 client marker: every request carries the exact
 * X-Client value the API exempts from its CORS/Referer gate plus the
 * informational X-Client-Version, and no browser-spoofing header is ever
 * added. The live counterpart runs in LiveApiTest against the real API.
 */
class ClientMarkerTest {

    private fun marked(version: String = "1.2.3"): Request =
        Request.Builder().url(LogophiliaApi.DEFAULT_BASE_URL).build()
            .withClientMarkers(version)

    @Test
    fun `marker and version carry the exact expected values`() {
        assertEquals("eu.logophilia.hermes", marked().header("X-Client"))
        assertEquals("1.2.3", marked().header("X-Client-Version"))
    }

    @Test
    fun `the marker constant is the exact string the API matches`() {
        assertEquals("eu.logophilia.hermes", LogophiliaApi.CLIENT_MARKER)
    }

    @Test
    fun `header names keep their written casing`() {
        val headers = marked().headers
        val names = (0 until headers.size).map { headers.name(it) }
        assertTrue("X-Client" in names)
        assertTrue("X-Client-Version" in names)
    }

    @Test
    fun `no browser-spoofing headers are sent`() {
        assertNull(marked().header("Origin"))
        assertNull(marked().header("Referer"))
        // The app never sets a User-Agent; OkHttp's own honest default (if
        // any) is added on the wire, never a browser-like fake.
        assertNull(marked().header("User-Agent"))
    }
}
