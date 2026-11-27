package eu.logophilia.hermes.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * One content file as listed by the /freeloot manifest (SPECIFICATION.md §3):
 * a flat list of files — no subdirectories, categories, or dates.
 */
data class ManifestEntry(
    val name: String,
    val size: Long,
    val sha256: String,
)

/**
 * Raised when a /freeloot response is malformed, incomplete, or corrupted
 * (SPECIFICATION.md §3 "Manifest validation"). Part of the [ApiException]
 * family so the synchronization engine can handle failures exhaustively.
 */
class ManifestException(message: String, cause: Throwable? = null) : ApiException(message, cause)

/**
 * Strict manifest validation (Phase A, SPECIFICATION.md §3).
 *
 * A manifest is accepted only if the body parses as JSON, `status` is
 * `"success"`, `freeloot` is an array, and every entry carries a non-empty
 * `name` (no path separators, not dot-prefixed), a non-negative `size`, and a
 * 64-character lowercase hexadecimal `sha256`. An empty `freeloot` array with
 * `status: "success"` is valid; with the current server's `status: "error"`
 * it is rejected here (an API error must never be read as an empty content
 * list, §3 and §5).
 */
object FreelootManifest {

    /** Unknown fields are tolerated (the server may add metadata later). */
    private val json = Json { ignoreUnknownKeys = true }

    private val sha256Pattern = Regex("[0-9a-f]{64}")

    // Wire shape; every field is optional so that missing fields fail
    // validation with a precise message instead of a generic parse error.
    @Serializable
    private data class RawManifest(
        val message: String? = null,
        val freeloot: List<RawEntry>? = null,
        val status: String? = null,
    )

    @Serializable
    private data class RawEntry(
        val name: String? = null,
        val size: Long? = null,
        val sha256: String? = null,
    )

    /**
     * Parses and validates a /freeloot response body.
     *
     * @throws ManifestException on any violation of the acceptance rules above.
     */
    fun parse(body: String): List<ManifestEntry> {
        val raw = try {
            json.decodeFromString<RawManifest>(body)
        } catch (e: SerializationException) {
            throw ManifestException("Manifest body is not a valid JSON document", e)
        } catch (e: IllegalArgumentException) {
            throw ManifestException("Manifest body is not a valid JSON document", e)
        }
        if (raw.status != "success") {
            throw ManifestException(
                "Manifest status is ${raw.status?.let { "\"$it\"" } ?: "missing"}; a manifest is valid only with \"success\"",
            )
        }
        val freeloot = raw.freeloot ?: throw ManifestException("Manifest has no freeloot array")
        return freeloot.mapIndexed(::validated)
    }

    private fun validated(index: Int, entry: RawEntry): ManifestEntry {
        val where = "freeloot[$index]"
        val name = entry.name
        if (name.isNullOrEmpty() || name.isBlank()) throw ManifestException("$where has an empty name")
        if (name.startsWith('.')) throw ManifestException("$where has a dot-prefixed name: \"$name\"")
        if ('/' in name || '\\' in name) throw ManifestException("$where has a path separator in its name: \"$name\"")
        val size = entry.size ?: throw ManifestException("$where has no size")
        if (size < 0) throw ManifestException("$where has a negative size")
        val sha256 = entry.sha256 ?: throw ManifestException("$where has no sha256")
        if (!sha256Pattern.matches(sha256)) {
            throw ManifestException("$where has a malformed sha256 (want 64 lowercase hex characters)")
        }
        return ManifestEntry(name = name, size = size, sha256 = sha256)
    }
}
