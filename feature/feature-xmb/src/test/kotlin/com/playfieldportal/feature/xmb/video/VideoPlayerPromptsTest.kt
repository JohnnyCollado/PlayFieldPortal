package com.playfieldportal.feature.xmb.video

import com.playfieldportal.core.domain.model.GamepadAction
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The video player's controller footer on the helper-button standard: the players' shared ladder
 * (A play/pause, ◀/▶ seek, L1/R1 prev/next, Y options, B back), with Back always named, last.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoPlayerPromptsTest {

    private val res get() = RuntimeEnvironment.getApplication().resources

    @Test
    fun `back is named last`() {
        val items = videoPlayerPromptItems(isPlaying = true, hasPrevNext = true, res = res)
        assertEquals(listOf(GamepadAction.BACK), items.last().actions)
        assertEquals("Back", items.last().label)
    }

    @Test
    fun `the full ladder in order`() {
        assertEquals(
            listOf("Pause", "Seek", "Prev / Next", "Options", "Back"),
            videoPlayerPromptItems(isPlaying = true, hasPrevNext = true, res = res).map { it.label },
        )
    }

    @Test
    fun `prev and next are named only with a library to move through`() {
        assertEquals(
            listOf("Play", "Seek", "Options", "Back"),
            videoPlayerPromptItems(isPlaying = false, hasPrevNext = false, res = res).map { it.label },
        )
    }
}
