package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.database.dao.VideoDao
import com.playfieldportal.core.data.database.dao.VideoLibraryDao
import com.playfieldportal.core.data.database.dao.VideoPlaylistDao
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import com.playfieldportal.core.data.database.entity.VideoPlaylistEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistItemEntity
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.domain.model.VideoLibrary
import com.playfieldportal.core.domain.model.VideoPlaylist
import com.playfieldportal.core.domain.repository.VideoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val KEY_VIDEO_DEFAULT_PLAYER = stringPreferencesKey("video_default_player")

@Singleton
class VideoRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val libraryDao: VideoLibraryDao,
    private val videoDao: VideoDao,
    private val playlistDao: VideoPlaylistDao,
) : VideoRepository {

    override fun observeLibraries(): Flow<List<VideoLibrary>> =
        libraryDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeEnabledLibraries(): Flow<List<VideoLibrary>> =
        libraryDao.observeEnabled().map { list -> list.map { it.toDomain() } }

    override suspend fun getLibraries(): List<VideoLibrary> =
        libraryDao.getAll().map { it.toDomain() }

    override suspend fun getLibrary(id: String): VideoLibrary? =
        libraryDao.getById(id)?.toDomain()

    override suspend fun addLibrary(
        displayName: String,
        treeUri: String,
        scanRecursively: Boolean,
    ): VideoLibrary {
        val now = System.currentTimeMillis()
        val library = VideoLibrary(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            treeUri = treeUri,
            enabled = true,
            scanRecursively = scanRecursively,
            createdAt = now,
            updatedAt = now,
        )
        libraryDao.upsert(library.toEntity())
        Timber.i("Video library added: \"$displayName\" -> $treeUri")
        return library
    }

    override suspend fun renameLibrary(id: String, displayName: String) =
        libraryDao.setDisplayName(id, displayName, System.currentTimeMillis())

    override suspend fun setLibraryEnabled(id: String, enabled: Boolean) =
        libraryDao.setEnabled(id, enabled, System.currentTimeMillis())

    override suspend fun setLibraryArtwork(id: String, artworkUri: String?) =
        libraryDao.setArtwork(id, artworkUri, System.currentTimeMillis())

    override suspend fun removeLibrary(id: String) {
        // Capture thumbnail uris (generated + custom) before the rows go, so their cached files
        // can be forgotten too.
        val thumbs = videoDao.getForLibrary(id).flatMap { listOfNotNull(it.thumbnailUri, it.customThumbnailUri) }
        // Videos cascade-delete via the foreign key, but delete explicitly too so behaviour is
        // identical whether or not foreign keys are enforced on the connection.
        videoDao.deleteForLibrary(id)
        libraryDao.delete(id)
        deleteOrphanedThumbnails(thumbs) { videoDao.countReferencingThumbnail(it) > 0 }
        Timber.i("Video library removed: $id")
    }

    override fun observeAllVideos(): Flow<List<Video>> =
        videoDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeVideosByLibrary(libraryId: String): Flow<List<Video>> =
        videoDao.observeByLibrary(libraryId).map { list -> list.map { it.toDomain() } }

    override suspend fun getVideo(id: String): Video? =
        videoDao.getById(id)?.toDomain()

    override suspend fun getVideosForLibrary(libraryId: String): List<Video> =
        videoDao.getForLibrary(libraryId).map { it.toDomain() }

    override suspend fun replaceVideosForLibrary(
        libraryId: String,
        videos: List<Video>,
        scannedAt: Long,
    ) {
        videoDao.replaceForLibrary(libraryId, videos.map { it.toEntity() })
        libraryDao.updateScanResult(libraryId, videos.size, scannedAt)
        Timber.i("Replaced ${videos.size} videos for library $libraryId")
    }

    override suspend fun setResumePosition(id: String, positionMs: Long, watchedAt: Long) =
        videoDao.updateResumePosition(id, positionMs, watchedAt)

    override suspend fun clearResumePosition(id: String) =
        videoDao.clearResumePosition(id)

    override suspend fun setCustomTitle(id: String, title: String?) =
        videoDao.setTitle(id, title?.trim()?.takeIf { it.isNotBlank() })

    override suspend fun setCustomThumbnail(id: String, uri: String?) =
        videoDao.setCustomThumbnail(id, uri?.takeIf { it.isNotBlank() })

    override suspend fun removeVideo(id: String) {
        val thumbs = videoDao.getById(id)?.let { listOfNotNull(it.thumbnailUri, it.customThumbnailUri) }.orEmpty()
        videoDao.deleteById(id)
        deleteOrphanedThumbnails(thumbs) { videoDao.countReferencingThumbnail(it) > 0 }
    }

    // ── Favorites & recently watched ────────────────────────────────────────────

    override fun observeFavorites(): Flow<List<Video>> =
        videoDao.observeFavorites().map { list -> list.map { it.toDomain() } }

    override fun observeRecentlyWatched(limit: Int): Flow<List<Video>> =
        videoDao.observeRecentlyWatched(limit).map { list -> list.map { it.toDomain() } }

    override suspend fun setFavorite(id: String, favorite: Boolean) =
        videoDao.setFavorite(id, favorite)

    // ── Playlists ───────────────────────────────────────────────────────────────

    override fun observePlaylists(): Flow<List<VideoPlaylist>> =
        playlistDao.observeAllWithCounts().map { rows ->
            rows.map { it.playlist.toDomain(videoCount = it.video_count) }
        }

    override fun observePlaylistVideos(playlistId: Long): Flow<List<Video>> =
        playlistDao.observeVideos(playlistId).map { list -> list.map { it.toDomain() } }

    override suspend fun getPlaylistIdsForVideo(videoId: String): List<Long> =
        playlistDao.getPlaylistIdsForVideo(videoId)

    override suspend fun createPlaylist(name: String): Long {
        val now = System.currentTimeMillis()
        val id = playlistDao.insert(
            VideoPlaylistEntity(
                name = name,
                createdAt = now,
                updatedAt = now,
                sortOrder = playlistDao.maxSortOrder() + 1,
            )
        )
        Timber.i("Video playlist created: \"$name\" (id=$id)")
        return id
    }

    override suspend fun renamePlaylist(id: Long, name: String) =
        playlistDao.rename(id, name, System.currentTimeMillis())

    override suspend fun deletePlaylist(id: Long) {
        playlistDao.delete(id)
        Timber.i("Video playlist removed: $id")
    }

    override suspend fun addVideoToPlaylist(playlistId: Long, videoId: String) {
        val now = System.currentTimeMillis()
        playlistDao.addVideo(
            VideoPlaylistItemEntity(
                playlistId = playlistId,
                videoId = videoId,
                position = playlistDao.maxPosition(playlistId) + 1,
                addedAt = now,
            )
        )
        playlistDao.touch(playlistId, now)
    }

    override suspend fun removeVideoFromPlaylist(playlistId: Long, videoId: String) {
        playlistDao.removeVideo(playlistId, videoId)
        playlistDao.touch(playlistId, System.currentTimeMillis())
    }

    override suspend fun toggleVideoInPlaylist(playlistId: Long, videoId: String): Boolean {
        val present = playlistDao.isVideoInPlaylist(playlistId, videoId) > 0
        if (present) removeVideoFromPlaylist(playlistId, videoId)
        else addVideoToPlaylist(playlistId, videoId)
        return !present
    }

    override fun observeDefaultVideoPlayer(): Flow<String?> =
        context.pfpDataStore.data.map { it[KEY_VIDEO_DEFAULT_PLAYER] }

    override suspend fun getDefaultVideoPlayer(): String? =
        context.pfpDataStore.data.first()[KEY_VIDEO_DEFAULT_PLAYER]

    override suspend fun setDefaultVideoPlayer(value: String?) {
        context.pfpDataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(KEY_VIDEO_DEFAULT_PLAYER)
            else prefs[KEY_VIDEO_DEFAULT_PLAYER] = value
        }
    }
}
