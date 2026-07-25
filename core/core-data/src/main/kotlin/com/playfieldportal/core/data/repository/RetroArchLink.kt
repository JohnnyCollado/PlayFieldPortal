package com.playfieldportal.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
 * installed. Without a link, detection falls back to offering every curated core (unverified).
 */
@Singleton
class RetroArchLink @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun linkedTreeUri(): String? =
        context.pfpDataStore.data.first()[KEY].takeIf { !it.isNullOrBlank() }

    /** True when a tree is stored AND its read grant is still live (grants are lost on reinstall). */
    suspend fun isLinked(): Boolean {
        val uri = linkedTreeUri() ?: return false
        return uri in SafGrants.persistedReadUris(context.contentResolver)
    }

    suspend fun save(treeUri: Uri) {
        persist(treeUri)
        context.pfpDataStore.edit { it[KEY] = treeUri.toString() }
        Timber.i("RetroArch linked: $treeUri")
    }

    suspend fun clear() {
        context.pfpDataStore.edit { it.remove(KEY) }
    }

    private fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Timber.w(it, "Could not persist RetroArch tree grant for $uri") }
    }

    /**
     * The set of installed libretro core file names (e.g. `snes9x_libretro_android.so`), discovered
     * by walking the linked tree to a `cores` folder. Returns null when not linked or the grant is
     * gone (caller then falls back to offering unverified cores); an empty set means linked but no
     * cores found.
     */
    suspend fun installedCoreFiles(): Set<String>? {
        val treeUriStr = linkedTreeUri() ?: return null
        if (treeUriStr !in SafGrants.persistedReadUris(context.contentResolver)) {
            Timber.w("RetroArch link present but grant lost — needs re-linking")
            return null
        }
        val treeUri = runCatching { Uri.parse(treeUriStr) }.getOrNull() ?: return null
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val cr = context.contentResolver

        // The linked folder may itself be the cores dir, or contain it one or two levels down
        // (RetroArch's base dir → cores/). Search a shallow tree for a folder named "cores"; if
        // none is found, treat the linked folder's own .so files as the core set.
        val coresDocId = findCoresDocId(treeUri, rootDocId) ?: rootDocId
        val cores = cr.querySafChildren(treeUri, coresDocId)
            .asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".so") && it.name.contains("_libretro") }
            .map { it.name }
            .toSet()
        Timber.i("RetroArch installed cores detected: ${cores.size} (${cores.take(6).joinToString()}${if (cores.size > 6) "…" else ""})")
        return cores
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
    }
}
