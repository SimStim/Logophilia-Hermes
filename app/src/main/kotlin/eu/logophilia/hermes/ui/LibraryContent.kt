package eu.logophilia.hermes.ui

import eu.logophilia.hermes.sync.Category
import eu.logophilia.hermes.sync.ContentLayout
import java.io.File

/** One browsable item in the Library (§8): a file or an extracted archive directory. */
data class LibraryItem(
    val name: String,
    val file: File,
    val isDirectory: Boolean,
)

/**
 * Maps the synchronized content directories to what the Library and Home
 * screens show (§8). Internal metadata is never listed (§6, §11: 24): a
 * `<name>.hash` sidecar is hidden while its file exists beside it; ZIP
 * markers live inside archive directories, which are listed as single items.
 */
object LibraryContent {

    fun items(layout: ContentLayout, category: Category): List<LibraryItem> {
        val children = layout.dirFor(category).listFiles().orEmpty()
        val names = children.mapTo(mutableSetOf()) { it.name }
        return children
            .filter { child ->
                !(child.isFile && isSidecarOf(child, names))
            }
            .map { LibraryItem(it.name, it, it.isDirectory) }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    private fun isSidecarOf(child: File, names: Set<String>): Boolean =
        child.name.endsWith(ContentLayout.SIDECAR_SUFFIX) &&
            names.contains(child.name.removeSuffix(ContentLayout.SIDECAR_SUFFIX))

    /** Counts for the Home screen: books, albums (extracted OSTs), documents (§8). */
    fun counts(layout: ContentLayout): Triple<Int, Int, Int> {
        val books = files(layout, Category.Books).size
        val albums = layout.music.listFiles().orEmpty().count { it.isDirectory }
        val documents = files(layout, Category.Documents).size
        return Triple(books, albums, documents)
    }

    /** Recently added, derived from mtimes, newest first (§8 Home). */
    fun recent(layout: ContentLayout, limit: Int = 5): List<Pair<Category, LibraryItem>> =
        Category.entries.flatMap { category ->
            items(layout, category).map { category to it }
        }
            .sortedByDescending { it.second.file.lastModified() }
            .take(limit)

    private fun files(layout: ContentLayout, category: Category): List<LibraryItem> =
        items(layout, category).filterNot { it.isDirectory }
}
