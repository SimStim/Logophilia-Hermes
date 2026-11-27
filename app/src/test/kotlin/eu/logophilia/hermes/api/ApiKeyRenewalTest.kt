package eu.logophilia.hermes.api

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Offline end-to-end tests of the §3.5 greeting-only key renewal: a local
 * HTTP server stands in for the API, so the whole path runs without network
 * access or real key material — the greeting response, the "usha" adoption,
 * the immediate persistence, and the key that subsequent requests put on the
 * wire. Key values are fakes; nothing is ever printed (§3).
 */
class ApiKeyRenewalTest {

    /** What the fake API answers on the greeting route; set per test. */
    private var greetingBody = "{}"
    private var greetingStatus = 200

    /** What it answers on every other route; set per test. */
    private var endpointBody = MANIFEST_OK
    private var endpointStatus = 200

    /** Every request's X-Api-key/X-Client wire values, in arrival order. */
    private val seenApiKeys = mutableListOf<Pair<String?, String?>>() // name to value
    private val seenClientMarkers = mutableListOf<String?>()

    private lateinit var server: HttpServer
    private var port = 0

    /** Records every persisted key without ever printing it (§3). */
    private class RecordingApiKeyStore(private val initial: String? = null) : ApiKeyStore {
        val storedKeys = mutableListOf<String>()
        override fun load(): String? = initial
        override fun store(key: String) {
            storedKeys.add(key)
        }
    }

    @Before
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val headers = exchange.requestHeaders
            fun header(name: String): Pair<String?, String?> = headers.entries
                .firstOrNull { it.key.equals(name, ignoreCase = true) }
                ?.let { it.key to it.value.firstOrNull() }
                ?: (null to null)
            synchronized(seenApiKeys) {
                seenApiKeys.add(header("X-Api-key"))
                seenClientMarkers.add(header("X-Client").second)
            }
            val greeting = exchange.requestURI.path == "/"
            val body = (if (greeting) greetingBody else endpointBody).toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=UTF-8")
            exchange.sendResponseHeaders(if (greeting) greetingStatus else endpointStatus, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun stopServer() {
        server.stop(0)
    }

    private fun api(keyStore: ApiKeyStore? = null, initialKey: String = COMPILED_IN_KEY) =
        LogophiliaApi(initialKey, baseUrl = "http://127.0.0.1:$port", keyStore = keyStore)

    /** Expected-failure helper; JUnit 4 without kotlin-test has no assertFailsWith. */
    private fun failure(block: () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("expected the call to fail")
        } catch (t: Throwable) {
            t
        }

    @Test
    fun `a greeting usha is persisted immediately and used by all later calls`() {
        greetingBody = """{"message":"Welcome","status":"success","usha":"renewed-key"}"""
        val store = RecordingApiKeyStore()
        val client = api(store)

        assertEquals(GreetingResult.SUCCESS, client.probeGreeting())
        assertEquals(listOf("renewed-key"), store.storedKeys)

        // The manifest endpoint only accepts the current key — success proves
        // the renewed key is in force.
        client.fetchManifest()
        // On the wire: the greeting still used the initial key, everything
        // afterwards the renewed one, both under the unchanged X-Api-key
        // header name and always with the exact client marker.
        assertEquals(COMPILED_IN_KEY, seenApiKeys[0].second)
        assertEquals("renewed-key", seenApiKeys[1].second)
        // The header name is unchanged (the local JDK server reports names
        // title-cased; the exact written casing is pinned in ClientMarkerTest).
        assertTrue(seenApiKeys.all { it.first.equals("X-Api-key", ignoreCase = true) })
        assertEquals(listOf(LogophiliaApi.CLIENT_MARKER, LogophiliaApi.CLIENT_MARKER), seenClientMarkers)
    }

    @Test
    fun `a greeting without usha renews nothing`() {
        greetingBody = """{"message":"Welcome","status":"success"}"""
        val store = RecordingApiKeyStore()
        val client = api(store)

        assertEquals(GreetingResult.SUCCESS, client.probeGreeting())
        assertTrue(store.storedKeys.isEmpty())
        client.fetchManifest()
        assertEquals(COMPILED_IN_KEY, seenApiKeys[1].second)
    }

    @Test
    fun `every greeting response is checked, even a refused one`() {
        greetingBody = """{"status":"error","message":"FORBIDDEN.","usha":"renewed-key"}"""
        greetingStatus = 403
        endpointBody = """{"status":"error","message":"FORBIDDEN."}"""
        endpointStatus = 403
        val store = RecordingApiKeyStore()
        val client = api(store)

        assertEquals(GreetingResult.AUTH_REJECTED, client.probeGreeting())
        assertEquals(listOf("renewed-key"), store.storedKeys)
        val rejected = failure { client.fetchManifest() }
        assertTrue("expected ApiException.Auth, got $rejected", rejected is ApiException.Auth)
        // Even after the refused greeting, the renewed key is what travels.
        assertEquals("renewed-key", seenApiKeys[1].second)
    }

    @Test
    fun `a blank or unreadable greeting renews nothing`() {
        greetingBody = """{"status":"success","usha":""}"""
        val store = RecordingApiKeyStore()
        val client = api(store)
        assertEquals(GreetingResult.SUCCESS, client.probeGreeting())
        assertTrue(store.storedKeys.isEmpty())

        greetingBody = "not json at all"
        val silentStore = RecordingApiKeyStore()
        val second = api(silentStore)
        assertEquals(GreetingResult.SERVER_ERROR, second.probeGreeting())
        assertTrue(silentStore.storedKeys.isEmpty())
    }

    @Test
    fun `a previously persisted key takes precedence over the compiled-in key`() {
        greetingBody = """{"message":"Welcome","status":"success"}"""
        val client = api(RecordingApiKeyStore(initial = "persisted-key"))
        client.probeGreeting()
        client.fetchManifest()
        assertEquals("persisted-key", seenApiKeys[0].second)
        assertEquals("persisted-key", seenApiKeys[1].second)
    }

    private companion object {
        const val COMPILED_IN_KEY = "compiled-in-key"

        /** A manifest the strict §3 validator accepts. */
        val MANIFEST_OK =
            """{"message":"Here is your free loot!",""" +
                """"freeloot":[{"name":"a.zip","size":1,"sha256":"${"a".repeat(64)}"}],""" +
                """"status":"success"}"""
    }
}
