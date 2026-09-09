package com.playfieldportal.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.saf.querySafChildren
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A user-granted SAF link into RetroArch's own document tree (authority `com.retroarch.documents`),
 * used to discover which libretro cores are actually installed.
 *
 * Why this exists: RetroArch stores cores in private internal storage that no other app can read,
 * so PFP otherwise cannot tell an installed core from a missing one and drops the user into a
 * silent black screen. RetroArch exposes its directories through a DocumentsProvider; a one-time
 * `ACTION_OPEN_DOCUMENT_TREE` grant lets PFP enumerate the `cores` folder and know exactly what's
 * installed.
 *
 * Without a link PFP offers no RetroArch cores at all. It used to fall back to offering a curated
 * guess, which is what produced the black screens; see [CoreInventory] for why that state is now
 * named rather than papered over.
 */
@Singleton
class RetroArchLink @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun linkedTreeUri(): String? =
        context.pfpDataStore.data.first()[KEY].takeIf { !it.isNullOrBlank() }

    suspend fun save(treeUri: Uri) {
        persist(treeUri)
        context.pfpDataStore.edit { it[KEY] = treeUri.toString() }
        Timber.i("RetroArch linked: $treeUri")
    }

    /** Forgets the tree AND the remembered inventory — an explicit unlink means "know nothing". */
    suspend fun clear() {
        context.pfpDataStore.edit {
            it.remove(KEY)
            it.remove(KEY_CACHED_CORES)
        }
    }

    private fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Timber.w(it, "Could not persist RetroArch tree grant for $uri") }
    }

    /**
     * What PFP knows about the installed cores right now — see [CoreInventory] for the states and
     * the rule about which of them may drive a deletion.
     *
     * A successful read is cached, so a later loss of the SAF grant degrades to
     * [CoreInventory.Remembered] instead of silently erasing the user's RetroArch setup. Every
     * failure path below degrades the same way, deliberately: an inventory we could not read is
     * never evidence that a core is gone.
     */
    suspend fun inventory(): CoreInventory {
        val treeUriStr = linkedTreeUri() ?: return CoreInventory.Unlinked

        if (treeUriStr !in SafGrants.persistedReadUris(context.contentResolver)) {
            Timber.w("RetroArch link present but grant lost — needs re-linking")
            return remembered()
        }
        val treeUri = runCatching { Uri.parse(treeUriStr) }.getOrNull() ?: return remembered()
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return remembered()

        // The linked folder may itself be the cores dir, or contain it one or two levels down
        // (RetroArch's base dir → cores/). Search a shallow tree for a folder named "cores"; if
        // none is found, treat the linked folder's own .so files as the core set.
        val cores = runCatching {
            val coresDocId = findCoresDocId(treeUri, rootDocId) ?: rootDocId
            context.contentResolver.querySafChildren(treeUri, coresDocId)
                .asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".so") && it.name.contains("_libretro") }
                .map { it.name }
                .toSet()
        }.getOrElse {
            Timber.w(it, "RetroArch core enumeration failed — keeping the last known inventory")
            return remembered()
        }

        if (cores.isEmpty()) {
            Timber.w("RetroArch linked but no libretro cores found under the granted folder")
            return CoreInventory.EmptyTree
        }

        cacheCores(cores)
        Timber.i(
            "RetroArch installed cores detected: ${cores.size} " +
                "(${cores.take(6).joinToString()}${if (cores.size > 6) "…" else ""})"
        )
        return CoreInventory.Verified(cores)
    }

    /** The last successfully-read inventory, or [CoreInventory.Unlinked] if there has never been one. */
    private suspend fun remembered(): CoreInventory {
        val cached = context.pfpDataStore.data.first()[KEY_CACHED_CORES].orEmpty()
        if (cached.isEmpty()) return CoreInventory.Unlinked
        Timber.i("RetroArch inventory unreadable — using ${cached.size} remembered core(s)")
        return CoreInventory.Remembered(cached)
    }

    private suspend fun cacheCores(cores: Set<String>) {
        runCatching { context.pfpDataStore.edit { it[KEY_CACHED_CORES] = cores } }
            .onFailure { Timber.w(it, "Could not cache RetroArch core inventory") }
    }

    // Breadth-first, depth-limited search for a child directory named "cores".
    private fun findCoresDocId(treeUri: Uri, startDocId: String, maxDepth: Int = 2): String? {
        var frontier = listOf(startDocId)
        val cr = context.contentResolver
        repeat(maxDepth) {
            val next = mutableListOf<String>()
            for (docId in frontier) {
                val children = cr.querySafChildren(treeUri, docId)
                children.firstOrNull { it.isDirectory && it.name.equals("cores", ignoreCase = true) }
                    ?.let { return it.documentId }
                next += children.filter { it.isDirectory && !it.name.startsWith(".") }.map { it.documentId }
            }
            frontier = next
        }
        return null
    }

    companion object {
        const val RETROARCH_DOCUMENTS_AUTHORITY = "com.retroarch.documents"
        private val KEY = stringPreferencesKey("retroarch_documents_tree_uri")
        private val KEY_CACHED_CORES = stringSetPreferencesKey("retroarch_cached_core_files")
    }
}
