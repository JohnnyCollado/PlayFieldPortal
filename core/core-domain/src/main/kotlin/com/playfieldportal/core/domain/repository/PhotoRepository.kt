package com.playfieldportal.core.domain.repository

import com.playfieldportal.core.domain.model.Photo
import com.playfieldportal.core.domain.model.PhotoLibrary
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for the Photo section: user-added SAF libraries (Albums) and the photos
 * discovered by manual quick/deep scans. Mirrors [VideoRepository], deliberately minimal — no
 * favorites, playlists, or recents; the Photo category stays a simple PSP-style memory card.
 */
interface PhotoRepository {

    // ── Libraries (Albums) ──────────────────────────────────────────────────────
    fun observeLibraries(): Flow<List<PhotoLibrary>>
    suspend fun getLibraries(): List<PhotoLibrary>
    suspend fun getLibrary(id: String): PhotoLibrary?
    /** Adds an Album. Recursive by default; "Include Subfolders" can limit it per Album. */
    suspend fun addLibrary(displayName: String, treeUri: String, scanRecursively: Boolean = true): PhotoLibrary
    suspend fun renameLibrary(id: String, displayName: String)
    /** Per Album: when disabled, scans read only the picked folder's own photos. */
    suspend fun setLibraryScanRecursively(id: String, scanRecursively: Boolean)
    /** Points the library at a different folder; the next scan replaces its photos. */
    suspend fun setLibraryTreeUri(id: String, treeUri: String)
    /** Removes the library and all of its photo rows. Never touches the files on disk. */
    suspend fun removeLibrary(id: String)

    // ── Photos ────────────────────────────────────────────────────────────────
    fun observeAllPhotos(): Flow<List<Photo>>
    fun observePhotosByLibrary(libraryId: String): Flow<List<Photo>>
    suspend fun getPhoto(id: String): Photo?
    suspend fun getPhotosForLibrary(libraryId: String): List<Photo>
    /** Atomically replaces the photos of one library only; other libraries are untouched. */
    suspend fun replacePhotosForLibrary(libraryId: String, photos: List<Photo>, scannedAt: Long)
    /** Removes one photo row from its library. Never deletes the file on disk. */
    suspend fun removePhoto(id: String)
}
