package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.PhotoDao
import com.playfieldportal.core.data.database.dao.PhotoLibraryDao
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.domain.model.Photo
import com.playfieldportal.core.domain.model.PhotoLibrary
import com.playfieldportal.core.domain.repository.PhotoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoRepositoryImpl @Inject constructor(
    private val libraryDao: PhotoLibraryDao,
    private val photoDao: PhotoDao,
) : PhotoRepository {

    override fun observeLibraries(): Flow<List<PhotoLibrary>> =
        libraryDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getLibraries(): List<PhotoLibrary> =
        libraryDao.getAll().map { it.toDomain() }

    override suspend fun getLibrary(id: String): PhotoLibrary? =
        libraryDao.getById(id)?.toDomain()

    override suspend fun addLibrary(
        displayName: String,
        treeUri: String,
        scanRecursively: Boolean,
    ): PhotoLibrary {
        val now = System.currentTimeMillis()
        val library = PhotoLibrary(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            treeUri = treeUri,
            enabled = true,
            scanRecursively = scanRecursively,
            createdAt = now,
            updatedAt = now,
        )
        libraryDao.upsert(library.toEntity())
        Timber.i("Photo library added: \"$displayName\" -> $treeUri")
        return library
    }

    override suspend fun renameLibrary(id: String, displayName: String) =
        libraryDao.setDisplayName(id, displayName, System.currentTimeMillis())

    override suspend fun setLibraryTreeUri(id: String, treeUri: String) =
        libraryDao.setTreeUri(id, treeUri, System.currentTimeMillis())

    override suspend fun setLibraryScanRecursively(id: String, scanRecursively: Boolean) =
        libraryDao.setScanRecursively(id, scanRecursively, System.currentTimeMillis())

    override suspend fun removeLibrary(id: String) {
        // Capture thumbnail uris before the rows go, so their cached files can be forgotten too.
        val thumbs = photoDao.getForLibrary(id).mapNotNull { it.thumbnailUri }
        // Photos cascade-delete via the foreign key, but delete explicitly too so behaviour is
        // identical whether or not foreign keys are enforced on the connection.
        photoDao.deleteForLibrary(id)
        libraryDao.delete(id)
        deleteOrphanedThumbnails(thumbs) { photoDao.countReferencingThumbnail(it) > 0 }
        Timber.i("Photo library removed: $id")
    }

    override fun observeAllPhotos(): Flow<List<Photo>> =
        photoDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observePhotosByLibrary(libraryId: String): Flow<List<Photo>> =
        photoDao.observeByLibrary(libraryId).map { list -> list.map { it.toDomain() } }

    override suspend fun getPhoto(id: String): Photo? =
        photoDao.getById(id)?.toDomain()

    override suspend fun getPhotosForLibrary(libraryId: String): List<Photo> =
        photoDao.getForLibrary(libraryId).map { it.toDomain() }

    override suspend fun replacePhotosForLibrary(
        libraryId: String,
        photos: List<Photo>,
        scannedAt: Long,
    ) {
        photoDao.replaceForLibrary(libraryId, photos.map { it.toEntity() })
        libraryDao.updateScanResult(libraryId, photos.size, scannedAt)
        Timber.i("Replaced ${photos.size} photos for library $libraryId")
    }

    override suspend fun removePhoto(id: String) {
        val thumb = photoDao.getById(id)?.thumbnailUri
        photoDao.deleteById(id)
        if (thumb != null) {
            deleteOrphanedThumbnails(listOf(thumb)) { photoDao.countReferencingThumbnail(it) > 0 }
        }
    }
}
