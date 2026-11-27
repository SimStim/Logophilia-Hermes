package eu.logophilia.hermes.api

import eu.logophilia.hermes.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Failures of the API client, as a sealed family so the synchronization
 * engine can handle every case exhaustively (SPECIFICATION.md §9).
 */
sealed class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** 401/403: a plain authentication failure — report and stop, no retry (§3). */
    class Auth(val httpCode: Int, serverMessage: String) : ApiException(serverMessage)

    /** Any other unsuccessful HTTP status. */
    class Server(val httpCode: Int, serverMessage: String) : ApiException(serverMessage)

    /** The request never completed: DNS, connect, timeout, interrupted transfer. */
    class Transport(cause: Throwable) : ApiException(
        "Network request failed (${cause.javaClass.simpleName})", cause,
    )

    /** A response that is not a downloadable file (§3 Content-Type sniffing). */
    class Download(message: String) : ApiException(message)
}

/**
 * How the server answered the greeting probe GET / (SPECIFICATION.md §8).
 */
enum class GreetingResult { SUCCESS, AUTH_REJECTED, SERVER_ERROR, UNREACHABLE }

/** A file streamed to disk together with the SHA-256 computed while streaming. */
data class DownloadedFile(val file: File, val sha256: String, val size: Long)

/**
 * §3 Content-Type sniffing: a /download response whose Content-Type is not
 * application/octet-stream is a failed download — the current server answers
 * a missing or invalid fileName with HTTP 200 and a JSON error body.
 */
internal fun isOctetStream(mediaType: MediaType?): Boolean =
    mediaType != null && mediaType.type == "application" && mediaType.subtype == "octet-stream"

/**
 * §3 Client marker: the identity headers of this application, added to every
 * request by a single OkHttp interceptor. `X-Client` carries the exact value
 * the API exempts from its browser CORS/Referer gate; `X-Client-Version` is
 * informational (ignored by the server today). The marker replaces the former
 * development-only spoofed Referer — the client declares what it is instead
 * of impersonating the website, so no Origin, Referer, or browser-like
 * User-Agent is ever faked. A missing or misspelled marker reverts to the
 * old 403 (§3 Authentication).
 */
internal fun Request.withClientMarkers(version: String): Request = newBuilder()
    .header("X-Client", LogophiliaApi.CLIENT_MARKER)
    .header("X-Client-Version", version)
    .build()

/** The only two fields the app reads from a greeting body (§3, §3.5). */
internal data class GreetingFields(val status: String?, val renewedKey: String?)

/**
 * §3/§3.5 greeting-body parsing: the `status` field classifies the probe
 * result, and the optional `usha` property carries the API's current key for
 * a stale-key holder (the greeting-only renewal channel). No other property
 * is read. An unreadable or non-JSON body yields both fields null; a `usha`
 * that is JSON null, not a string, or blank counts as absent so it can never
 * blank out the stored key.
 */
internal fun parseGreeting(body: String): GreetingFields = try {
    val fields = Json.parseToJsonElement(body) as? JsonObject
    GreetingFields(
        status = (fields?.get("status") as? JsonPrimitive)?.content,
        renewedKey = (fields?.get("usha") as? JsonPrimitive)
            ?.takeUnless { it is JsonNull }
            ?.content
            ?.takeIf { it.isNotBlank() },
    )
} catch (_: Exception) {
    GreetingFields(null, null)
}

/**
 * The Logophilia API client (SPECIFICATION.md §3).
 *
 * Blocking methods; never call them on the Android main thread. Every request
 * carries the client marker (§3 Client marker) and the API key currently in
 * force (§3 Authentication): the persisted key from [keyStore] when one was
 * stored by an earlier renewal, else the compiled-in [apiKey]. API keys are
 * never logged or exposed in diagnostic messages.
 */
