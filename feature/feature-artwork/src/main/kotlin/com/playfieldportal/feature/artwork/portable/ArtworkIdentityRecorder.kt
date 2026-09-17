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
 * **The only writer (task 1.4 / D2).** This is the sole owner of `pfp-artwork-identity.json`; no
 * other code calls [PortableArtworkLibrary.readIdentityIndex] or
 * [PortableArtworkLibrary.writeIdentityIndex]. `ArtworkImportManager.relinkLibrary` reads through
 * [current] and writes through [recordAll] + [flush], the same as any other caller — [flush] is
 * called at the end of an import (`ArtworkImportExecutor`) **and** at the end of a relink.
 *
 * **What happens to unflushed rows.** They are lost if the process dies first. That is acceptable
 * rather than merely tolerated: D.4's backfill rebuilds the index from `artwork_records` on the
 * next relink, so the worst case is that identity is one relink behind, not that it is wrong.
 *
 * The in-memory copy is the folder's, merged — [record] loads the existing index before its first
 * upsert, so a flush never drops rows another session wrote.
 *
 * **Tri-state per tree (task 1.3 / D3).** [folderIndex] is the last index this recorder could
 * actually read for the tree — either what [PortableArtworkLibrary.readIdentityIndex] loaded, or
 * empty when the folder had none yet ([PortableArtworkLibrary.IdentityIndexRead.Absent]). While the
 * folder's file is [PortableArtworkLibrary.IdentityIndexRead.Unreadable], [folderIndex] is left
 * untouched (never reset to empty), [record] keeps buffering into [recorded], and [flush] refuses
 * to write and returns false — an index the app cannot read must never be overwritten. Every
 * [ensureLoaded] call while unreadable retries the read, so a transient failure (or a rewrite by a
 * newer app version going away) heals on the next operation. On a read that recovers, the newly
 * loaded folder content is merged with whatever was buffered in the meantime, so nothing recorded
 * while unreadable is lost.
 */
@Singleton
class ArtworkIdentityRecorder @Inject constructor(
    private val library: PortableArtworkLibrary,
) {
    private val mutex = Mutex()

    // Which tree the cached state belongs to — re-linking a different folder must not flush one
    // library's identity into another.
    private var loadedFor: String? = null

    // The last index this recorder could actually read for [loadedFor] — never reset to empty
    // just because a later read comes back unreadable.
    private var folderIndex: ArtworkIdentityIndex = ArtworkIdentityIndex()

    // Rows recorded since folderIndex was last confirmed by a successful read or write. Kept
    // separate from folderIndex (rather than folded straight in) so an Unreadable read never has
    // to guess which rows in the merged view were the folder's and which were only buffered.
    private var recorded: List<ArtworkIdentityIndex.Entry> = emptyList()

    // True while the folder's index file could not be read on the last attempt for [loadedFor].
    private var unreadable = false

    /** Buffers [entry] for the next [flush]. Never writes. */
    suspend fun record(tree: Uri, entry: ArtworkIdentityIndex.Entry) = mutex.withLock {
        ensureLoaded(tree)
        recorded = recorded + entry
    }

    /**
     * Buffers every row in [entries] for the next [flush]. Never writes. Bulk counterpart of
     * [record] for callers that already collected a batch — a relink's whole-library backfill,
     * for one — so they don't pay the mutex lock/unlock cost per row (task 1.4).
     */
    suspend fun recordAll(tree: Uri, entries: List<ArtworkIdentityIndex.Entry>) = mutex.withLock {
        if (entries.isEmpty()) return@withLock
        ensureLoaded(tree)
        recorded = recorded + entries
    }

    /**
     * Writes the buffered identity, if anything changed. Call this at an operation boundary — the
     * end of an import, the end of a relink — never per file.
     *
     * Returns false when the folder's index is currently unreadable (task 1.3 / D3) or the write
     * itself failed; either way the rows stay buffered so the next flush retries them rather than
     * losing them silently.
     */
    suspend fun flush(tree: Uri): Boolean = mutex.withLock {
        ensureLoaded(tree)
        if (unreadable) return@withLock false
        if (recorded.isEmpty()) return@withLock true
        val merged = folderIndex.upsertAll(recorded)
        // Rows that only restate what the folder already holds write nothing — a relink over an
        // unchanged library must not rewrite this file on the SD card every scan.
        if (merged.entries == folderIndex.entries) {
            recorded = emptyList()
            return@withLock true
        }
        val ok = library.writeIdentityIndex(tree, merged)
        if (ok) {
            folderIndex = merged
            recorded = emptyList()
        }
        ok
    }

    /** The identity known for [tree], folder plus anything buffered since. */
    suspend fun current(tree: Uri): ArtworkIdentityIndex = mutex.withLock {
        ensureLoaded(tree)
        folderIndex.upsertAll(recorded)
    }

    private suspend fun ensureLoaded(tree: Uri) {
        val key = tree.toString()
        if (loadedFor == key && !unreadable) return
        when (val read = library.readIdentityIndex(tree)) {
            is PortableArtworkLibrary.IdentityIndexRead.Absent -> {
                folderIndex = ArtworkIdentityIndex()
                unreadable = false
            }
            is PortableArtworkLibrary.IdentityIndexRead.Loaded -> {
                folderIndex = read.index
                unreadable = false
            }
            is PortableArtworkLibrary.IdentityIndexRead.Unreadable -> {
                // Leave folderIndex/recorded exactly as they were — do not invent an empty index
                // for a file that might still exist with rows we simply couldn't read this time.
                unreadable = true
            }
        }
        loadedFor = key
    }
}
