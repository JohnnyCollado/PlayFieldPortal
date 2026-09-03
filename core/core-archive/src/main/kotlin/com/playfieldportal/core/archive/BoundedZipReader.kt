package com.playfieldportal.core.archive

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * Caps applied to one archive read.
 *
 * The defaults are the union of what the three call sites used to enforce separately — the theme
 * loader capped entry count and total size, the theme codec capped per-entry size, and the backup
 * reader capped nothing. Taking the union makes the strongest reader the floor rather than the
 * exception.
 */
data class ZipLimits(
    /** Entries examined before the archive is refused outright. */
    val maxEntries: Int = 512,
    /** Inflated bytes allowed for any single entry. */
    val maxEntryBytes: Long = 32L * 1024 * 1024,
    /** Inflated bytes allowed across the whole archive. */
    val maxTotalBytes: Long = 128L * 1024 * 1024,
)

/** Why an archive was refused. Carried on [ZipLimitExceededException] so callers can report it. */
sealed interface ZipRejection {
    data class TooManyEntries(val limit: Int) : ZipRejection
    data class EntryTooLarge(val name: String, val limit: Long) : ZipRejection
    data class ArchiveTooLarge(val limit: Long) : ZipRejection
}

class ZipLimitExceededException(val rejection: ZipRejection) : IOException(
    when (rejection) {
        is ZipRejection.TooManyEntries -> "archive has more than ${rejection.limit} entries"
        is ZipRejection.EntryTooLarge ->
            "entry '${rejection.name}' exceeds ${rejection.limit} bytes when inflated"
        is ZipRejection.ArchiveTooLarge -> "archive exceeds ${rejection.limit} inflated bytes"
    },
)

/**
 * One entry, handed to the visitor while the underlying stream is positioned on it. The content is
 * readable only inside the callback; [readBytes] and [copyTo] are both capped, so a caller cannot
 * opt out of the limits by picking the other one.
 */
class BoundedZipEntry internal constructor(
    val name: String,
    val isDirectory: Boolean,
    private val source: InputStream,
    private val limits: ZipLimits,
    private val budget: ArchiveBudget,
) {
    private var consumed = false

    /** Inflates this entry into memory, refusing anything past the per-entry cap. */
    fun readBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        copyTo(out)
        return out.toByteArray()
    }

    /**
     * Streams this entry out, counting as it goes. The count comes from the copy rather than from
     * the ZIP header, because a declared size is attacker-controlled and is frequently absent (-1)
     * on a streamed archive.
     */
    fun copyTo(out: OutputStream): Long {
        check(!consumed) { "entry '$name' has already been read" }
        consumed = true
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var entryTotal = 0L
        while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            entryTotal += read
            if (entryTotal > limits.maxEntryBytes) {
                throw ZipLimitExceededException(ZipRejection.EntryTooLarge(name, limits.maxEntryBytes))
            }
            budget.spend(read.toLong(), limits)
            out.write(buffer, 0, read)
        }
        return entryTotal
    }

    /** Skips this entry without materialising it. */
    internal fun drain() {
        if (consumed) return
        copyTo(OutputStream.nullOutputStream())
    }
}

/** Running total across one archive, so per-entry caps cannot be summed past the archive cap. */
class ArchiveBudget internal constructor() {
    private var total = 0L

    internal fun spend(bytes: Long, limits: ZipLimits) {
        total += bytes
        if (total > limits.maxTotalBytes) {
            throw ZipLimitExceededException(ZipRejection.ArchiveTooLarge(limits.maxTotalBytes))
        }
    }
}

/**
 * The single ZIP reader for this project.
 *
 * It exists because the same ingestion policy — zip slip, entry counts, decompression bombs — was
 * reimplemented three times at three different quality levels, so a fix to one never reached the
 * others. Callers supply only a name policy (what to do with each entry); the bounds are not
 * theirs to choose or to forget.
 *
 * Throws [ZipLimitExceededException] the moment a cap is crossed, having inflated no more than the
 * cap allows. Path safety is deliberately separate — see [SafeArchivePath] — because "where may
 * this entry be written" depends on the caller's root while "how big may it be" does not.
 */
object BoundedZipReader {

    fun read(
        source: InputStream,
        limits: ZipLimits = ZipLimits(),
        onEntry: (BoundedZipEntry) -> Unit,
    ) {
        val budget = ArchiveBudget()
        var count = 0
        ZipInputStream(source.buffered()).use { zip ->
            var raw = zip.nextEntry
            while (raw != null) {
                count++
                if (count > limits.maxEntries) {
                    throw ZipLimitExceededException(ZipRejection.TooManyEntries(limits.maxEntries))
                }
                val entry = BoundedZipEntry(
                    name = raw.name,
                    isDirectory = raw.isDirectory,
                    // Non-closing: a visitor that wraps the stream in use {} must not close the
                    // shared ZipInputStream half way through the archive.
                    source = NonClosingInputStream(zip),
                    limits = limits,
                    budget = budget,
                )
                onEntry(entry)
                // An entry the visitor ignored still has to be paid for, so a bomb cannot hide
                // behind a name filter.
                if (!raw.isDirectory) entry.drain()
                zip.closeEntry()
                raw = zip.nextEntry
            }
        }
    }
}

private class NonClosingInputStream(private val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun close() = Unit
}

/**
 * Where an archive entry is allowed to land.
 *
 * Kept apart from the reader because the two questions have different owners: the reader decides
 * how much may be inflated, the caller decides which directory is legitimate. Both helpers return
 * a value rather than a boolean — a `File?` cannot be used without acknowledging the null, whereas
 * a boolean check is easy to call and then ignore, which is exactly how the restore path lost its
 * confinement.
 */
object SafeArchivePath {

    /**
     * Resolves [relative] under [root], or null if it escapes. Canonical paths are compared, so
     * symlinks and ".." segments are both covered; an absolute [relative] is refused outright,
     * because `File(root, "/etc/passwd")` does not mean what it looks like on every platform.
     */
    fun resolveWithin(root: File, relative: String): File? {
        val cleaned = relative.trim()
        if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") return null
        if (File(cleaned).isAbsolute || cleaned.startsWith("/") || cleaned.startsWith("\\")) return null
        // A Windows drive-relative name ("C:foo") is absolute in intent but not by isAbsolute.
        if (cleaned.length >= 2 && cleaned[1] == ':') return null

        val rootCanonical = root.canonicalPath
        val candidate = File(root, cleaned)
        val candidateCanonical = candidate.canonicalPath
        if (candidateCanonical == rootCanonical) return null
        if (!candidateCanonical.startsWith(rootCanonical + File.separator)) return null
        return candidate
    }

    /**
     * [resolveWithin], additionally requiring the first path segment to be one of [roots].
     *
     * This is the check the backup restore was missing. Confinement to `filesDir` alone is not
     * enough there: the DataStore file sits inside `filesDir` too, and overwriting it corrupts the
     * live preference store into a crash loop. The declared roots are the only sub-trees a backup
     * actually owns.
     */
    fun resolveWithinRoots(base: File, relative: String, roots: List<String>): File? {
        val resolved = resolveWithin(base, relative) ?: return null
        val rel = resolved.canonicalFile.relativeTo(base.canonicalFile).invariantSeparatorsPath
        val firstSegment = rel.substringBefore('/')
        // Whole-segment match: "artwork_evil/x" must not pass just because it starts with "artwork".
        if (firstSegment !in roots) return null
        // A file sitting at the root name itself, rather than inside it, is not a root member.
        if (rel == firstSegment) return null
        return resolved
    }
}
