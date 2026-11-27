package eu.logophilia.hermes.ui

import eu.logophilia.hermes.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Offline tests of the §7 mapping: file type → MIME type, and the concrete
 * installation suggestions with their verified Play links.
 */
class OpenWithTest {

    @Test
    fun `spec §7 file types map to their MIME types`() {
        assertEquals("application/epub+zip", OpenWith.mimeType("Book.epub"))
        assertEquals("audio/opus", OpenWith.mimeType("Track.opus"))
        assertEquals("audio/mpeg", OpenWith.mimeType("Track.mp3"))
        assertEquals("audio/flac", OpenWith.mimeType("Track.flac"))
        assertEquals("text/plain", OpenWith.mimeType("Notes.txt"))
        assertEquals("text/markdown", OpenWith.mimeType("Notes.md"))
        assertEquals("text/html", OpenWith.mimeType("Page.html"))
        assertEquals("text/html", OpenWith.mimeType("Page.HTM"))
        assertEquals("application/pdf", OpenWith.mimeType("Paper.pdf"))
        // §7, added in v1.1 (§11: 26): image files open in a viewer.
        assertEquals("image/jpeg", OpenWith.mimeType("cover.jpg"))
        assertEquals("image/jpeg", OpenWith.mimeType("cover.JPEG"))
        assertEquals("image/png", OpenWith.mimeType("cover.png"))
        assertEquals("image/webp", OpenWith.mimeType("cover.webp"))
        assertEquals("image/gif", OpenWith.mimeType("cover.gif"))
        // §10 update channel: the app's own package opens the installer.
        assertEquals(
            "application/vnd.android.package-archive",
            OpenWith.mimeType("LogophiliaHermes-0.0.1.apk"),
        )
        assertEquals(
            "application/vnd.android.package-archive",
            OpenWith.mimeType("LogophiliaHermes-0.0.1.APK"),
        )
    }

    @Test
    fun `unknown types are left to Android as generic binary data`() {
        assertEquals("application/octet-stream", OpenWith.mimeType("no-extension"))
        assertEquals("application/octet-stream", OpenWith.mimeType("Archive.7z"))
    }

    @Test
    fun `matching is case-insensitive and uses the last extension`() {
        assertEquals("application/epub+zip", OpenWith.mimeType("BOOK.EPUB"))
        assertEquals("text/plain", OpenWith.mimeType("archive.tar.gz.txt"))
    }

    @Test
    fun `epub resolves to Moon+ Reader with the §7 Play link`() {
        val suggestion = OpenWith.suggestionFor("application/epub+zip")

        assertEquals(R.string.open_app_moonreader, suggestion?.appNameRes)
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.flyersoft.moonreader",
            suggestion?.playUrl,
        )
    }

    @Test
    fun `audio resolves to foobar2000 with the verified Play link`() {
        // §12.2: package and link verified against the developer's own
        // Play redirect (foobar2000.org/get-android) on 2026-11-27.
        val suggestion = OpenWith.suggestionFor("audio/mpeg")

        assertEquals(R.string.open_app_foobar2000, suggestion?.appNameRes)
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.foobar2000.foobar2000",
            suggestion?.playUrl,
        )
        assertEquals(
            "https://play.google.com/store/apps/details?id=com.foobar2000.foobar2000",
            OpenWith.suggestionFor("audio/opus")?.playUrl,
        )
    }

    @Test
    fun `types without a known dedicated application get no direct suggestion`() {
        assertNull(OpenWith.suggestionFor("application/pdf"))
        assertNull(OpenWith.suggestionFor("text/plain"))
        assertNull(OpenWith.suggestionFor("application/octet-stream"))
        // Images have no dedicated suggestion (§7): they get the generic
        // "no application found" dialog with a store search instead.
        assertNull(OpenWith.suggestionFor("image/jpeg"))
        assertNull(OpenWith.suggestionFor("image/png"))
        // The package installer is the OS, not a Play suggestion (§10).
        assertNull(OpenWith.suggestionFor("application/vnd.android.package-archive"))
    }

    @Test
    fun `generic fallbacks are Play searches`() {
        assertEquals(
            "https://play.google.com/store/search?q=pdf+reader&c=apps",
            OpenWith.searchUrlFor("application/pdf"),
        )
        assertEquals(
            "https://play.google.com/store/search?q=image+viewer&c=apps",
            OpenWith.searchUrlFor("image/jpeg"),
        )
        // Only reached when no installer resolves at all (§10) — on stock
        // Android the platform installer always handles .apk.
        assertEquals(
            "https://play.google.com/store/search?q=app+installer&c=apps",
            OpenWith.searchUrlFor("application/vnd.android.package-archive"),
        )
        assertTrue(OpenWith.searchUrlFor("application/octet-stream").endsWith("&c=apps"))
    }
}
