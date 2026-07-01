package com.playfieldportal.core.domain.repository

import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.domain.model.VideoLibrary
import kotlinx.coroutines.flow.Flow

/**
 * Source of truth for the Video library: user-added SAF libraries and the videos discovered by
 * manual quick/deep scans. Mirrors [MusicRepository]. Phase 1 keeps everything local; external
 * player preference and playlists are added in later phases.
 */
interface VideoRepository {

    // ── Libraries ───────────────────────────────────────────────────────────────
    fun observeLibraries(): Flow<List<VideoLibrary>>
    fun observeEnabledLibraries(): Flow<List<VideoLibrary>>
    suspend fun getLibraries(): List<VideoLibrary>
    suspend fun getLibrary(id: String): VideoLibrary?
    suspend fun addLibrary(displayName: String, treeUri: String, scanRecursively: Boolean = true): VideoLibrary
    suspend fun renameLibrary(id: String, displayName: String)
    suspend fun setLibraryEnabled(id: String, enabled: Boolean)
    suspend fun setLibraryArtwork(id: String, artworkUri: String?)
    /** Removes the library and all of its videos. */
    suspend fun removeLibrary(id: String)

    // ── Videos ────────────────────────────────────────────────────────────────
    fun observeAllVideos(): Flow<List<Video>>
    fun observeVideosByLibrary(libraryId: String): Flow<List<Video>>
    suspend fun getVideo(id: String): Video?
    suspend fun getVideosForLibrary(libraryId: String): List<Video>
    /** Atomically replaces the videos of one library only; other libraries are untouched. */
    suspend fun replaceVideosForLibrary(libraryId: String, videos: List<Video>, scannedAt: Long)

    // ── Playback state ──────────────────────────────────────────────────────────
    suspend fun setResumePosition(id: String, positionMs: Long, watchedAt: Long)
    suspend fun clearResumePosition(id: String)
    suspend fun setCustomTitle(id: String, title: String?)
    suspend fun setCustomThumbnail(id: String, uri: String?)
    suspend fun removeVideo(id: String)
}
