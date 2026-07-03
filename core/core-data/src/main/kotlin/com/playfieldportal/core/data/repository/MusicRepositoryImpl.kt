package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.database.dao.MusicFolderDao
import com.playfieldportal.core.data.database.dao.MusicTrackDao
import com.playfieldportal.core.data.database.dao.PlaylistDao
import com.playfieldportal.core.data.database.entity.PlaylistEntity
import com.playfieldportal.core.data.database.entity.PlaylistTrackEntity
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.model.MusicTrack
import com.playfieldportal.core.domain.model.Playlist
import com.playfieldportal.core.domain.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_DEFAULT_PLAYER = stringPreferencesKey("music_default_player_package")

@Singleton
class MusicRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderDao: MusicFolderDao,
    private val trackDao: MusicTrackDao,
    private val playlistDao: PlaylistDao,
) : MusicRepository {

    override fun observeFolders(): Flow<List<MusicFolder>> =
        folderDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeEnabledFolders(): Flow<List<MusicFolder>> =
        folderDao.observeEnabled().map { list -> list.map { it.toDomain() } }

    override suspend fun getFolders(): List<MusicFolder> =
        folderDao.getAll().map { it.toDomain() }

    override suspend fun getFolder(id: String): MusicFolder? =
        folderDao.getById(id)?.toDomain()

    override suspend fun addFolder(displayName: String, treeUri: String): MusicFolder {
        val now = System.currentTimeMillis()
        val folder = MusicFolder(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            treeUri = treeUri,
            enabled = true,
            createdAt = now,
            updatedAt = now,
        )
        folderDao.upsert(folder.toEntity())
        Timber.i("Music folder added: \"$displayName\" -> $treeUri")
        return folder
    }

    override suspend fun renameFolder(id: String, displayName: String) =
        folderDao.setDisplayName(id, displayName, System.currentTimeMillis())

    override suspend fun setFolderTreeUri(id: String, treeUri: String) =
        folderDao.setTreeUri(id, treeUri, System.currentTimeMillis())

    override suspend fun setFolderEnabled(id: String, enabled: Boolean) =
        folderDao.setEnabled(id, enabled, System.currentTimeMillis())

    override suspend fun removeFolder(id: String) {
        // Tracks cascade-delete via the foreign key, but delete explicitly too so behaviour is
        // identical whether or not foreign keys are enforced on the connection.
        trackDao.deleteForFolder(id)
        folderDao.delete(id)
        Timber.i("Music folder removed: $id")
    }

    override fun observeAllTracks(): Flow<List<MusicTrack>> =
        trackDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeTracksByFolder(folderId: String): Flow<List<MusicTrack>> =
        trackDao.observeByFolder(folderId).map { list -> list.map { it.toDomain() } }

    override suspend fun getTrack(id: String): MusicTrack? =
        trackDao.getById(id)?.toDomain()

    override suspend fun replaceTracksForFolder(
        folderId: String,
        tracks: List<MusicTrack>,
        scannedAt: Long,
    ) {
        trackDao.replaceForFolder(folderId, tracks.map { it.toEntity() })
        folderDao.updateScanResult(folderId, tracks.size, scannedAt)
        Timber.i("Replaced ${tracks.size} tracks for music folder $folderId")
    }

    override fun observeDefaultPlayerPackage(): Flow<String?> =
        context.pfpDataStore.data.map { it[KEY_DEFAULT_PLAYER] }

    override suspend fun getDefaultPlayerPackage(): String? =
        context.pfpDataStore.data.first()[KEY_DEFAULT_PLAYER]

    override suspend fun setDefaultPlayerPackage(packageName: String?) {
        context.pfpDataStore.edit { prefs ->
            if (packageName.isNullOrBlank()) prefs.remove(KEY_DEFAULT_PLAYER)
            else prefs[KEY_DEFAULT_PLAYER] = packageName
        }
    }

    // ── Playlists ───────────────────────────────────────────────────────────────

    override fun observePlaylists(): Flow<List<Playlist>> =
        playlistDao.observeAllWithCounts().map { rows ->
            rows.map { it.playlist.toDomain(trackCount = it.track_count) }
        }

    override fun observePlaylistTracks(playlistId: Long): Flow<List<MusicTrack>> =
        playlistDao.observeTracks(playlistId).map { list -> list.map { it.toDomain() } }

    override suspend fun getPlaylistIdsForTrack(trackId: String): List<Long> =
        playlistDao.getPlaylistIdsForTrack(trackId)

    override suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        val id = playlistDao.insert(
            PlaylistEntity(
                name = name,
                createdAt = now,
                updatedAt = now,
                sortOrder = playlistDao.maxSortOrder() + 1,
            )
        )
        Timber.i("Playlist created: \"$name\" (id=$id)")
        return id
    }

    override suspend fun renamePlaylist(id: Long, name: String) =
        playlistDao.rename(id, name, System.currentTimeMillis())

    override suspend fun deletePlaylist(id: Long) {
        playlistDao.delete(id)
        Timber.i("Playlist removed: $id")
    }

    override suspend fun addTrackToPlaylist(playlistId: Long, trackId: String) {
        val now = System.currentTimeMillis()
        playlistDao.addTrack(
            PlaylistTrackEntity(
                playlistId = playlistId,
                trackId = trackId,
                position = playlistDao.maxPosition(playlistId) + 1,
                addedAt = now,
            )
        )
        playlistDao.touch(playlistId, now)
    }

    override suspend fun removeTrackFromPlaylist(playlistId: Long, trackId: String) {
        playlistDao.removeTrack(playlistId, trackId)
        playlistDao.touch(playlistId, System.currentTimeMillis())
    }

    override suspend fun toggleTrackInPlaylist(playlistId: Long, trackId: String): Boolean {
        val present = playlistDao.isTrackInPlaylist(playlistId, trackId) > 0
        if (present) removeTrackFromPlaylist(playlistId, trackId)
        else addTrackToPlaylist(playlistId, trackId)
        return !present
    }
}
