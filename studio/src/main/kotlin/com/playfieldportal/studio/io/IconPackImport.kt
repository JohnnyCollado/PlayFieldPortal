package com.playfieldportal.studio.io

import com.playfieldportal.core.archive.BoundedZipReader
import com.playfieldportal.core.archive.ZipLimitExceededException
import com.playfieldportal.core.archive.ZipLimits
import com.playfieldportal.studio.EditableSlots
import com.playfieldportal.themekit.PfpThemeCodec
import java.io.File
import java.nio.file.Files

/** One candidate file of a pack: [name] is its pack-relative path (for the report), [bytes] its content. */
class PackFile(val name: String, val bytes: ByteArray)

/** A png/gif that matches no slot; [suggestion] is the closest slot key when one is plausibly a typo. */
data class PackUnmatched(val name: String, val suggestion: String?)

/** A file that was not imported, with the human-readable why. */
data class PackRejected(val name: String, val reason: String)

/**
 * Result of reading a pack, before any slot is touched. Every file seen lands in exactly one of
 * [matched], [unmatched] or [rejected], so `fileCount == matched.size + unmatched.size + rejected.size`
 * unless [error] refused the whole pack (then [matched] is empty — a half-read archive is not applied).
 */
class PackScan(
    val matched: Map<String, PackFile>,
    val unmatched: List<PackUnmatched>,
    val rejected: List<PackRejected>,
    val fileCount: Int,
    val error: String?,
)

/** What an import did, for the UI: slot keys filled for the first time, replaced, and what was left out. */
data class IconPackReport(
    val source: String,
    val added: List<String>,
    val replaced: List<String>,
    val unmatched: List<PackUnmatched>,
    val rejected: List<PackRejected>,
    val error: String? = null,
)

/**
 * Reads an icon pack — a folder or a `.zip` of `<slotKey>.<png|gif>` files, the names the template
 * export writes — and matches files to [EditableSlots] keys.
 *
 * Nothing is ever extracted to disk: zip entries are inflated into memory through
 * [BoundedZipReader] (entry count / per-entry / total caps), and the entry NAME is only ever used
 * as a lookup string, so zip slip has nothing to land on. Names that try to escape (`..`, absolute,
 * drive-prefixed, control characters) are still rejected and reported rather than silently matched
 * on their last segment. Per-icon caps (bytes, GIF frames/size/duration) are NOT decided here: the
 * view model runs every match through the same gate as a single-icon import.
 */
object IconPackImport {

    private const val MAX_FILES = 512
    private const val MAX_DEPTH = 3
    private val EXTENSIONS = setOf("png", "gif")

    /** Lower-cased key → canonical key. File names match case-insensitively, keys are stored lower-case. */
    private val keysByLower: Map<String, String> = EditableSlots.ALL.associate { it.key.lowercase() to it.key }

    private val ZIP_LIMITS = ZipLimits(
        maxEntries = MAX_FILES,
        maxEntryBytes = PfpThemeCodec.MAX_ICON_BYTES.toLong(),
        maxTotalBytes = 64L * 1024 * 1024,
    )

    fun scan(source: File): PackScan {
        val collector = Collector()
        val error = runCatching {
            when {
                source.isDirectory -> scanFolder(source, collector)
                source.isFile -> scanZip(source, collector)
                else -> throw IllegalArgumentException("${source.name} is not a folder or .zip")
            }
        }.exceptionOrNull()?.let(::describe)
        // A refused archive applies nothing: partial packs would be a surprise on undo-less retries.
        return collector.finish(error)
    }

    private fun describe(e: Throwable): String = when (e) {
        is ZipLimitExceededException -> "Pack refused: ${e.message}"
        is IllegalArgumentException -> e.message ?: "Pack could not be read"
        else -> "Pack could not be read: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun scanFolder(dir: File, collector: Collector) {
        val walk = dir.walkTopDown()
            .maxDepth(MAX_DEPTH)
            // Never descend through a linked sub-folder; the picked root itself may be a link.
            .onEnter { it == dir || !Files.isSymbolicLink(it.toPath()) }
        for (file in walk.filter { it.isFile || Files.isSymbolicLink(it.toPath()) }) {
            val name = file.relativeTo(dir).invariantSeparatorsPath
            if (collector.count >= MAX_FILES) throw IllegalArgumentException("Pack has more than $MAX_FILES files")
            if (Files.isSymbolicLink(file.toPath())) {
                collector.reject(name, "links are not followed")
                continue
            }
            collector.offer(name) {
                if (file.length() > PfpThemeCodec.MAX_ICON_BYTES) null else file.readBytes()
            }
        }
    }

    private fun scanZip(zip: File, collector: Collector) {
        zip.inputStream().use { input ->
            BoundedZipReader.read(input, ZIP_LIMITS) { entry ->
                if (!entry.isDirectory) collector.offer(entry.name) { entry.readBytes() }
            }
        }
    }

    private class Collector {
        val matched = LinkedHashMap<String, PackFile>()
        val unmatched = mutableListOf<PackUnmatched>()
        val rejected = mutableListOf<PackRejected>()
        var count = 0
            private set

        fun reject(name: String, reason: String) {
            count++
            rejected += PackRejected(name, reason)
        }

        /** Classifies one file; [read] is only invoked for a file that will actually be kept, and returns null for "too large". */
        fun offer(rawName: String, read: () -> ByteArray?) {
            val name = rawName.replace('\\', '/')
            if (!isSafeName(name)) return reject(rawName.printable(), "unsafe file name")
            val base = name.substringAfterLast('/')
            if (base.startsWith(".")) return reject(name, "hidden or metadata file")
            val ext = base.substringAfterLast('.', "").lowercase()
            if (ext !in EXTENSIONS) return reject(name, "not a png or gif")
            val stem = base.substringBeforeLast('.')
            val key = keysByLower[stem.lowercase()]
            if (key == null) {
                count++
                unmatched += PackUnmatched(name, nearestKey(stem.lowercase()))
                return
            }
            matched[key]?.let { return reject(name, "duplicate of ${it.name} for $key") }
            val bytes = read() ?: return reject(name, "over the ${PfpThemeCodec.MAX_ICON_BYTES / (1024 * 1024)} MB icon limit")
            count++
            matched[key] = PackFile(name, bytes)
        }

        fun finish(error: String?) = PackScan(
            matched = if (error == null) matched else emptyMap(),
            unmatched = unmatched,
            rejected = rejected,
            fileCount = count,
            error = error,
        )
    }

    /** Relative, no `..` segment, no drive prefix, no control characters. */
    private fun isSafeName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith("/")) return false
        if (name.length >= 2 && name[1] == ':') return false
        if (name.any { it.isISOControl() }) return false
        return name.split('/').none { it == ".." || it.isEmpty() }
    }

    /** Names are attacker-controlled; keep control characters out of the report text. */
    private fun String.printable(): String = map { if (it.isISOControl()) '?' else it }.joinToString("")

    private fun nearestKey(stem: String): String? {
        val threshold = (stem.length / 4).coerceIn(1, 3)
        var best: String? = null
        var bestDistance = threshold + 1
        for ((lower, key) in keysByLower) {
            val d = levenshtein(stem, lower, bestDistance)
            if (d < bestDistance) { best = key; bestDistance = d }
        }
        return best
    }

    private fun levenshtein(a: String, b: String, cutoff: Int): Int {
        if (kotlin.math.abs(a.length - b.length) >= cutoff) return cutoff
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
