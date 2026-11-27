package eu.logophilia.hermes.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Offline tests of the storage layout and the deterministic classification
 * rules (SPECIFICATION.md §6).
 */
class ContentLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var layout: ContentLayout

    private fun layout(): ContentLayout = ContentLayout(temp.root)

    @Test
    fun `category directories sit at the root next to Temp`() {
        layout = layout()
        assertEquals(File(temp.root, "Books"), layout.books)
        assertEquals(File(temp.root, "Music"), layout.music)
        assertEquals(File(temp.root, "Documents"), layout.documents)
        assertEquals(File(temp.root, "Other"), layout.other)
        assertEquals(File(temp.root, "Temp"), layout.temp)
    }

    @Test
    fun `wiping Temp discards everything inside it but keeps the directory`() {
        layout = layout()
        layout.temp.mkdirs()
        val junk = File(layout.temp, "leftover.bin").apply { writeText("junk") }
        val nested = File(layout.temp, "nested/sub").apply { mkdirs() }

        layout.wipeTemp()

        assertFalse(junk.exists())
        assertFalse(nested.exists())
        assertTrue(layout.temp.isDirectory)
    }

    @Test
    fun `sidecars live next to their file, named name-dot-hash`() {
        layout = layout()
        val file = File(layout.books, "Title.epub")
        assertEquals(File(layout.books, "Title.epub.hash"), layout.sidecarOf(file))
    }

    @Test
    fun `plain book formats go to Books`() {
        for (name in listOf("Title.epub", "Title.EPUB", "Title.mobi", "Title.azw3", "Title.fb2")) {
            assertEquals(
                "expected $name in Books",
                Placement.Plain(Category.Books),
                ContentClassifier.classify(name),
            )
        }
    }

    @Test
    fun `audio formats go to Music`() {
        for (name in listOf("Track.opus", "Track.mp3", "Track.flac", "Track.m4a")) {
            assertEquals(
                "expected $name in Music",
                Placement.Plain(Category.Music),
                ContentClassifier.classify(name),
            )
        }
    }

    @Test
    fun `document formats go to Documents`() {
        for (name in listOf("Notes.txt", "Notes.md", "Page.html", "Page.htm", "Paper.pdf")) {
            assertEquals(
                "expected $name in Documents",
                Placement.Plain(Category.Documents),
                ContentClassifier.classify(name),
            )
        }
    }

    @Test
    fun `anything else goes to Other`() {
        for (name in listOf("Image.png", "Archive.7z", "no-extension", "weird.xz")) {
            assertEquals(
                "expected $name in Other",
                Placement.Plain(Category.Other),
                ContentClassifier.classify(name),
            )
        }
    }

    @Test
    fun `OST archives extract into Music under their archive basename`() {
        assertEquals(
            Placement.Archive(Category.Music, "The Pitch Science Fiction 2025 OST E55"),
            ContentClassifier.classify("The Pitch Science Fiction 2025 OST E55.zip"),
        )
    }

    @Test
    fun `non-OST archives extract into Other`() {
        assertEquals(
            Placement.Archive(Category.Other, "Bundle"),
            ContentClassifier.classify("Bundle.zip"),
        )
    }
}
