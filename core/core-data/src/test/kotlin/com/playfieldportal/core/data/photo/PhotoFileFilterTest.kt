package com.playfieldportal.core.data.photo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoFileFilterTest {

    @Test
    fun `accepts files whose MIME is image regardless of extension`() {
        assertTrue(PhotoFileFilter.isPhoto("shot", "image/jpeg"))
        assertTrue(PhotoFileFilter.isPhoto("weird.bin", "image/heic"))
    }

    @Test
    fun `rejects files whose MIME is not image`() {
        assertFalse(PhotoFileFilter.isPhoto("song.mp3", "audio/mpeg"))
        assertFalse(PhotoFileFilter.isPhoto("movie.mp4", "video/mp4"))
        assertFalse(PhotoFileFilter.isPhoto("notes.txt", "text/plain"))
    }

    @Test
    fun `falls back to extension when MIME is missing`() {
        for (ext in listOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "tif", "tiff")) {
            assertTrue(PhotoFileFilter.isPhoto("photo.$ext", null), "Expected .$ext to be a photo")
            assertTrue(PhotoFileFilter.isPhoto("PHOTO.${ext.uppercase()}", null))
        }
    }

    @Test
    fun `falls back to extension when MIME is the generic octet-stream`() {
        assertTrue(PhotoFileFilter.isPhoto("IMG_2041.jpg", "application/octet-stream"))
        assertFalse(PhotoFileFilter.isPhoto("archive.zip", "application/octet-stream"))
    }

    @Test
    fun `rejects non-image extensions when MIME is missing`() {
        assertFalse(PhotoFileFilter.isPhoto("clip.mkv", null))
        assertFalse(PhotoFileFilter.isPhoto("readme", null))
        assertFalse(PhotoFileFilter.isPhoto("track.mp3", null))
    }
}
