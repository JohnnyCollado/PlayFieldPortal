package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.database.dao.MusicFolderDao
import com.playfieldportal.core.data.database.dao.MusicTrackDao
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.domain.model.MusicFolder
import com.playfieldportal.core.domain.model.MusicTrack
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
}
