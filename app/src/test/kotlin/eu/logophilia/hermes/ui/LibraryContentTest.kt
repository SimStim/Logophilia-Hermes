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
 * Offline tests of what the Library and Home screens show (§8): a hierarchical
 * listing — plain files as items, one item per extracted archive named after
 * the archive basename without ".zip", browsed one level down — with
 * sidecars/markers invisible (§11: 24), counts, and mtime-based recency over
 * top-level items only (archive items by the directory's own timestamp).
 */
class LibraryContentTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val layout get() = ContentLayout(temp.root)

    @Test
    fun `top level lists plain files and one item per extracted archive`() {
        layout.createDirectories()
        File(layout.books, "B.epub").writeText("b")
        File(layout.books, "C.epub").writeText("c")
        val album = File(layout.music, "Some OST").apply { mkdirs() }
        File(album, "track.opus").writeText("t")

        val books = LibraryContent.topLevel(layout, Category.Books)
        val music = LibraryContent.topLevel(layout, Category.Music)

        assertEquals(listOf("B.epub", "C.epub"), books.map { it.name })
        assertTrue(books.all { it is LibraryItem.FileItem })
        // The archive appears once, named after the archive basename without
        // ".zip" (§8 Library) — never as its inner files:
        assertEquals(listOf("Some OST"), music.map { it.name })
        assertTrue(music.single() is LibraryItem.ArchiveItem)
    }

    @Test
    fun `tapping an archive lists the files it contains without metadata`() {
        layout.createDirectories()
        val album = File(layout.music, "Some OST").apply { mkdirs() }
        File(album, "track.opus").writeText("t")
        File(album, "sub").mkdirs()
        File(album, "sub/booklet.pdf").writeText("p")
        // ZIP marker inside the archive root, and a sidecar next to a file:
        File(album, "${"a".repeat(64)}.hash").writeText("a".repeat(64))
        File(album, "track.opus.hash").writeText("0".repeat(64))

        val archive = LibraryContent.topLevel(layout, Category.Music)
            .single() as LibraryItem.ArchiveItem
        val inside = LibraryContent.contents(archive.directory)

        // Contained files, relative to the archive, alphabetical; `.hash`
        // markers and sidecars are never listed (§6, §11: 24):
        assertEquals(listOf("sub/booklet.pdf", "track.opus"), inside.map { it.name })
        assertTrue(inside.all { it is LibraryItem.FileItem })
    }

    @Test
    fun `top-level sidecars are never listed while their file exists`() {
        layout.createDirectories()
        File(layout.books, "B.epub").writeText("b")
        File(layout.books, "B.epub.hash").writeText("0".repeat(64))

        assertEquals(
            listOf("B.epub"),
            LibraryContent.topLevel(layout, Category.Books).map { it.name },
        )
    }

    @Test
    fun `a manifest file that itself ends in hash stays visible`() {
        layout.createDirectories()
        File(layout.other, "release-notes.hash").writeText("deadbeef")

        assertEquals(
            listOf("release-notes.hash"),
            LibraryContent.topLevel(layout, Category.Other).map { it.name },
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
    fun `recent content is top-level only, archives by their directory mtime`() {
        layout.createDirectories()
        val old = File(layout.books, "Old.epub").apply {
            writeText("old")
            setLastModified(1_000)
        }
        val newer = File(layout.documents, "New.pdf").apply {
            writeText("new")
            setLastModified(2_000)
        }
        val album = File(layout.music, "Some OST").apply {
            mkdirs()
            val track = File(this, "track.opus")
            track.writeText("t")
            track.setLastModified(9_000)
        }
        album.setLastModified(5_000)

        val recent = LibraryContent.recent(layout)

        // Top-level items only (§8 Home): the album appears once, at its
        // extracted directory's own timestamp; its inner file (mtime 9000)
        // never leaks into the list.
        assertEquals(
            listOf("Some OST", "New.pdf", "Old.epub"),
            recent.map { it.second.name },
        )
        assertTrue(recent.first().second is LibraryItem.ArchiveItem)
    }

    @Test
    fun `empty directories yield empty listings and recency`() {
        layout.createDirectories()

        assertTrue(LibraryContent.topLevel(layout, Category.Books).isEmpty())
        assertTrue(LibraryContent.recent(layout).isEmpty())
        assertFalse(LibraryContent.topLevel(layout, Category.Other).isNotEmpty())
    }
}
