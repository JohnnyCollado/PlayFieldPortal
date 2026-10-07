package com.playfieldportal.feature.xmb.video

import com.playfieldportal.core.domain.model.GamepadAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The video player's controller footer on the helper-button standard: the players' shared ladder
 * (A play/pause, ◀/▶ seek, L1/R1 prev/next, Y options, B back), with Back always named, last.
 */
class VideoPlayerPromptsTest {

    @Test
    fun `back is named last`() {
        val items = videoPlayerPromptItems(isPlaying = true, hasPrevNext = true)
        assertEquals(listOf(GamepadAction.BACK), items.last().actions)
        assertEquals("Back", items.last().label)
    }

    @Test
    fun `the full ladder in order`() {
        assertEquals(
            listOf("Pause", "Seek", "Prev / Next", "Options", "Back"),
            videoPlayerPromptItems(isPlaying = true, hasPrevNext = true).map { it.label },
        )
    }

    @Test
    fun `prev and next are named only with a library to move through`() {
        assertEquals(
            listOf("Play", "Seek", "Options", "Back"),
            videoPlayerPromptItems(isPlaying = false, hasPrevNext = false).map { it.label },
        )
    }
}
