package eu.logophilia.hermes.api

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Offline tests of the strict manifest validation rules
 * (SPECIFICATION.md §3 "Manifest validation").
 */
class FreelootManifestTest {

    private val validSha = "a".repeat(64)

    private fun entry(
        name: String = "\"The Pitch Science Fiction 2025 OST E55.zip\"",
        size: String = "12345678",
        sha256: String = "\"$validSha\"",
    ): String = "\"name\":$name,\"size\":$size,\"sha256\":$sha256"

    private fun manifest(
        vararg entries: String,
        status: String = "\"success\"",
        freeloot: String? = null,
    ): String {
        val list = freeloot ?: entries.joinToString(",", "[", "]") { "{$it}" }
        return "{\"message\":\"Here is your free loot!\",\"freeloot\":$list,\"status\":$status}"
    }

    private fun assertAccepted(body: String, expectedSize: Int = 1) {
        assertEquals(expectedSize, FreelootManifest.parse(body).size)
    }

    private fun assertRejected(body: String) {
        try {
            FreelootManifest.parse(body)
            fail("expected ManifestException for: $body")
        } catch (_: ManifestException) {
            // expected: every malformed, incomplete, or corrupted response is rejected
        }
    }

    @Test
    fun `accepts a valid manifest and maps its entries`() {
        val parsed = FreelootManifest.parse(
            manifest(
                entry(),
                entry(name = "\"The Pitch Science Fiction 1Q2025 - Logophilia Essentials.epub\"", size = "981516"),
            ),
        )
        assertEquals(2, parsed.size)
        assertEquals("The Pitch Science Fiction 2025 OST E55.zip", parsed[0].name)
        assertEquals(12345678L, parsed[0].size)
        assertEquals(validSha, parsed[0].sha256)
    }

    @Test
    fun `accepts an empty manifest with status success`() {
        assertAccepted(manifest(status = "\"success\"", freeloot = "[]"), expectedSize = 0)
    }

    @Test
    fun `tolerates unknown fields on the manifest and its entries`() {
        assertAccepted(
            "{\"message\":\"x\",\"freeloot\":[{${entry()},\"added\":\"2026\"}]," +
                "\"status\":\"success\",\"note\":\"extra\"}",
        )
    }

    @Test
    fun `rejects an empty list with a non-success status`() {
        // The server now answers an empty downloads folder with status
        // success (TODO.md, fixed); a non-success status must still never
        // read as empty content — that rule guards every other responder.
        assertRejected(manifest(status = "\"error\"", freeloot = "[]"))
    }

    @Test
    fun `rejects a non-success status even with entries`() {
        assertRejected(manifest(entry(), status = "\"error\""))
        assertRejected(manifest(entry(), status = "\"SUCCESS\""))
        assertRejected(manifest(entry(), status = "null"))
    }

    @Test
    fun `rejects a manifest without status`() {
        assertRejected("{\"message\":\"x\",\"freeloot\":[]}")
    }

    @Test
    fun `rejects a manifest without a freeloot array`() {
        assertRejected("{\"message\":\"x\",\"status\":\"success\"}")
        assertRejected("""{"message":"x","freeloot":"none","status":"success"}""")
        assertRejected("""{"message":"x","freeloot":{},"status":"success"}""")
    }

    @Test
    fun `rejects bodies that are not JSON documents`() {
        assertRejected("")
        assertRejected("not json at all")
        assertRejected("[1,2,3]")
        assertRejected("\"a string\"")
    }

    @Test
    fun `rejects entries with missing fields`() {
        assertRejected(manifest("{\"name\":\"a.zip\",\"size\":1}"))
        assertRejected(manifest("{\"name\":\"a.zip\",\"sha256\":\"$validSha\"}"))
        assertRejected(manifest("{\"size\":1,\"sha256\":\"$validSha\"}"))
    }

    @Test
    fun `rejects empty and blank names`() {
        assertRejected(manifest(entry(name = "\"\"")))
        assertRejected(manifest(entry(name = "\"   \"")))
    }

    @Test
    fun `rejects dot-prefixed names`() {
        assertRejected(manifest(entry(name = "\".hidden.zip\"")))
        assertRejected(manifest(entry(name = "\"..\"")))
    }

    @Test
    fun `rejects names with path separators`() {
        assertRejected(manifest(entry(name = "\"sub/dir.zip\"")))
        assertRejected(manifest(entry(name = "\"..\\/escape.zip\"")))
        assertRejected(manifest(entry(name = "\"back\\\\slash.zip\"")))
    }

    @Test
    fun `rejects negative sizes`() {
        assertRejected(manifest(entry(size = "-1")))
        // A quoted size is coerced by the JSON library; the value rule below
        // still catches a quoted negative number.
        assertRejected(manifest(entry(size = "\"-1\"")))
    }

    @Test
    fun `accepts a quoted size with a valid value`() {
        // The spec demands a non-negative size; whether the server sends the
        // number quoted is not part of the acceptance rules.
        assertAccepted(manifest(entry(size = "\"123\"")))
    }

    @Test
    fun `accepts a zero size`() {
        assertAccepted(manifest(entry(size = "0")))
    }

    @Test
    fun `rejects malformed sha256 values`() {
        assertRejected(manifest(entry(sha256 = "\"${validSha.uppercase()}\""))) // not lowercase
        assertRejected(manifest(entry(sha256 = "\"${"a".repeat(63)}\""))) // too short
        assertRejected(manifest(entry(sha256 = "\"${"a".repeat(65)}\""))) // too long
        assertRejected(manifest(entry(sha256 = "\"${"g".repeat(64)}\""))) // not hexadecimal
        assertRejected(manifest(entry(sha256 = "null")))
    }
}
