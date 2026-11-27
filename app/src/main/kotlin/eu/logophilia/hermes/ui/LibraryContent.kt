package eu.logophilia.hermes.ui

import eu.logophilia.hermes.sync.Category
import eu.logophilia.hermes.sync.ContentLayout
import java.io.File

/**
 * One browsable item in the Library (§8): either a plain openable file or a
 * browsable directory. The listing is hierarchical — each extracted archive
 * appears at the top level as a single item named after the archive basename
 * without ".zip"; tapping it lists what it contains. Subdirectories inside an
 * archive are browsed the same way, one tap per level, and every display name
 * is the bare file-system name — user-facing text never contains "/" (§8).
 */
sealed interface LibraryItem {

    /** The display name: the bare file or directory name, never a path. */
    val name: String

    /**
     * Modification time driving "recently added" (§8 Home). For folders this
     * is the directory's own timestamp (§8: no added dates are stored anywhere).
     */
    val lastModified: Long

    /** A plain file, openable by an external application (§7). */
    data class FileItem(override val name: String, val file: File) : LibraryItem {
        override val lastModified: Long get() = file.lastModified()
    }

    /**
     * A browsable directory: an extracted archive at the top level, or a
     * subdirectory inside one (§8 Library). Tapping it lists its contents.
     */
    data class FolderItem(override val name: String, val directory: File) : LibraryItem {
        override val lastModified: Long get() = directory.lastModified()
    }
}

/**
 * Maps the synchronized content directories to what the Library and Home
 * screens show (§8). The top level of each category lists plain files and one
 * item per extracted archive; browsing lists immediate children only, so deep
 * nesting simply costs one tap per level.
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
                    LibraryItem.FolderItem(name = it.name, directory = it)
                } else {
                    LibraryItem.FileItem(name = it.name, file = it)
                }
            }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * What a browsed directory contains (§8 Library): its immediate children,
     * each named with the bare file-system name — never a path. Tapping a
     * contained file opens it (§7); tapping a contained directory lists it
     * the same way, so nesting costs one tap per level.
     */
    fun contents(directory: File): List<LibraryItem> =
        directory.listFiles().orEmpty()
            .filter { !isInternalMetadata(it) }
            .map {
                if (it.isDirectory) {
                    LibraryItem.FolderItem(name = it.name, directory = it)
                } else {
                    LibraryItem.FileItem(name = it.name, file = it)
                }
            }
            .sortedBy { it.name.lowercase() }

    /**
     * One level up from a browsed directory, or null when it already is the
     * category's top level (the "Back" affordance, §8 Library).
     */
    fun parent(of: LibraryItem.FolderItem, layout: ContentLayout, category: Category): LibraryItem.FolderItem? {
        val parent = of.directory.parentFile ?: return null
        val root = layout.dirFor(category)
        if (parent.path != root.path) return LibraryItem.FolderItem(parent.name, parent)
        return null
    }

    private fun isInternalMetadata(file: File): Boolean {
        if (!file.isFile) return false // a browsable directory is never metadata
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
