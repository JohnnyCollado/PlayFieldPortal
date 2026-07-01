package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.VideoDao
import com.playfieldportal.core.data.database.dao.VideoLibraryDao
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.domain.model.VideoLibrary
import com.playfieldportal.core.domain.repository.VideoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VideoRepositoryImpl @Inject constructor(
    private val libraryDao: VideoLibraryDao,
    private val videoDao: VideoDao,
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
        // Videos cascade-delete via the foreign key, but delete explicitly too so behaviour is
        // identical whether or not foreign keys are enforced on the connection.
        videoDao.deleteForLibrary(id)
        libraryDao.delete(id)
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

    override suspend fun removeVideo(id: String) =
        videoDao.deleteById(id)
}
