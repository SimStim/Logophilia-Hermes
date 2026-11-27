package eu.logophilia.hermes.ui

import eu.logophilia.hermes.sync.Category
import eu.logophilia.hermes.sync.ContentLayout
import java.io.File

/** One browsable, openable item in the Library (§8). */
data class LibraryItem(
    /** Path relative to the category directory, as shown in the UI. */
    val name: String,
    val file: File,
)

/**
 * Maps the synchronized content directories to what the Library and Home
 * screens show (§8). Every listed item is a plain file, so tapping it can
 * always hand a real file to an external application (§7); extracted archive
 * directories appear through the files they contain, named relatively
 * (e.g. "Some OST/track.opus").
 *
 * Internal metadata is never listed (§6, §11: 24): a `<name>.hash` sidecar is
 * hidden while its file exists beside it, and ZIP markers (`<sha256>.hash`
 * inside archive directories) are never shown.
 */
object LibraryContent {

    /** A ZIP marker is named after a 64-character lowercase hex checksum. */
    private val markerPattern = Regex("[0-9a-f]{64}\\.hash")

    fun items(layout: ContentLayout, category: Category): List<LibraryItem> {
        val root = layout.dirFor(category)
        return root.walkTopDown()
            .filter { it.isFile && !isInternalMetadata(it) }
            .map { LibraryItem(name = it.toRelativeString(root), file = it) }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    private fun isInternalMetadata(file: File): Boolean {
        if (!file.name.endsWith(ContentLayout.SIDECAR_SUFFIX)) return false
        if (markerPattern.matches(file.name)) return true // ZIP marker
        // A sidecar is hidden while its file exists beside it; a manifest file
        // that itself ends in .hash (no sibling base) stays visible.
        val base = file.name.removeSuffix(ContentLayout.SIDECAR_SUFFIX)
        return File(file.parentFile, base).exists()
    }

    /** Counts for the Home screen: books, albums (extracted OSTs), documents (§8). */
    fun counts(layout: ContentLayout): Triple<Int, Int, Int> {
        val books = items(layout, Category.Books).size
        val albums = layout.music.listFiles().orEmpty().count { it.isDirectory }
        val documents = items(layout, Category.Documents).size
        return Triple(books, albums, documents)
    }

    /** Recently added, derived from mtimes, newest first (§8 Home). */
    fun recent(layout: ContentLayout, limit: Int = 5): List<Pair<Category, LibraryItem>> =
        Category.entries.flatMap { category ->
            items(layout, category).map { category to it }
        }
            .sortedByDescending { it.second.file.lastModified() }
            .take(limit)
}