class LogophiliaApi(
    apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** Persists greeting-renewed keys immediately (§3.5); optional for tests. */
    private val keyStore: ApiKeyStore? = null,
) : SyncClient {

    /**
     * The key in use (§3.5). Atomic because a greeting probe may renew the
     * key while a synchronization attempt reads it for its next request.
     */
    private val currentKey =
        AtomicReference(keyStore?.load()?.takeIf { it.isNotBlank() } ?: apiKey)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Sends the API key currently in force with every request (§3).
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("X-Api-key", currentKey.get())
                    .build(),
            )
        }
        // Declares the client on every request (§3 Client marker); this
        // replaced the former development-only spoofed Referer.
        .addInterceptor { chain ->
            chain.proceed(chain.request().withClientMarkers(BuildConfig.VERSION_NAME))
        }
        .build()

    /**
     * GET / — the server-reachability probe for the Wi-Fi indicator (§8) and
     * the greeting-only key-renewal channel (§3.5): every greeting response
     * is checked for `usha`, and a stale-key holder rotates to the delivered
     * key immediately — persisted at once, in force for all later calls.
     */
    fun probeGreeting(): GreetingResult {
        val request = Request.Builder().url(url("/")).build()
        return try {
            execute(request).use { response ->
                val greeting = greetingOf(response)
                adoptRenewedKey(greeting.renewedKey)
                when {
                    !response.isSuccessful && (response.code == 401 || response.code == 403) ->
                        GreetingResult.AUTH_REJECTED
                    !response.isSuccessful -> GreetingResult.SERVER_ERROR
                    greeting.status == "success" -> GreetingResult.SUCCESS
                    else -> GreetingResult.SERVER_ERROR
                }
            }
        } catch (_: IOException) {
            GreetingResult.UNREACHABLE
        }
    }

    /**
     * GET /freeloot — retrieve and validate the content manifest
     * (§3; §5 Phase A). Local content is never touched on failure.
     *
     * @throws ApiException.Auth, ApiException.Server, ApiException.Transport
     *   or ManifestException, never an empty content list.
     */
    override fun fetchManifest(): List<ManifestEntry> {
        val request = Request.Builder().url(url("/freeloot")).build()
        execute(request).use { response ->
            checkHttp(response)
            val body = try {
                response.readCapped(MAX_JSON_BYTES)
            } catch (e: IOException) {
                throw ApiException.Transport(e)
            }
            return FreelootManifest.parse(body)
        }
    }

    /**
     * GET /download?fileName=… — stream one file into [destination], hashing
     * it on the way (§5 Phase C; the comparison against the manifest is the
     * caller's job). No HTTP Range support exists; interrupted downloads are
     * simply discarded and later retried in full.
     *
     * @throws ApiException.Download if the Content-Type is not
     *   application/octet-stream (a JSON error body arrives with HTTP 200).
     */
    override fun download(fileName: String, destination: File): DownloadedFile {
        val request = Request.Builder()
            .url(url("/download").newBuilder().addQueryParameter("fileName", fileName).build())
            .build()
        execute(request).use { response ->
            checkHttp(response)
            val body = response.body
            val mediaType = body.contentType()
            if (!isOctetStream(mediaType)) {
                throw ApiException.Download(
                    "Download of \"$fileName\" answered $mediaType instead of application/octet-stream",
                )
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            try {
                destination.parentFile?.mkdirs()
                FileOutputStream(destination).use { output ->
                    body.byteStream().use { input ->
                        val chunk = ByteArray(DOWNLOAD_CHUNK_BYTES)
                        while (true) {
                            val read = input.read(chunk)
                            if (read < 0) break
                            digest.update(chunk, 0, read)
                            size += read
                            output.write(chunk, 0, read)
                        }
                    }
                }
            } catch (e: IOException) {
                destination.delete() // never leave a partial file behind
                throw ApiException.Transport(e)
            }
            return DownloadedFile(destination, digest.hex(), size)
        }
    }

    /** Non-200 answers become typed failures (§3 Authentication and §9). */
    private fun checkHttp(response: Response) {
        if (response.isSuccessful) return
        val message = "HTTP ${response.code}${serverDetail(response)?.let { ": $it" } ?: ""}"
        when (response.code) {
            401, 403 -> throw ApiException.Auth(response.code, message)
            else -> throw ApiException.Server(response.code, message)
        }
    }

    private fun execute(request: Request): Response =
        try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw ApiException.Transport(e)
        }

    private fun url(path: String) = "$baseUrl$path".toHttpUrl()

    /** The parsed greeting body; both fields null when unreadable (§3.5). */
    private fun greetingOf(response: Response): GreetingFields = try {
        parseGreeting(response.readCapped(MAX_JSON_BYTES))
    } catch (_: Exception) {
        GreetingFields(null, null)
    }

    /**
     * §3.5: adopt the key the greeting delivered — in force from now on and
     * persisted immediately, so the rotation survives a restart. No retry,
     * no loop: a refused attempt simply self-heals at the next greeting.
     */
    private fun adoptRenewedKey(key: String?) {
        if (key == null) return
        currentKey.set(key)
        keyStore?.store(key)
    }

    /** The server's error message from a JSON error body, if any. */
    private fun serverDetail(response: Response): String? = try {
        (Json.parseToJsonElement(response.readCapped(MAX_JSON_BYTES)) as? JsonObject)
            ?.get("message")
            ?.let { it as? JsonPrimitive }
            ?.content
    } catch (_: Exception) {
        null
    }

    /**
     * Reads the body as UTF-8 text, refusing responses larger than [maxBytes]
     * so a broken server cannot balloon memory.
     */
    private fun Response.readCapped(maxBytes: Long): String {
        val output = ByteArrayOutputStream(64 * 1024)
        body.byteStream().use { input ->
            val chunk = ByteArray(8 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                total += read
                if (total > maxBytes) throw IOException("Response body exceeds $maxBytes bytes")
                output.write(chunk, 0, read)
            }
        }
        return output.toString("UTF-8")
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.logophilia.eu"

        /**
         * The value of the X-Client header (§3 Client marker): the exact
         * string the API's CORS middleware matches against.
         */
        const val CLIENT_MARKER = "eu.logophilia.hermes"

        /** Greeting and manifest are small JSON documents. */
        private const val MAX_JSON_BYTES = 4L * 1024 * 1024

        private const val DOWNLOAD_CHUNK_BYTES = 64 * 1024
    }
}
