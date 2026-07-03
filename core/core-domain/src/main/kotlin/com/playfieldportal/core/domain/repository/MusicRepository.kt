package com.playfieldportal.core.domain.repository

import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.model.MusicTrack
import com.playfieldportal.core.domain.model.Playlist
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
    /** Re-points the folder at a (re-granted) SAF tree; the next scan reads from it. */
    suspend fun setFolderTreeUri(id: String, treeUri: String)
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

    // ── Playlists ───────────────────────────────────────────────────────────────
    fun observePlaylists(): Flow<List<Playlist>>
    fun observePlaylistTracks(playlistId: Long): Flow<List<MusicTrack>>
    /** Playlist ids the track belongs to — drives the checkmarks in "Add to Playlist". */
    suspend fun getPlaylistIdsForTrack(trackId: String): List<Long>
    suspend fun createPlaylist(name: String): Long
    suspend fun renamePlaylist(id: Long, name: String)
    suspend fun deletePlaylist(id: Long)
    suspend fun addTrackToPlaylist(playlistId: Long, trackId: String)
    suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String)
    /** Adds the track if absent, removes it if present; returns the new membership state. */
    suspend fun toggleTrackInPlaylist(playlistId: Long, trackId: String): Boolean
}
