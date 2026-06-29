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

    fun isAudio(fileName: String, mimeType: String?): Boolean {
        if (mimeType != null) return mimeType.startsWith("audio/")
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }
}
