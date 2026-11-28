package eu.logophilia.hermes.sync

import eu.logophilia.hermes.api.ApiException
import eu.logophilia.hermes.api.ManifestEntry
import eu.logophilia.hermes.api.SyncClient
import java.io.File
import java.security.MessageDigest

/** The five-minute cooldown between attempts, regardless of outcome (§4). */
const val COOLDOWN_MILLIS = 5L * 60 * 1000

/** §5 Phase C 1: the per-commit gate re-check refused — the attempt stops. */
internal const val NETWORK_GATE_LOST_MESSAGE =
    "No longer on a validated unmetered network — synchronization stopped"

/**
 * Progress of a running synchronization, shown by the sync indicator (§8):
 * blue while the manifest request waits for the server; green while content
 * flows — every required file is downloaded (TRANSFERRING) and committed
 * immediately (COMMITTING, §5 Phase C–D) — and the attempt finishes with the
 * obsolete-content cleanup under the last COMMITTING signal.
 */
enum class SyncPhase {
    MANIFEST_REQUESTED,
    TRANSFERRING,
    COMMITTING,
}

/** A synchronization failure carrying a message fit for display (§8, §9). */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The outcome of one [SyncEngine.attempt]. Failures never touch existing
 * content; skips mean the API was not contacted at all (§4, §9).
 */
sealed class SyncOutcome {

    /** Phase E reached the end successfully. */
    data class Success(
        val added: Int,
        val updated: Int,
        val removed: Int,
    ) : SyncOutcome()

    /** The attempt started and failed; existing content is untouched. */
    data class Failure(val message: String, val cause: Throwable? = null) : SyncOutcome()

    /** No attempt began; the API was not contacted. */
    sealed class Skipped : SyncOutcome() {
        /** No validated unmetered network (§4 Network gate). */
        object NoQualifyingNetwork : Skipped()

        /** The five-minute cooldown is still running (§4). */
        data class CoolingDown(val remainingMillis: Long) : Skipped()

        /** A synchronization operation is already running (single-flight, §4). */
        object AlreadyRunning : Skipped()
    }
}

