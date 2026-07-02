package com.playfieldportal.core.data.photo

/**
 * Pure image-file detection shared by the photo scanner. A file counts as a photo when its MIME
 * type starts with "image/", or — when the MIME is missing/unknown — its extension is a known
 * image type. Kept free of Android types so it can be unit-tested directly. Mirrors
 * [VideoFileFilter].
 */
object PhotoFileFilter {

    val IMAGE_EXTENSIONS = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "bmp",
        "heic", "heif", "tif", "tiff",
    )

    fun isPhoto(fileName: String, mimeType: String?): Boolean {
        if (mimeType != null && mimeType != "application/octet-stream") {
            return mimeType.startsWith("image/")
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext in IMAGE_EXTENSIONS
    }
}
