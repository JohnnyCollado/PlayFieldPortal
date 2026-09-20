package com.playfieldportal.core.data.music

/**
 * Pure audio-file detection shared by the music scanner. A file counts as audio when its MIME type
 * starts with "audio/", or — when the MIME is missing/unknown — its extension is a known audio
 * type. Kept free of Android types so it can be unit-tested directly.
 */
object AudioFileFilter {

    val AUDIO_EXTENSIONS = setOf(
        "mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma",
    )

    /**
     * Playlists — files that REFERENCE tracks rather than containing any audio.
     *
     * These must be checked before the MIME test, not alongside it: Android's MimeTypeMap types
     * `.m3u` as `audio/mpegurl` and `.m3u8` as `audio/x-mpegurl`, and a DocumentsProvider reports
     * exactly that. Both pass `startsWith("audio/")`, so a plain MIME check imports every playlist
     * in a music folder as a track — one that then fails metadata extraction and shows up in the
     * library as a junk row named after the file.
     */
    val PLAYLIST_EXTENSIONS = setOf(
        "m3u", "m3u8", "pls", "wpl", "zpl", "pla", "xspf", "asx", "plist",
    )

    fun isAudio(fileName: String, mimeType: String?): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext in PLAYLIST_EXTENSIONS) return false
        if (mimeType != null) return mimeType.startsWith("audio/")
        return ext in AUDIO_EXTENSIONS
    }

    /** True for a playlist file — audio-adjacent, but never a track in its own right. */
    fun isPlaylist(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in PLAYLIST_EXTENSIONS
}