/** SHA-256 of a whole file, hex-encoded (used only for sidecar self-heal, §5 Phase B). */
internal fun sha256Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val chunk = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            digest.update(chunk, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * Reads a sidecar or marker (64 hex characters), or null when absent,
 * unreadable, or implausibly large. Corrupted metadata behaves as missing
 * metadata: the entry is treated as not current and re-fetched.
 */
internal fun File.readMetadataOrNull(): String? {
    if (!isFile) return null
    if (length() > ContentLayout.MAX_METADATA_BYTES) return null
    return try {
        readText().trim().ifEmpty { null }
    } catch (_: Exception) {
        null
    }
}

/**
 * The synchronization engine (SPECIFICATION.md §4 and §5).
 *
 * Blocking; never call it on the Android main thread. One instance belongs to
 * the application. The remote manifest is authoritative; verification of the
 * downloaded files is the only hashing performed for unchanged content (§5).
 *
 * Invariants (§9): existing valid content stays usable offline; content is
 * never deleted because an API request failed; valid content is never
 * overwritten by an unverified file; operations never run concurrently; the
 * cooldown is respected in every failure scenario.
 */
class SyncEngine(
    private val client: SyncClient,
    private val layout: ContentLayout,
    private val store: SyncStore,
    private val networkGate: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val extractor: ZipExtractor = ZipExtractor(),
) {

    private val lock = Any()

    @Volatile
    private var running = false

    /**
     * Observes the progress of the running attempt (§8 Status indicators).
     * Called on the engine's worker thread; set once at wiring time. A
     * consecutive equal signal is skipped: phases are state transitions.
     */
    @Volatile
    var phaseListener: ((SyncPhase) -> Unit)? = null

    @Volatile
    private var lastPhase: SyncPhase? = null

    private fun phase(p: SyncPhase) {
        if (lastPhase == p) return
        lastPhase = p
        phaseListener?.invoke(p)
    }

    /**
     * Attempts one synchronization (§4). Returns without any network traffic
     * when no qualifying network exists, when the cooldown is running, or
     * when an operation is already in flight.
     */
    fun attempt(): SyncOutcome {
        // Single-flight (§4): the preflight decisions run under the lock, the
        // attempt itself runs outside it; a concurrent caller is skipped at
        // once, never queued and never run twice.
        synchronized(lock) {
            if (running) return SyncOutcome.Skipped.AlreadyRunning
            if (!networkGate()) return SyncOutcome.Skipped.NoQualifyingNetwork
            val now = clock()
            val last = store.lastSyncAttemptMillis()
            if (last != null && now - last < COOLDOWN_MILLIS) {
                return SyncOutcome.Skipped.CoolingDown(COOLDOWN_MILLIS - (now - last))
            }
            // The timestamp is written when an actual attempt begins, whatever
            // its outcome will be (§4 Five-minute cooldown).
            store.writeLastSyncAttempt(now)
            running = true
        }
        return try {
            synchronize()
        } finally {
            running = false
        }
    }

    /** Last attempt time as persisted by [SyncStore], for the Sync screen (§8). */
    fun lastSyncAttemptMillis(): Long? = store.lastSyncAttemptMillis()

    /** Milliseconds until a new attempt is permitted, 0 when none is running. */
    fun cooldownRemainingMillis(now: Long = clock()): Long {
        val last = store.lastSyncAttemptMillis() ?: return 0L
        return (COOLDOWN_MILLIS - (now - last)).coerceAtLeast(0L)
    }

    // ---------------------------------------------------------------------
    // Phases (§5)
    // ---------------------------------------------------------------------

    private fun synchronize(): SyncOutcome = try {
        layout.createDirectories()
        val entries = run {
            phase(SyncPhase.MANIFEST_REQUESTED)       // §8: blue while the server waits
            val fetched = client.fetchManifest()      // Phase A
            phase(SyncPhase.TRANSFERRING)             // §8: green from here on
            fetched.distinctBy { it.name }            // defensive: first entry wins
        }
        val work = plan(entries)                      // Phase B
        val downloads = File(layout.temp, ContentLayout.TEMP_DOWNLOADS)
        val extracts = File(layout.temp, ContentLayout.TEMP_EXTRACT)
        downloadAndCommitEach(work, downloads, extracts) // Phase C–D, file by file
        phase(SyncPhase.COMMITTING)   // obsolete-content cleanup (§5 Phase E)
        val removed = cleanup(work)   // Phase E 1–3
        SyncOutcome.Success(added = work.addedCount, updated = work.updatedCount, removed = removed)
    } catch (e: Exception) {
        SyncOutcome.Failure(describe(e), e)
    } finally {
        layout.wipeTemp() // Phase E 4: Temp is discarded whatever happened (§4, §5)
    }

    /** Phase B — identify required work without touching existing content. */
    internal fun plan(entries: List<ManifestEntry>): Work {
        val required = mutableListOf<ManifestEntry>()
        val placements = mutableMapOf<String, Placement>()
        var added = 0
        for (entry in entries) {
            val placement = ContentClassifier.classify(entry.name)
            placements[entry.name] = placement
            val target = targetOf(placement, entry.name)
            val current = when (placement) {
                is Placement.Plain -> {
                    if (!target.isFile) {
                        false
                    } else {
                        // Currency ⟺ the sidecar matches the manifest sha256;
                        // a missing sidecar self-heals by hashing once (§5 Phase B).
                        val known = layout.sidecarOf(target).readMetadataOrNull()
                        val hash = known ?: sha256Of(target)
                            .also { layout.sidecarOf(target).writeText(it) }
                        hash == entry.sha256
                    }
                }
                is Placement.Archive ->
                    // Currency ⟺ the extracted directory carries the marker
                    // named after (and containing) the manifest sha256 (§5 Phase B).
                    target.isDirectory &&
                        File(target, "${entry.sha256}.hash").readMetadataOrNull() == entry.sha256
            }
            if (!current) {
                required += entry
                if (!target.exists()) added++
            }
        }
        return Work(entries, required, placements, added)
    }

    private fun targetOf(placement: Placement, name: String): File = when (placement) {
        is Placement.Plain -> File(layout.dirFor(placement.category), name)
        is Placement.Archive -> File(layout.dirFor(placement.category), placement.baseName)
    }

    /**
     * Phases C–D, merged: each required file is downloaded, verified, and
     * committed in turn, smallest first (§5 Phase C) — the Library fills
     * visibly from the first seconds and small content is openable while
     * the big archives still stream. A failure — or a network-gate flip —
     * stops the attempt while keeping every file already committed (§5
     * Phase E): those files match the manifest and are recognized as
     * unchanged by the next attempt (§5 Phase B).
     */
    private fun downloadAndCommitEach(work: Work, downloads: File, extracts: File) {
        for (entry in work.required.sortedBy { it.size }) {
            // §5 Phase C 1: the gate is re-checked before every file commit —
            // a "temporarily not metered" network may flip back mid-attempt
            // (§4 Network gate). Stopping keeps committed work, deletes
            // nothing, and marks the attempt failed (§5 Phase E).
            if (!networkGate()) {
                throw SyncException(NETWORK_GATE_LOST_MESSAGE)
            }
            phase(SyncPhase.TRANSFERRING)             // §8: green while content flows
            val downloaded = client.download(entry.name, File(downloads, entry.name))
            // The checksum applies to the complete original file content (§5);
            // size is re-checked too because /download is chunked and the
            // client cannot rely on a Content-Length header.
            if (downloaded.sha256 != entry.sha256) {
                throw SyncException(
                    "Checksum mismatch for \"${entry.name}\": downloaded ${downloaded.sha256}, manifest says ${entry.sha256}",
                )
            }
            if (downloaded.size != entry.size) {
                throw SyncException(
                    "Size mismatch for \"${entry.name}\": downloaded ${downloaded.size} bytes, manifest says ${entry.size}",
                )
            }
            phase(SyncPhase.COMMITTING)               // §8: this verified file commits now
            commit(work.placements.getValue(entry.name), entry, downloads, extracts)
        }
    }

    /**
     * Commits one verified file immediately (§5 Phase C 4). Never called with
     * an unverified download: verification has just succeeded (§9).
     */
    private fun commit(placement: Placement, entry: ManifestEntry, downloads: File, extracts: File) {
        when (placement) {
            is Placement.Plain -> {
                // Move the verified file into its final location and write the
                // sidecar from the manifest value (§5 Phase C 4).
                val target = File(layout.dirFor(placement.category), entry.name)
                move(File(downloads, entry.name), target)
                layout.sidecarOf(target).writeText(entry.sha256)
            }

            is Placement.Archive -> {
                // Full ZIP handling of Phase D within this commit step:
                // extract into Temp, swap, marker, delete the archive (§5 Phase D).
                val target = File(layout.dirFor(placement.category), placement.baseName)
                val extracted = File(extracts, placement.baseName)
                extractor.extract(File(downloads, entry.name), extracted)
                if (target.exists()) target.deleteRecursively()
                move(extracted, target)
                File(target, "${entry.sha256}.hash").writeText(entry.sha256)
                // The archive itself is never retained (§5 Phase D 6).
                File(downloads, entry.name).delete()
            }
        }
    }

    /**
     * Deletes files, sidecars, and extracted directories that no longer exist
     * in the authoritative manifest. Never called on a failed attempt (§5 Phase E).
     */
    private fun cleanup(work: Work): Int {
        val plainByDir = mutableMapOf<File, MutableSet<String>>()
        val archiveByDir = mutableMapOf<File, MutableSet<String>>()
        for (entry in work.entries) {
            when (val placement = work.placements[entry.name]) {
                is Placement.Plain ->
                    plainByDir.getOrPut(layout.dirFor(placement.category)) { mutableSetOf() } += entry.name
                is Placement.Archive ->
                    archiveByDir.getOrPut(layout.dirFor(placement.category)) { mutableSetOf() } += placement.baseName
                null -> {}
            }
        }
        var removed = 0
        for (dir in layout.categoryDirectories) {
            val plainNames = plainByDir[dir].orEmpty()
            val archiveNames = archiveByDir[dir].orEmpty()
            for (child in dir.listFiles().orEmpty()) {
                when {
                    child.isDirectory -> if (child.name !in archiveNames) {
                        child.deleteRecursively()
                        removed++
                    }
                    // An expected plain file — checked before the sidecar
                    // heuristic, since a manifest file may itself end in .hash.
                    child.name in plainNames -> {}
                    child.name.endsWith(ContentLayout.SIDECAR_SUFFIX) -> {
                        // A sidecar or a stray marker at directory root;
                        // it survives only while its counterpart does.
                        val base = child.name.removeSuffix(ContentLayout.SIDECAR_SUFFIX)
                        if (base !in plainNames) child.delete()
                    }
                    else -> {
                        child.delete()
                        layout.sidecarOf(child).takeIf { it.isFile }?.delete()
                        removed++
                    }
                }
            }
        }
        return removed
    }

    /** Atomic when possible: rename within the same volume, copy as fallback. */
    private fun move(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return
        if (source.isDirectory) {
            source.copyRecursively(target, overwrite = true)
            source.deleteRecursively()
        } else {
            source.copyTo(target, overwrite = true)
            source.delete()
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException ->
            "${e.javaClass.simpleName} API failure: ${e.message ?: "no detail"}"
        is ZipExtractionException -> e.message ?: "ZIP extraction failed"
        is SyncException -> e.message ?: "synchronization failed"
        else -> "${e.javaClass.simpleName}: ${e.message ?: "unexpected failure"}"
    }
}

/** The internal work plan of one attempt (§5 Phase B). */
internal class Work(
    val entries: List<ManifestEntry>,
    val required: List<ManifestEntry>,
    val placements: Map<String, Placement>,
    val addedCount: Int,
) {
    val updatedCount: Int = required.size - addedCount
}
