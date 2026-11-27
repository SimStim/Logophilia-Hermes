package eu.logophilia.hermes.api

import okhttp3.MediaType.Companion.toMediaType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests of the §3 rule: a /download response whose Content-Type is
 * not application/octet-stream is a failed download (the current server
 * answers a missing or invalid fileName with HTTP 200 and a JSON body).
 */
class ContentSniffingTest {

    @Test
    fun `octet-stream is accepted, with or without parameters`() {
        assertTrue(isOctetStream("application/octet-stream".toMediaType()))
        assertTrue(isOctetStream("application/octet-stream; charset=binary".toMediaType()))
    }

    @Test
    fun `other content types are rejected`() {
        assertFalse(isOctetStream("application/json; charset=UTF-8".toMediaType()))
        assertFalse(isOctetStream("text/html".toMediaType()))
    }

    @Test
    fun `a missing Content-Type is rejected`() {
        assertFalse(isOctetStream(null))
    }
}
