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

    // MimeTypeMap types .m3u as audio/mpegurl and .m3u8 as audio/x-mpegurl, so a DocumentsProvider
    // hands the scanner an "audio/" MIME for a plain text file listing other tracks. Before the
    // extension check moved ahead of the MIME check, every playlist in a music folder was imported
    // as a track and surfaced as a junk row named after the file.
    @Test
    fun `rejects playlists even when the provider calls them audio`() {
        assertFalse(AudioFileFilter.isAudio("Favourites.m3u", "audio/mpegurl"))
        assertFalse(AudioFileFilter.isAudio("Favourites.m3u8", "audio/x-mpegurl"))
        assertFalse(AudioFileFilter.isAudio("MIXTAPE.M3U8", "audio/x-mpegurl"))
    }

    @Test
    fun `rejects playlists when MIME is missing`() {
        for (ext in AudioFileFilter.PLAYLIST_EXTENSIONS) {
            assertFalse(AudioFileFilter.isAudio("list.$ext", null), "Expected .$ext not to be audio")
            assertTrue(AudioFileFilter.isPlaylist("list.$ext"), "Expected .$ext to be a playlist")
        }
    }

    @Test
    fun `a track is not a playlist`() {
        assertFalse(AudioFileFilter.isPlaylist("song.mp3"))
        assertFalse(AudioFileFilter.isPlaylist("song"))
    }
}
