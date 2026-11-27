package eu.logophilia.hermes.sync

import java.io.File

/**
 * Content categories and their top-level directories in private application
 * storage (SPECIFICATION.md §6). Everything lives app-private; no external
 * storage permissions.
 */
enum class Category(val directoryName: String) {
    Books("Books"),
    Music("Music"),
    Documents("Documents"),
    Other("Other"),
}

/**
 * Where a manifest entry belongs locally (§6 Classification rules,
 * deterministic and filename/extension based).
 */
sealed class Placement {
    abstract val category: Category

    /** A plain file kept directly in its category directory. */
    data class Plain(override val category: Category) : Placement()

    /**
     * A ZIP archive, extracted into `<category>/<baseName>/` with the
     * original archive never retained (§5 Phase D, §6): OST archives extract
     * to `Music/<archive basename>/`, all other ZIPs to `Other/<archive
     * basename>/`. The marker file `<sha256>.hash` in the directory root
     * replaces the archive.
     */
    data class Archive(override val category: Category, val baseName: String) : Placement()
}

/** Filename/extension-based classification of manifest entries (§6). */
object ContentClassifier {

    private val bookExtensions = setOf("epub", "mobi", "azw", "azw3", "fb2", "cbz", "cbr")
    private val audioExtensions =
        setOf("opus", "mp3", "flac", "ogg", "oga", "m4a", "aac", "wav", "wma")
    private val documentExtensions = setOf("txt", "md", "html", "htm", "pdf")

    fun classify(name: String): Placement {
        val lowercase = name.lowercase()
        if (lowercase.endsWith(".zip")) {
            // Pattern "<publication title> OST<optional>.zip" (§6): OST is
            // matched verbatim, exactly as the classification table states.
            val category = if ("OST" in name) Category.Music else Category.Other
            return Placement.Archive(category, name.dropLast(".zip".length))
        }
        val extension = lowercase.substringAfterLast('.', "")
        return when {
            extension in bookExtensions -> Placement.Plain(Category.Books)
            extension in audioExtensions -> Placement.Plain(Category.Music)
            extension in documentExtensions -> Placement.Plain(Category.Documents)
            else -> Placement.Plain(Category.Other)
        }
    }
}

/**
 * The on-disk layout of synchronized content (SPECIFICATION.md §6):
 *
 * ```
 * <root>/Books/  <root>/Music/  <root>/Documents/  <root>/Other/  <root>/Temp/
 * ```
 *
 * `.hash` sidecars live next to their plain files, ZIP markers at the root of
 * their extracted directory — internal metadata, never listed in the UI and
 * never offered to external applications (§6, §7).
 */
class ContentLayout(private val root: File) {

    val books = File(root, Category.Books.directoryName)
    val music = File(root, Category.Music.directoryName)
    val documents = File(root, Category.Documents.directoryName)
    val other = File(root, Category.Other.directoryName)
    val temp = File(root, TEMP_DIRECTORY_NAME)

    val categoryDirectories = listOf(books, music, documents, other)

    fun dirFor(category: Category): File = when (category) {
        Category.Books -> books
        Category.Music -> music
        Category.Documents -> documents
        Category.Other -> other
    }

    fun createDirectories() {
        categoryDirectories.forEach { it.mkdirs() }
        temp.mkdirs()
    }

    /** §4 Temp discipline: wiped at the beginning of every attempt. */
    fun wipeTemp() {
        temp.deleteRecursively()
        temp.mkdirs()
    }

    /** The `<name>.hash` sidecar of a plain file, in the same directory (§5 Phase B). */
    fun sidecarOf(file: File): File = File(file.parentFile, file.name + SIDECAR_SUFFIX)

    companion object {
        const val SIDECAR_SUFFIX = ".hash"
        const val TEMP_DIRECTORY_NAME = "Temp"
        const val TEMP_DOWNLOADS = "downloads"
        const val TEMP_EXTRACT = "extract"

        /** Sidecars and markers are 64 hex characters; refuse to read more. */
        const val MAX_METADATA_BYTES = 4 * 1024L
    }
}
