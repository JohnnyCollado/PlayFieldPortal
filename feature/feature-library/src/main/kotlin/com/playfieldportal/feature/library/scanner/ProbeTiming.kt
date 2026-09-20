package com.playfieldportal.feature.library.scanner

import java.util.concurrent.atomic.LongAdder

/**
 * Where a cold scan's per-file time actually goes.
 *
 * A first scan of a large library is dominated by per-file work, but "per-file work" is three
 * very different things — opening and parsing the container, pulling embedded artwork, and
 * writing the artwork cache — and they have completely different fixes. Guessing which one
 * dominates is how you spend a day optimising the wrong one, so the scanners measure it and
 * report it on the completion line.
 *
 * [LongAdder] rather than `AtomicLong` because probing runs concurrently and these are
 * write-heavy, read-once counters — exactly what it is designed for.
 *
 * Note the totals are CPU-time-across-workers, not wall time: with probing parallelised they add
 * up to more than the scan took. Read them as proportions, not as a budget.
 */
internal class ProbeTiming {

    private val metadataNs = LongAdder()
    private val artworkNs = LongAdder()
    private val artCacheNs = LongAdder()
    private val probed = LongAdder()

    /** Opening the file and reading its tags — [android.media.MediaMetadataRetriever] setDataSource + extract. */
    fun addMetadata(startNs: Long) = metadataNs.add(System.nanoTime() - startNs)

    /** Pulling the embedded picture out of the already-open file. */
    fun addArtwork(startNs: Long) = artworkNs.add(System.nanoTime() - startNs)

    /** Hashing and writing the artwork/thumbnail cache file. */
    fun addArtCache(startNs: Long) = artCacheNs.add(System.nanoTime() - startNs)

    /** One file actually probed (as opposed to reused from the quick-scan cache). */
    fun countProbe() = probed.increment()

    /**
     * Appended to the scan's completion log line. Empty when nothing was probed, so a quick scan
     * that reused every row does not print a row of zeroes.
     */
    fun summary(): String {
        val n = probed.sum()
        if (n == 0L) return " — no files probed"
        val open = metadataNs.sum() / 1_000_000
        val art = artworkNs.sum() / 1_000_000
        val cache = artCacheNs.sum() / 1_000_000
        // Zero-valued stages are omitted rather than printed as "0ms": not every scanner has all
        // three (music caches album art as its own step, photo and video fold the write into
        // thumbnail generation), and a row of zeroes reads like a measurement failure.
        val parts = buildList {
            if (open > 0) add("open+tags ${open}ms")
            if (art > 0) add("artwork/thumbnail ${art}ms")
            if (cache > 0) add("art cache ${cache}ms")
        }
        if (parts.isEmpty()) return " — probed $n, all stages under 1ms"
        return " — probed $n: ${parts.joinToString(", ")}" +
            " (~${(open + art + cache) / n}ms/file across workers)"
    }
}
