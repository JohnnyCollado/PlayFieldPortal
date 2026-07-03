package com.playfieldportal.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Multiple ROM roots (internal storage + SD card + …). Stored newline-joined under one string key
// so they ride the existing backup string-key mechanism (tree URIs are URL-encoded and never
// contain newlines). The legacy single-root key is read as a fallback so older installs/backups
// migrate transparently.
private val KEY_ROM_ROOT_TREE_URIS = stringPreferencesKey("library_rom_root_tree_uris")
private val KEY_LEGACY_ROM_ROOT    = stringPreferencesKey("library_rom_root_tree_uri")

/**
 * The user's ROM root folders. Each is a persisted `ACTION_OPEN_DOCUMENT_TREE` grant; because SAF
 * tree grants are recursive, every console's subfolder under a root is readable without a separate
 * grant. Multiple roots let a library span internal storage and an SD card. Re-linking a root after
 * a wipe re-enables every console it contains in one tap (managed under Library ▸ ROM Root Access).
 */
@Singleton
class RomRootRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** All configured ROM roots, in the order they were added. */
    val roots: Flow<List<String>> = context.pfpDataStore.data.map { readRoots(it) }

    suspend fun getAll(): List<String> = readRoots(context.pfpDataStore.data.first())

    /** Adds a root (deduplicated, order-preserving). No-op for a blank URI. */
    suspend fun add(treeUri: String) {
        if (treeUri.isBlank()) return
        val next = LinkedHashSet(getAll()).apply { add(treeUri) }.toList()
        writeRoots(next)
        Timber.i("ROM root added: $treeUri (total ${next.size})")
    }

    suspend fun remove(treeUri: String) {
        val next = getAll().filterNot { it == treeUri }
        writeRoots(next)
        Timber.i("ROM root removed: $treeUri (total ${next.size})")
    }

    /** Replaces one root URI with another (used when a re-link picks a different folder). */
    suspend fun replace(oldTreeUri: String, newTreeUri: String) {
        if (newTreeUri.isBlank()) return
        val current = getAll()
        val next = if (oldTreeUri in current) {
            LinkedHashSet(current.map { if (it == oldTreeUri) newTreeUri else it })
        } else {
            LinkedHashSet(current).apply { add(newTreeUri) }
        }
        writeRoots(next.toList())
    }

    /** Takes a persistable grant. Read-only by default; pass [writable] for the ES-DE folder setup. */
    fun persist(uri: Uri, writable: Boolean = false) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { Timber.w(it, "Could not persist ROM root permission for $uri") }
    }

    private fun readRoots(prefs: Preferences): List<String> {
        val plural = prefs[KEY_ROM_ROOT_TREE_URIS]
            ?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()
        if (plural.isNotEmpty()) return plural
        // Migration fallback: a single legacy root written by an earlier build.
        return prefs[KEY_LEGACY_ROM_ROOT]?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }

    private suspend fun writeRoots(roots: List<String>) {
        context.pfpDataStore.edit { prefs ->
            if (roots.isEmpty()) prefs.remove(KEY_ROM_ROOT_TREE_URIS)
            else prefs[KEY_ROM_ROOT_TREE_URIS] = roots.joinToString("\n")
            // Collapse the legacy key once migrated so it can't shadow later edits.
            prefs.remove(KEY_LEGACY_ROM_ROOT)
        }
    }

    companion object {
        /** Root tree URI → its raw filesystem path (pure string math, no file access). */
        fun rawPathOfTree(treeUri: String): String? {
            val docId = treeDocId(treeUri) ?: return null
            return docIdToRawPath(docId)
        }

        fun treeDocId(treeUri: String): String? =
            runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)) }.getOrNull()

        /** Document id of a named subfolder directly under a root tree (e.g. root + "gba"). */
        fun childDocIdOf(treeUri: String, subfolderName: String): String? {
            val root = treeDocId(treeUri) ?: return null
            return "$root/$subfolderName"
        }

        // Mirrors RomScanner.safDocumentIdToRawPath: "primary:Roms" → "/storage/emulated/0/Roms",
        // "1A2B-3C4D:Games" → "/storage/1A2B-3C4D/Games". Null for opaque (non-volume) ids.
        fun docIdToRawPath(documentId: String): String? {
            val parts = documentId.split(":", limit = 2)
            if (parts.size != 2 || parts[1].isBlank()) return null
            val (volume, relative) = parts
            return if (volume.equals("primary", ignoreCase = true)) {
                "/storage/emulated/0/$relative"
            } else {
                "/storage/$volume/$relative"
            }
        }

        /**
         * Document id of the subfolder a card scans, given a root and the card's raw ROM directory.
         * Null when the directory is not under the root. e.g. root "primary:Roms"
         * (`/storage/emulated/0/Roms`) + "/storage/emulated/0/Roms/GBA" → "primary:Roms/GBA".
         */
        fun childDocIdFor(rootTreeUri: String, rootRawPath: String, romDirectory: String): String? {
            val rootDocId = treeDocId(rootTreeUri) ?: return null
            return childDocIdFrom(rootDocId, rootRawPath, romDirectory)
        }

        // Pure (no Android) core of [childDocIdFor] — testable on the JVM.
        fun childDocIdFrom(rootDocId: String, rootRawPath: String, romDirectory: String): String? {
            val normRoot = rootRawPath.trimEnd('/')
            val normDir  = romDirectory.trimEnd('/')
            if (normDir != normRoot && !normDir.startsWith("$normRoot/")) return null
            val relative = normDir.removePrefix(normRoot).trim('/')
            return if (relative.isEmpty()) rootDocId else "$rootDocId/$relative"
        }
    }
}
