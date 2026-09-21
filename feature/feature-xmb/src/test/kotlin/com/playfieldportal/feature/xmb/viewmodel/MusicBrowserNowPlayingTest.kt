package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.MusicTrack
import com.playfieldportal.feature.xmb.music.MusicPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins what the browser's now-playing strip draws.
 *
 * The strip is the only thing on that screen that says what is playing, and it is fed from the
 * player's state flow — so "nothing loaded hides it" and "the song it names is the song the player
 * is on" are the two facts worth guarding.
 */
class MusicBrowserNowPlayingTest {

    private fun track(
        id: String = "t1",
        title: String? = "Aerith's Theme",
        displayName: String = "02 aeriths theme.mp3",
        artist: String? = "Nobuo Uematsu",
        artUri: String? = "content://art/1",
    ) = MusicTrack(
        id = id,
        folderId = "f",
        uri = "content://$id",
        displayName = displayName,
        title = title,
        artist = artist,
        artUri = artUri,
    )

    @Test
    fun `nothing loaded means no strip at all`() {
        assertNull(browserNowPlaying(MusicPlaybackState()))
    }

    @Test
    fun `the strip names the loaded song and carries its art`() {
        val strip = browserNowPlaying(MusicPlaybackState(track = track()))
        assertEquals("Aerith's Theme", strip?.title)
        assertEquals("Nobuo Uematsu", strip?.artist)
        assertEquals("content://art/1", strip?.artUri)
    }

    @Test
    fun `a file with no title falls back to its file name, as every other surface does`() {
        val strip = browserNowPlaying(MusicPlaybackState(track = track(title = null)))
        assertEquals("02 aeriths theme.mp3", strip?.title)
    }

    @Test
    fun `a track with no artist simply has none to draw`() {
        val strip = browserNowPlaying(MusicPlaybackState(track = track(artist = null)))
        assertNull(strip?.artist)
    }

    @Test
    fun `the transport state rides along, so the strip can show it`() {
        val playing = browserNowPlaying(MusicPlaybackState(track = track(), isPlaying = true))
        val paused = browserNowPlaying(MusicPlaybackState(track = track(), isPlaying = false))
        assertTrue(playing!!.isPlaying)
        assertFalse(paused!!.isPlaying)
    }

    @Test
    fun `position is not part of the strip, so ticks cannot repaint it`() {
        // The player re-publishes twice a second; a strip whose contents moved would repaint the
        // browser's whole column at that rate. Equality is the guard the ViewModel skips writes on.
        val atStart = browserNowPlaying(MusicPlaybackState(track = track(), positionMs = 0))
        val later = browserNowPlaying(MusicPlaybackState(track = track(), positionMs = 138_000))
        assertEquals(atStart, later)
    }
}
