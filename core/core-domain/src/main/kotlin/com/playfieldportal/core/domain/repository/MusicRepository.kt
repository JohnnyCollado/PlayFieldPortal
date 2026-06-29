package com.playfieldportal.core.domain.repository

import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.model.MusicTrack
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for the Music library: user-added SAF folders, the tracks discovered by manual
 * scans, and the user's chosen default external music player.
 */
interface MusicRepository {

    // ── Folders ───────────────────────────────────────────────────────────────
    fun observeFolders(): Flow<List<MusicFolder>>
    fun observeEnabledFolders(): Flow<List<MusicFolder>>
    suspend fun getFolders(): List<MusicFolder>
    suspend fun getFolder(id: String): MusicFolder?
    suspend fun addFolder(displayName: String, treeUri: String): MusicFolder
    suspend fun renameFolder(id: String, displayName: String)
    suspend fun setFolderEnabled(id: String, enabled: Boolean)
    /** Removes the folder and all of its tracks. */
    suspend fun removeFolder(id: String)

    // ── Tracks ────────────────────────────────────────────────────────────────
    fun observeAllTracks(): Flow<List<MusicTrack>>
    fun observeTracksByFolder(folderId: String): Flow<List<MusicTrack>>
    suspend fun getTrack(id: String): MusicTrack?
    /** Atomically replaces the tracks of one folder only; other folders' tracks are untouched. */
    suspend fun replaceTracksForFolder(folderId: String, tracks: List<MusicTrack>, scannedAt: Long)

    // ── Default player (DataStore-backed) ───────────────────────────────────────
    /** Package name of the chosen external player, or null for the system default chooser. */
    fun observeDefaultPlayerPackage(): Flow<String?>
    suspend fun getDefaultPlayerPackage(): String?
    suspend fun setDefaultPlayerPackage(packageName: String?)
}
