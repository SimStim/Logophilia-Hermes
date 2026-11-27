package eu.logophilia.hermes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Offline tests of ZIP extraction safety (SPECIFICATION.md §5 Phase D):
 * ZIP Slip protection, entry/size limits, and structure preservation.
 */
class ZipExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val extractor = ZipExtractor()

    private fun zipOf(name: String, vararg entries: Pair<String, String>): File {
        val archive = File(temp.root, name)
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (path, content) ->
                if (content == DIR_SENTINEL) {
                    output.putNextEntry(ZipEntry(path))
                } else {
                    output.putNextEntry(ZipEntry(path))
                    output.write(content.toByteArray())
                }
                output.closeEntry()
            }
        }
        return archive
    }

    private fun destination(): File = File(temp.root, "extracted")

    private fun failure(block: () -> Unit): Throwable =
        try {
            block()
            throw AssertionError("expected the extraction to fail")
        } catch (t: Throwable) {
            t
        }

    @Test
    fun `extracts nested structure`() {
        val archive = zipOf(
            "ok.zip",
            "top.txt" to "one",
            "sub/inner.txt" to "two",
            "sub/deeper/deepest.txt" to "three",
        )

        extractor.extract(archive, destination())

        assertEquals("one", File(destination(), "top.txt").readText())
        assertEquals("two", File(destination(), "sub/inner.txt").readText())
        assertEquals("three", File(destination(), "sub/deeper/deepest.txt").readText())
    }

    @Test
    fun `directory entries become directories`() {
        val archive = zipOf("dirs.zip", "album/" to DIR_SENTINEL, "album/track.opus" to "audio")

        extractor.extract(archive, destination())

        assertTrue(File(destination(), "album").isDirectory)
        assertTrue(File(destination(), "album/track.opus").isFile)
    }

    @Test
    fun `rejects an entry escaping the destination with dot-dot`() {
        val archive = zipOf("slip.zip", "../escape.txt" to "evil")
        val outside = File(temp.root, "escape.txt")

        val failure = failure { extractor.extract(archive, destination()) }

        assertTrue("expected ZipExtractionException, got $failure", failure is ZipExtractionException)
        assertFalse(outside.exists())
    }

    @Test
    fun `an absolute-path entry cannot escape the destination`() {
        val archive = zipOf("absolute.zip", "/etc/escape" to "evil")

        // java.io.File joins an absolute child under the parent, so the entry
        // lands inside the destination; the invariant is: nothing escapes.
        extractor.extract(archive, destination())

        assertFalse(File("/etc/escape").exists())
        assertTrue(File(destination(), "etc/escape").isFile)
    }

    @Test
    fun `rejects an entry writing into the destination's parent`() {
        val archive = zipOf("dot.zip", "./../sibling.txt" to "evil")

        val failure = failure { extractor.extract(archive, destination()) }

        assertTrue("expected ZipExtractionException, got $failure", failure is ZipExtractionException)
        assertFalse(File(temp.root, "sibling.txt").exists())
    }

    @Test
    fun `rejects archives with too many entries`() {
        val archive = File(temp.root, "many.zip")
        ZipOutputStream(archive.outputStream()).use { output ->
            repeat(6) { index ->
                output.putNextEntry(ZipEntry("entry-$index.txt"))
                output.write(ByteArray(1))
                output.closeEntry()
            }
        }
        val tight = ZipExtractor(maxEntries = 5)

        val failure = failure { tight.extract(archive, destination()) }

        assertTrue("expected ZipExtractionException, got $failure", failure is ZipExtractionException)
    }

    @Test
    fun `rejects archives exceeding the extracted-size limit`() {
        val archive = zipOf("big.zip", "blob.bin" to "x".repeat(10_000))
        val tight = ZipExtractor(maxTotalUncompressedBytes = 1_000)

        val failure = failure { tight.extract(archive, destination()) }

        assertTrue("expected ZipExtractionException, got $failure", failure is ZipExtractionException)
    }

    @Test
    fun `rejects a file that is not a zip`() {
        val notAZip = File(temp.root, "bogus.zip").apply { writeText("definitely not a zip") }

        val failure = failure { extractor.extract(notAZip, destination()) }

        assertTrue("expected ZipExtractionException, got $failure", failure is ZipExtractionException)
    }

    @Test
    fun `a failed extraction never writes outside the destination`() {
        val archive = zipOf("mixed.zip", "good.txt" to "fine", "../escape.txt" to "evil")

        val failure = failure { extractor.extract(archive, destination()) }

        assertTrue("expected ZipExtractionException, got $failure", failure is ZipExtractionException)
        // Whatever debris remains inside the destination is Temp-scoped and
        // discarded by the engine; nothing may have escaped it.
        assertFalse(File(temp.root, "escape.txt").exists())
        assertTrue(File(destination(), "good.txt").isFile)
    }

    private companion object {
        const val DIR_SENTINEL = "<dir>"
    }
}
