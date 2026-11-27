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
 * Offline tests of what the Library and Home screens show (§8): every item is
 * an openable file, sidecars/markers are invisible (§11: 24), counts and
 * mtime-based recency.
 */
class LibraryContentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val layout get() = ContentLayout(temp.root)

    @Test
    fun `files are listed with paths relative to the category`() {
        layout.createDirectories()
        File(layout.books, "B.epub").writeText("b")
        val album = File(layout.music, "Some OST").apply { mkdirs() }
        File(album, "sub").mkdirs()
        File(album, "track.opus").writeText("t")
        File(album, "sub/booklet.pdf").writeText("p")

        val books = LibraryContent.items(layout, Category.Books)
        val music = LibraryContent.items(layout, Category.Music)

        assertEquals(listOf("B.epub"), books.map { it.name })
        // Archive contents appear as relative paths, alphabetical, all files:
        assertEquals(listOf("Some OST/sub/booklet.pdf", "Some OST/track.opus"), music.map { it.name })
        assertTrue(music.none { it.file.isDirectory })
    }

    @Test
    fun `sidecars are never listed while their file exists`() {
        layout.createDirectories()
        File(layout.books, "B.epub").writeText("b")
        File(layout.books, "B.epub.hash").writeText("0".repeat(64))

        assertEquals(listOf("B.epub"), LibraryContent.items(layout, Category.Books).map { it.name })
    }

    @Test
    fun `a manifest file that itself ends in hash stays visible`() {
        layout.createDirectories()
        File(layout.other, "release-notes.hash").writeText("deadbeef")

        assertEquals(
            listOf("release-notes.hash"),
            LibraryContent.items(layout, Category.Other).map { it.name },
        )
    }

    @Test
    fun `zip markers inside archive directories are never listed`() {
        layout.createDirectories()
        val album = File(layout.music, "Some OST").apply { mkdirs() }
        File(album, "track.opus").writeText("t")
        File(album, "${"a".repeat(64)}.hash").writeText("a".repeat(64))

        assertEquals(
            listOf("Some OST/track.opus"),
            LibraryContent.items(layout, Category.Music).map { it.name },
        )
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
        assertFalse(LibraryContent.items(layout, Category.Other).isNotEmpty())
    }
}
