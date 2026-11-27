package eu.logophilia.hermes.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import eu.logophilia.hermes.R
import java.io.File
import java.net.URLEncoder

/**
 * An installation suggestion with a direct Google Play link (§7). The app
 * name is a string resource — all user-facing copy lives in
 * res/values/strings.xml (§8 User-facing copy).
 */
data class InstallSuggestion(val appNameRes: Int, val playUrl: String)

/**
 * File opening with external applications (SPECIFICATION.md §7): MIME types
 * per file type, and — where an appropriate application is known to exist —
 * a concrete installation suggestion. No handler is ever assumed by name;
 * actual intent resolution decides (see [FileOpener]).
 */
object OpenWith {

    /** Moon+ Reader (free), as specified in §7. */
    private const val MOON_READER =
        "https://play.google.com/store/apps/details?id=com.flyersoft.moonreader"

    /** foobar2000 for Android — official package verified 2026-11-27 against
     *  the developer's own Play redirect (foobar2000.org/get-android), §12.2. */
    private const val FOOBAR2000 =
        "https://play.google.com/store/apps/details?id=com.foobar2000.foobar2000"

    private val mimeByExtension = mapOf(
        // Book formats (§6 Books/)
        "epub" to "application/epub+zip",
        "mobi" to "application/x-mobipocket-ebook",
        "azw" to "application/vnd.amazon.ebook",
        "azw3" to "application/vnd.amazon.ebook",
        "fb2" to "application/x-fictionbook+xml",
        "cbz" to "application/vnd.comicbook+zip",
        "cbr" to "application/vnd.comicbook-rar",
        // Audio (§6 Music/)
        "opus" to "audio/opus",
        "mp3" to "audio/mpeg",
        "flac" to "audio/flac",
        "ogg" to "audio/ogg",
        "oga" to "audio/ogg",
        "m4a" to "audio/mp4",
        "aac" to "audio/aac",
        "wav" to "audio/wav",
        "wma" to "audio/x-ms-wma",
        // Documents (§6 Documents/)
        "txt" to "text/plain",
        "md" to "text/markdown",
        "html" to "text/html",
        "htm" to "text/html",
        "pdf" to "application/pdf",
        // Images (§7: .jpg, .jpeg, .png, .webp, .gif — image viewer)
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
    )

    /**
     * The MIME type handed to ACTION_VIEW. Anything unknown is left to
     * Android as generic binary data (§7: "other — let Android choose").
     */
    fun mimeType(fileName: String): String {
        val extension = fileName.lowercase().substringAfterLast('.', "")
        return mimeByExtension[extension] ?: "application/octet-stream"
    }

    /** A direct Play link where an appropriate application exists (§7). */
    fun suggestionFor(mimeType: String): InstallSuggestion? = when {
        mimeType == "application/epub+zip" ->
            InstallSuggestion(R.string.open_app_moonreader, MOON_READER)
        mimeType.startsWith("audio/") ->
            InstallSuggestion(R.string.open_app_foobar2000, FOOBAR2000)
        else -> null
    }

    /** A Play search for file types without a known dedicated application. */
    fun searchUrlFor(mimeType: String): String {
        val term = when {
            mimeType == "application/pdf" -> "pdf reader"
            mimeType == "text/markdown" -> "markdown viewer"
            mimeType == "text/plain" -> "text editor"
            mimeType == "text/html" -> "web browser"
            mimeType.startsWith("audio/") -> "audio player"
            mimeType.startsWith("image/") -> "image viewer"
            mimeType == "application/epub+zip" ||
                mimeType == "application/x-mobipocket-ebook" ||
                mimeType == "application/vnd.amazon.ebook" ||
                mimeType == "application/x-fictionbook+xml" -> "ebook reader"
            mimeType.startsWith("application/vnd.comicbook") -> "comic reader"
            else -> "file viewer"
        }
        return "https://play.google.com/store/search?q=" +
            URLEncoder.encode(term, "UTF-8") + "&c=apps"
    }
}

/** The result of an open attempt: either an application started or none exists. */
sealed interface OpenOutcome {

    /** An external application was started with a granted temporary URI permission. */
    data object Started : OpenOutcome

    /** No installed application resolves the intent (§7: handle gracefully). */
    data class MissingHandler(val mimeType: String, val suggestion: InstallSuggestion?) :
        OpenOutcome
}

/**
 * Opens synchronized content with external applications: a FileProvider
 * content URI (private filesystem paths are never exposed, §7) plus the
 * suitable MIME type, started only when the intent actually resolves.
 *
 * §7, §11: 26: opening a file must never fail silently. Resolution happens at
 * tap time, and even a resolved start can still fail (stale resolver state,
 * revoked permissions) — any failure surfaces the missing-handler dialog, the
 * generic "no application found" one when no suggestion applies.
 */
class FileOpener(private val context: Context) {

    fun open(file: File): OpenOutcome {
        val mimeType = OpenWith.mimeType(file.name)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val handlers = context.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        if (handlers.isEmpty()) {
            return OpenOutcome.MissingHandler(mimeType, OpenWith.suggestionFor(mimeType))
        }
        return try {
            context.startActivity(intent)
            OpenOutcome.Started
        } catch (_: ActivityNotFoundException) {
            OpenOutcome.MissingHandler(mimeType, OpenWith.suggestionFor(mimeType))
        } catch (_: SecurityException) {
            OpenOutcome.MissingHandler(mimeType, OpenWith.suggestionFor(mimeType))
        }
    }
}
