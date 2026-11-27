package eu.logophilia.hermes.ui

import eu.logophilia.hermes.sync.Category
import eu.logophilia.hermes.sync.ContentLayout
import java.io.File

/**
 * One browsable item in the Library (§8): either a plain openable file or an
 * extracted archive. The listing is hierarchical — each extracted archive
 * appears as a single item named after the archive basename without ".zip";
 * tapping it lists the files it contains instead of opening anything.
 */
sealed interface LibraryItem {

    /** The display name: a file name, or the archive basename sans ".zip". */
    val name: String

    /**
     * Modification time driving "recently added" (§8 Home). For archive items
     * this is the extracted directory's own timestamp (§8: no added dates are
     * stored anywhere).
     */
    val lastModified: Long

    /** A plain file, openable by an external application (§7). */
    data class FileItem(override val name: String, val file: File) : LibraryItem {
        override val lastModified: Long get() = file.lastModified()
    }

    /** An extracted archive, browsed as one item (§8 Library). */
    data class ArchiveItem(override val name: String, val directory: File) : LibraryItem {
        override val lastModified: Long get() = directory.lastModified()
    }
}

/**
 * Maps the synchronized content directories to what the Library and Home
 * screens show (§8). The top level of each category lists plain files and one
 * item per extracted archive; an archive's contents are listed one level down.
 *
 * Internal metadata is never listed (§6, §11: 24): a `<name>.hash` sidecar is
 * hidden while its file exists beside it, and ZIP markers (`<sha256>.hash`
 * inside archive directories) are never shown.
 */
object LibraryContent {

    /** A ZIP marker is named after a 64-character lowercase hex checksum. */
    private val markerPattern = Regex("[0-9a-f]{64}\\.hash")

    /**
     * The Library's top level (§8): plain files appear as items, and each
     * extracted archive appears as a single item named after the archive
     * basename without ".zip".
     */
    fun topLevel(layout: ContentLayout, category: Category): List<LibraryItem> {
        val root = layout.dirFor(category)
        return root.listFiles().orEmpty()
            .filter { !isInternalMetadata(it) }
            .map {
                if (it.isDirectory) {
                    LibraryItem.ArchiveItem(name = it.name, directory = it)
                } else {
                    LibraryItem.FileItem(name = it.name, file = it)
                }
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * The files inside one extracted archive, as listed when it is tapped
     * (§8 Library); tapping a contained file opens it (§7).
     */
    fun contents(archive: File): List<LibraryItem.FileItem> =
        archive.walkTopDown()
            .filter { it.isFile && !isInternalMetadata(it) }
            .map { LibraryItem.FileItem(name = it.toRelativeString(archive), file = it) }
            .sortedBy { it.name.lowercase() }
            .toList()

    private fun isInternalMetadata(file: File): Boolean {
        if (!file.isFile) return false // an archive directory is never metadata
        if (!file.name.endsWith(ContentLayout.SIDECAR_SUFFIX)) return false
        if (markerPattern.matches(file.name)) return true // ZIP marker
        // A sidecar is hidden while its file exists beside it; a manifest file
        // that itself ends in .hash (no sibling base) stays visible.
        val base = file.name.removeSuffix(ContentLayout.SIDECAR_SUFFIX)
        return File(file.parentFile, base).exists()
    }

    /** Counts for the Home screen: books, albums (extracted OSTs), documents (§8). */
    fun counts(layout: ContentLayout): Triple<Int, Int, Int> {
        val books = topLevel(layout, Category.Books).size
        val albums = layout.music.listFiles().orEmpty().count { it.isDirectory }
        val documents = topLevel(layout, Category.Documents).size
        return Triple(books, albums, documents)
    }

    /**
     * Recently added, derived from modification times, newest first (§8 Home).
     * Top-level items only: a new OST album appears once, never as its inner
     * files, and archive items use the extracted directory's own timestamp.
     */
    fun recent(layout: ContentLayout, limit: Int = 5): List<Pair<Category, LibraryItem>> =
        Category.entries.flatMap { category ->
            topLevel(layout, category).map { category to it }
        }
            .sortedByDescending { it.second.lastModified }
            .take(limit)
}
