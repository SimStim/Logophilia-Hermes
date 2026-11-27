package eu.logophilia.hermes.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Offline tests of the §3/§3.5 greeting-body parsing: the status field
 * classifies the probe, the optional "usha" property is the only renewal
 * channel, and nothing else in the body is read. Unreadable input must
 * yield nulls, never a key.
 */
class GreetingParsingTest {

    @Test
    fun `status and usha are the only fields read`() {
        val greeting = parseGreeting(
            """{"message":"Welcome to Logophilia API.","status":"success","usha":"k2","extra":42}""",
        )
        assertEquals("success", greeting.status)
        assertEquals("k2", greeting.renewedKey)
    }

    @Test
    fun `a greeting without usha renews nothing`() {
        val greeting =
            parseGreeting("""{"message":"Welcome to Logophilia API.","status":"success"}""")
        assertEquals("success", greeting.status)
        assertNull(greeting.renewedKey)
    }

    @Test
    fun `an error status is parsed the same way`() {
        val greeting =
            parseGreeting("""{"status":"error","message":"UNAUTHORIZED: API KEY IS MISSING."}""")
        assertEquals("error", greeting.status)
        assertNull(greeting.renewedKey)
    }

    @Test
    fun `a blank usha counts as absent`() {
        assertNull(parseGreeting("""{"status":"success","usha":""}""").renewedKey)
        assertNull(parseGreeting("""{"status":"success","usha":"   "}""").renewedKey)
    }

    @Test
    fun `a JSON-null usha counts as absent`() {
        assertNull(parseGreeting("""{"status":"success","usha":null}""").renewedKey)
    }

    @Test
    fun `a non-string usha counts as absent`() {
        assertNull(parseGreeting("""{"status":"success","usha":{"key":"k2"}}""").renewedKey)
        assertNull(parseGreeting("""{"status":"success","usha":["k2"]}""").renewedKey)
    }

    @Test
    fun `a malformed body yields nulls, never a key`() {
        val greeting = parseGreeting("not json at all")
        assertNull(greeting.status)
        assertNull(greeting.renewedKey)
    }

    @Test
    fun `an empty body yields nulls`() {
        val greeting = parseGreeting("")
        assertNull(greeting.status)
        assertNull(greeting.renewedKey)
    }
}
