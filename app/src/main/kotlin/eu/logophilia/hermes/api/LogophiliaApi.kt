package eu.logophilia.hermes.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
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
 * The Logophilia API client (SPECIFICATION.md §3).
 *
 * Blocking methods; never call them on the Android main thread. Every request
 * carries the compiled-in API key (§3 Authentication). API keys are never
 * logged or exposed in diagnostic messages.
 */
class LogophiliaApi(
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : SyncClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Sends the API key with every request (§3 Authentication).
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("X-Api-key", apiKey)
                    .build(),
            )
        }
        .addInterceptor(DEVELOPMENT_ONLY_SPOOFED_REFERER)
        .build()

    /** GET / — the server-reachability probe for the Wi-Fi indicator (§8). */
    fun probeGreeting(): GreetingResult {
        val request = Request.Builder().url(url("/")).build()
        return try {
            execute(request).use { response ->
                when {
                    !response.isSuccessful && (response.code == 401 || response.code == 403) ->
                        GreetingResult.AUTH_REJECTED
                    !response.isSuccessful -> GreetingResult.SERVER_ERROR
                    statusFieldOf(response) == "success" -> GreetingResult.SUCCESS
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

    /** The "status" field of a small JSON body, or null if unreadable. */
    private fun statusFieldOf(response: Response): String? = try {
        Json.parseToJsonElement(response.readCapped(MAX_JSON_BYTES))
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("status")?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    /** The server's error message from a JSON error body, if any. */
    private fun serverDetail(response: Response): String? = try {
        Json.parseToJsonElement(response.readCapped(MAX_JSON_BYTES))
            .let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("message")?.jsonPrimitive?.content
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

        /** Greeting and manifest are small JSON documents. */
        private const val MAX_JSON_BYTES = 4L * 1024 * 1024

        private const val DOWNLOAD_CHUNK_BYTES = 64 * 1024

        /*
         * =====================================================================
         * DEVELOPMENT ONLY — SPOOFED REFERER. THE ONE PLACE THIS EXISTS.
         * =====================================================================
         * The API's CORS/Referer gate (§3) rejects any request without an
         * Origin unless it carries a Referer from logophilia.eu, and the app
         * sends neither by default. Until the API exempts an explicit client
         * marker (TODO.md, both repositories), development and testing send
         * the spoofed header below. DO NOT SHIP THE SPOOF: before public
         * launch, replace this single interceptor with the honest client
         * marker (SPECIFICATION.md §3, TODO.md).
         */
        private val DEVELOPMENT_ONLY_SPOOFED_REFERER = Interceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Referer", "https://logophilia.eu/")
                    .build(),
            )
        }
    }
}
