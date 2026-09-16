package com.playfieldportal.feature.artwork.portable

import android.net.Uri
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects durable identity for library files and writes it **once per operation** (C16 task D.2).
 *
 * `RoutingArtworkStore.persistPortable` runs once per artwork file, and the index is a single
 * document at the library root — so writing on every save would rewrite the whole file eight times
 * for one eight-kind scrape, over SAF, on an SD card. Recording is therefore an in-memory upsert
 * and [flush] is the only thing that touches the folder.
 *
 * **What happens to unflushed rows.** They are lost if the process dies first. That is acceptable
 * rather than merely tolerated: D.4's backfill rebuilds the index from `artwork_records` on the
 * next relink, so the worst case is that identity is one relink behind, not that it is wrong.
 *
 * The in-memory copy is the folder's, merged — [record] loads the existing index before its first
 * upsert, so a flush never drops rows another session wrote.
 */
@Singleton
class ArtworkIdentityRecorder @Inject constructor(
    private val library: PortableArtworkLibrary,
) {
    private val mutex = Mutex()

    // Which tree the cached index belongs to — re-linking a different folder must not flush one
    // library's identity into another.
    private var loadedFor: String? = null
    private var index: ArtworkIdentityIndex = ArtworkIdentityIndex()
    private var dirty = false

    /** Buffers [entry] for the next [flush]. Never writes. */
    suspend fun record(tree: Uri, entry: ArtworkIdentityIndex.Entry) = mutex.withLock {
        ensureLoaded(tree)
        val before = index
        index = index.upsert(entry)
        if (index != before) dirty = true
    }

    /**
     * Writes the buffered identity, if anything changed. Call this at an operation boundary — the
     * end of an import, the end of a relink — never per file.
     *
     * Returns false only when the write itself failed; the rows stay buffered and dirty so the next
     * flush retries them rather than losing them silently.
     */
    suspend fun flush(tree: Uri): Boolean = mutex.withLock {
        ensureLoaded(tree)
        if (!dirty) return@withLock true
        val ok = library.writeIdentityIndex(tree, index)
        if (ok) dirty = false
        ok
    }

    /** The identity known for [tree], folder plus anything buffered since. */
    suspend fun current(tree: Uri): ArtworkIdentityIndex = mutex.withLock {
        ensureLoaded(tree)
        index
    }

    private suspend fun ensureLoaded(tree: Uri) {
        val key = tree.toString()
        if (loadedFor == key) return
        index = library.readIdentityIndex(tree) ?: ArtworkIdentityIndex()
        loadedFor = key
        dirty = false
    }
}
