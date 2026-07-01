package com.playfieldportal.core.data.video

/**
 * Pure video-file detection shared by the video scanner. A file counts as video when its MIME type
 * starts with "video/", or — when the MIME is missing/unknown — its extension is a known video
 * type. Kept free of Android types so it can be unit-tested directly. Mirrors [AudioFileFilter].
 */
object VideoFileFilter {

    val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv",
        "ts", "m2ts", "mts", "mpg", "mpeg", "3gp", "ogv",
    )

    fun isVideo(fileName: String, mimeType: String?): Boolean {
        if (mimeType != null && mimeType != "application/octet-stream") {
            return mimeType.startsWith("video/")
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in VIDEO_EXTENSIONS
    }
}
