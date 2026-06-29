package com.playfieldportal.core.data.music

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioFileFilterTest {

    @Test
    fun `accepts files whose MIME is audio regardless of extension`() {
        assertTrue(AudioFileFilter.isAudio("track", "audio/mpeg"))
        assertTrue(AudioFileFilter.isAudio("weird.bin", "audio/flac"))
    }

    @Test
    fun `rejects files whose MIME is not audio`() {
        assertFalse(AudioFileFilter.isAudio("cover.jpg", "image/jpeg"))
        assertFalse(AudioFileFilter.isAudio("movie.mp4", "video/mp4"))
        assertFalse(AudioFileFilter.isAudio("notes.txt", "text/plain"))
    }

    @Test
    fun `falls back to extension when MIME is missing`() {
        for (ext in listOf("mp3", "flac", "ogg", "opus", "m4a", "aac", "wav", "wma")) {
            assertTrue(AudioFileFilter.isAudio("song.$ext", null), "Expected .$ext to be audio")
            assertTrue(AudioFileFilter.isAudio("SONG.${ext.uppercase()}", null))
        }
    }

    @Test
    fun `rejects non-audio extensions when MIME is missing`() {
        assertFalse(AudioFileFilter.isAudio("art.png", null))
        assertFalse(AudioFileFilter.isAudio("readme", null))
        assertFalse(AudioFileFilter.isAudio("clip.mkv", null))
    }
}
