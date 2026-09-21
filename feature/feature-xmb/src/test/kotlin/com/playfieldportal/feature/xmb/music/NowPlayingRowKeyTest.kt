package com.playfieldportal.feature.xmb.music

import com.playfieldportal.core.domain.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NowPlayingRowKeyTest {

    private fun track(
        id: String = "t1",
        title: String? = "Aerith's Theme",
        artist: String? = "Nobuo Uematsu",
        artUri: String? = "file:///art/t1.png",
        album: String? = "FFVII OST",
    ) = MusicTrack(
        id = id,
        folderId = "f1",
        uri = "content://tracks/$id",
        displayName = "$id.mp3",
        title = title,
        artist = artist,
        album = album,
        artUri = artUri,
    )

    private fun playing(
        track: MusicTrack? = track(),
        isPlaying: Boolean = true,
        positionMs: Int = 0,
        durationMs: Int = 240_000,
    ) = MusicPlaybackState(
        track = track,
        isPlaying = isPlaying,
        positionMs = positionMs,
        durationMs = durationMs,
        index = 0,
        queueSize = 3,
        isPrepared = true,
    )

    @Test fun `no track means no row`() {
        assertNull(nowPlayingRowKey(MusicPlaybackState()))
    }

    // The bug this key exists to fix: the row used to be keyed on presence alone, so advancing
    // from one song to the next left it showing the previous title, artist and art.
    @Test fun `changing the song changes the key`() {
        assertNotEquals(
            nowPlayingRowKey(playing(track(id = "t1", title = "Aerith's Theme"))),
            nowPlayingRowKey(playing(track(id = "t2", title = "One-Winged Angel"))),
        )
    }

    @Test fun `position ticks pause and seek leave the key alone`() {
        val start = nowPlayingRowKey(playing(positionMs = 0))
        assertEquals(start, nowPlayingRowKey(playing(positionMs = 500)))
        assertEquals(start, nowPlayingRowKey(playing(positionMs = 138_000)))
        assertEquals(start, nowPlayingRowKey(playing(isPlaying = false)))
    }

    @Test fun `a rescan that changes displayed metadata changes the key`() {
        val before = nowPlayingRowKey(playing(track(artist = null, artUri = null)))
        assertNotEquals(before, nowPlayingRowKey(playing(track(artist = "Nobuo Uematsu"))))
        assertNotEquals(before, nowPlayingRowKey(playing(track(artist = null))))
    }

    @Test fun `starting and stopping playback flips the key to and from null`() {
        assertNull(nowPlayingRowKey(playing(track = null)))
        assertNotEquals(null, nowPlayingRowKey(playing()))
    }

    // The notification carries fields the XMB row doesn't, so it needs the wider key...
    @Test fun `notification key covers album duration and transport state`() {
        val base = nowPlayingNotificationKey(playing())
        assertNotEquals(base, nowPlayingNotificationKey(playing(track(album = "Reunion Tracks"))))
        assertNotEquals(base, nowPlayingNotificationKey(playing(durationMs = 241_000)))
        assertNotEquals(base, nowPlayingNotificationKey(playing(isPlaying = false)))
    }

    // ...but not the position: the posted PlaybackState carries that, and the system
    // extrapolates the seek bar between updates.
    @Test fun `notification key ignores position`() {
        assertEquals(
            nowPlayingNotificationKey(playing(positionMs = 0)),
            nowPlayingNotificationKey(playing(positionMs = 137_400)),
        )
    }
}
