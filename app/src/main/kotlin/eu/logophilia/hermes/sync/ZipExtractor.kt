package eu.logophilia.hermes.sync

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * A rejected or failed ZIP extraction (§5 Phase D). Part of the failure
 * family surfaced by the synchronization engine: the attempt fails, existing
 * content is retained, Temp is discarded.
 */
class ZipExtractionException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Extraction of a checksum-verified ZIP archive (§5 Phase D): preserves the
 * archive's internal directory structure, applies ZIP Slip protection (entries
 * that would write outside the destination are rejected) and reasonable
 * limits on entry count and total extracted size against resource
 * exhaustion. Extraction completes into a caller-chosen directory — always
 * under Temp — before anything live is touched; the swap is the caller's job.
 */
class ZipExtractor(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxTotalUncompressedBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) {

    fun extract(archive: File, destination: File) {
        try {
            ZipFile(archive).use { zip ->
                destination.mkdirs()
                val destinationPath = destination.canonicalPath + File.separator
                var totalUncompressed = 0L
                var entryCount = 0
                val entries = zip.entries()
                val chunk = ByteArray(COPY_CHUNK_BYTES)
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement() as ZipEntry
                    entryCount++
                    if (entryCount > maxEntries) {
                        throw ZipExtractionException(
                            "archive \"${archive.name}\" has more than $maxEntries entries",
                        )
                    }
                    val name = entry.name
                    if (name.isBlank()) {
                        throw ZipExtractionException("archive \"${archive.name}\" has a blank entry name")
                    }
                    val target = File(destination, name)
                    // ZIP Slip protection (§5 Phase D 3): every entry must
                    // land strictly inside the extraction destination.
                    if (!target.canonicalPath.startsWith(destinationPath)) {
                        throw ZipExtractionException(
                            "entry \"$name\" escapes the extraction destination (ZIP Slip)",
                        )
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output ->
                            while (true) {
                                val read = input.read(chunk)
                                if (read < 0) break
                                totalUncompressed += read
                                if (totalUncompressed > maxTotalUncompressedBytes) {
                                    throw ZipExtractionException(
                                        "archive \"${archive.name}\" exceeds the extracted-size limit",
                                    )
                                }
                                output.write(chunk, 0, read)
                            }
                        }
                    }
                }
            }
        } catch (e: ZipExtractionException) {
            throw e
        } catch (e: IOException) {
            throw ZipExtractionException(
                "failed to extract \"${archive.name}\": ${e.message ?: e.javaClass.simpleName}",
                e,
            )
        }
    }

    companion object {
        /** Generous limits; OST archives are tens of megabytes. */
        const val DEFAULT_MAX_ENTRIES = 10_000
        const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

        private const val COPY_CHUNK_BYTES = 64 * 1024
    }
}
