package eu.logophilia.hermes.ui

import eu.logophilia.hermes.sync.Category
import eu.logophilia.hermes.sync.ContentLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Offline tests of what the Library and Home screens show (§8): categories,
 * sidecar/marker invisibility (§11: 24), counts, and mtime-based recency.
 */
class LibraryContentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val layout get() = ContentLayout(temp.root)

    @Test
    fun `plain files and archive directories are listed per category`() {
        layout.createDirectories()
        File(layout.books, "B.epub").writeText("b")
        File(layout.music, "Loose.opus").writeText("l")
        val album = File(layout.music, "Some OST").apply { mkdirs() }
        File(album, "track.opus").writeText("t")

        val books = LibraryContent.items(layout, Category.Books)
        val music = LibraryContent.items(layout, Category.Music)

        assertEquals(listOf("B.epub"), books.map { it.name })
        // Directories first, then files, each alphabetical:
        assertEquals(listOf("Some OST", "Loose.opus"), music.map { it.name })
        assertTrue(music.first().isDirectory)
        assertFalse(music.last().isDirectory)
    }

    @Test
    fun `sidecars are never listed while their file exists`() {
        layout.createDirectories()
        File(layout.books, "B.epub").writeText("b")
        File(layout.books, "B.epub.hash").writeText("0".repeat(64))

        val books = LibraryContent.items(layout, Category.Books)

        assertEquals(listOf("B.epub"), books.map { it.name })
    }

    @Test
    fun `markers inside archive directories do not leak into the listing`() {
        layout.createDirectories()
        val album = File(layout.music, "Some OST").apply { mkdirs() }
        File(album, "track.opus").writeText("t")
        File(album, "${"a".repeat(64)}.hash").writeText("a".repeat(64))

        val music = LibraryContent.items(layout, Category.Music)

        // The album is one item; its marker is not a separate entry.
        assertEquals(listOf("Some OST"), music.map { it.name })
    }

    @Test
    fun `counts are books, albums, documents`() {
        layout.createDirectories()
        File(layout.books, "B.epub").writeText("b")
        File(layout.books, "C.epub").writeText("c")
        File(layout.documents, "D.pdf").writeText("d")
        File(layout.music, "Some OST").mkdirs()
        File(layout.music, "Loose.opus").writeText("l")
        File(layout.other, "X.bin").writeText("x")

        val (books, albums, documents) = LibraryContent.counts(layout)

        assertEquals(2, books)
        assertEquals(1, albums)
        assertEquals(1, documents)
    }

    @Test
    fun `recent content is the newest first by modification time`() {
        layout.createDirectories()
        val old = File(layout.books, "Old.epub").apply {
            writeText("old")
            setLastModified(1_000)
        }
        val newer = File(layout.documents, "New.pdf").apply {
            writeText("new")
            setLastModified(2_000)
        }

        val recent = LibraryContent.recent(layout)

        assertEquals(listOf(newer.name, old.name), recent.map { it.second.name })
    }

    @Test
    fun `empty directories yield empty listings and recency`() {
        layout.createDirectories()

        assertTrue(LibraryContent.items(layout, Category.Books).isEmpty())
        assertTrue(LibraryContent.recent(layout).isEmpty())
    }
}
