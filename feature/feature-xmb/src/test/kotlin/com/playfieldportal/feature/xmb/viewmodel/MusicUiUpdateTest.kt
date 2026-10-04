package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.feature.xmb.music.MusicPlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * While the launcher is hidden, the half-second position ticks of in-app music are not copied into
 * the XMB state; anything else (track, play/pause, queue) still is.
 */
class MusicUiUpdateTest {

    private val playing = MusicPlaybackState(isPlaying = true, positionMs = 1_000, durationMs = 90_000, queueSize = 3)

    @Test
    fun `a position tick is skipped only while hidden`() {
        val tick = playing.copy(positionMs = 1_500)
        assertFalse(shouldApplyMusicUpdate(previous = playing, next = tick, hostVisible = false))
        assertTrue(shouldApplyMusicUpdate(previous = playing, next = tick, hostVisible = true))
    }

    @Test
    fun `a real change applies even while hidden`() {
        assertTrue(shouldApplyMusicUpdate(playing, playing.copy(isPlaying = false), hostVisible = false))
        assertTrue(shouldApplyMusicUpdate(playing, playing.copy(index = 1, positionMs = 0), hostVisible = false))
    }
}
