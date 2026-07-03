package com.playfieldportal.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.playfieldportal.core.domain.repository.MusicRepository
import com.playfieldportal.core.domain.repository.PhotoRepository
import com.playfieldportal.core.domain.repository.VideoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

enum class FolderKind { ROM_ROOT, MUSIC, VIDEO, PHOTO, BACKUP }

enum class FolderLinkStatus {
    /** A live persisted read grant exists for the stored tree URI. */
    LINKED,
    /** The tree URI is stored (in the DB / settings) but the OS grant is gone — needs re-linking. */
    ACCESS_LOST,
}

data class FolderAccessItem(
    val kind: FolderKind,
    val id: String?,            // library id; null for the ROM root
    val displayName: String,
    val treeUri: String,
    val status: FolderLinkStatus,
)

/**
 * Single place that answers "which of the user's granted folders still have live SAF access?" and
 * re-establishes a grant. Backs the Folder Access screen: after a restore (or any lost grant — SD
 * card removed, folder deleted) every folder shows [FolderLinkStatus.ACCESS_LOST] until the user
 * re-links it, one tap each (the ROM root re-links every console at once).
 */
@Singleton
class FolderAccessManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val romRoot: RomRootRepository,
    private val music: MusicRepository,
    private val video: VideoRepository,
    private val photo: PhotoRepository,
    private val backupFolder: BackupFolderRepository,
) {

    /** All stored folder grants with their current link status. ROM root first, then media. */
    suspend fun folders(): List<FolderAccessItem> {
        val persisted = persistedReadUris()
        val out = mutableListOf<FolderAccessItem>()

        val roots = romRoot.getAll()
        roots.forEachIndexed { i, uri ->
            val label = if (roots.size == 1) "ROM Root" else "ROM Root ${i + 1}"
            // id = the URI so a re-link can replace exactly this root in the list.
            out += FolderAccessItem(FolderKind.ROM_ROOT, uri, label, uri, linkStatus(uri, persisted))
        }
        music.getFolders().forEach {
            out += FolderAccessItem(FolderKind.MUSIC, it.id, it.displayName, it.treeUri, linkStatus(it.treeUri, persisted))
        }
        video.getLibraries().forEach {
            out += FolderAccessItem(FolderKind.VIDEO, it.id, it.displayName, it.treeUri, linkStatus(it.treeUri, persisted))
        }
        photo.getLibraries().forEach {
            out += FolderAccessItem(FolderKind.PHOTO, it.id, it.displayName, it.treeUri, linkStatus(it.treeUri, persisted))
        }
        backupFolder.get()?.takeIf { it.isNotBlank() }?.let { uri ->
            out += FolderAccessItem(FolderKind.BACKUP, uri, "Backup Folder", uri, linkStatus(uri, persisted))
        }
        return out
    }

    /** Read-permission URIs currently persisted to this app, as strings for exact comparison. */
    fun persistedReadUris(): Set<String> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission }
            .map { it.uri.toString() }
            .toSet()

    /**
     * Completes a re-link: takes a persistable read grant on [grantedUri] and, if the user picked a
     * different folder than the one stored, updates the stored URI so scans/launches follow it.
     */
    suspend fun relink(item: FolderAccessItem, grantedUri: Uri) {
        // The backup folder is written to, so it needs a write grant; everything else is read-only.
        persist(grantedUri, writable = item.kind == FolderKind.BACKUP)
        val granted = grantedUri.toString()
        if (granted != item.treeUri) updateStoredUri(item, granted)
        Timber.i("Folder re-linked: kind=${item.kind} id=${item.id} -> $granted")
    }

    /** Adds a ROM root (internal or SD) and persists a read grant on it. */
    suspend fun addRomRoot(uri: Uri) {
        persist(uri)
        romRoot.add(uri.toString())
        Timber.i("ROM root added via Folder Access -> $uri")
    }

    fun persist(uri: Uri, writable: Boolean = false) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            (if (writable) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        }.onFailure { Timber.w(it, "Could not persist permission for $uri") }
    }

    private suspend fun updateStoredUri(item: FolderAccessItem, newTreeUri: String) {
        when (item.kind) {
            FolderKind.ROM_ROOT -> romRoot.replace(item.treeUri, newTreeUri)
            FolderKind.MUSIC    -> item.id?.let { music.setFolderTreeUri(it, newTreeUri) }
            FolderKind.VIDEO    -> item.id?.let { video.setLibraryTreeUri(it, newTreeUri) }
            FolderKind.PHOTO    -> item.id?.let { photo.setLibraryTreeUri(it, newTreeUri) }
            FolderKind.BACKUP   -> backupFolder.set(newTreeUri)
        }
    }

    companion object {
        /** Pure status rule, factored out for unit testing. */
        fun linkStatus(treeUri: String, persistedReadUris: Set<String>): FolderLinkStatus =
            if (treeUri in persistedReadUris) FolderLinkStatus.LINKED else FolderLinkStatus.ACCESS_LOST
    }
}
