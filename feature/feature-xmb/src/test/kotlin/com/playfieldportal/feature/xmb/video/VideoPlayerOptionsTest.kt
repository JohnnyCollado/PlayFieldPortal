package com.playfieldportal.feature.xmb.video

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PspMenuOutcome
import com.playfieldportal.core.ui.components.PspMenuNav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Video Player's options panel: value rows that open › sub-lists, the active choice checked. */
class VideoPlayerOptionsTest {

    private val state = VideoOptionsState(
        speed = 1.5f,
        screenModeLabel = "Zoom",
        subtitleChoices = listOf(
            VideoTrackChoice("Off", selected = false),
            VideoTrackChoice("EN", selected = true),
            VideoTrackChoice("FR", selected = false),
        ),
        audioChoices = listOf(
            VideoTrackChoice("EN", selected = false),
            VideoTrackChoice("JA", selected = true),
        ),
    )

    @Test
    fun `root rows are the four options, each with a value and opening a list`() {
        val rows = videoPlayerOptionRows(state, group = null)
        assertEquals(listOf("Playback Speed", "Subtitles", "Audio Track", "Screen Mode"), rows.map { it.label })
        assertEquals(listOf("1.5×", "EN", "JA", "Zoom"), rows.map { it.value })
        assertTrue(rows.all { it.opensMenu })
    }

    @Test
    fun `root values fall back when a track type has nothing to choose`() {
        val empty = state.copy(subtitleChoices = emptyList(), audioChoices = emptyList())
        val rows = videoPlayerOptionRows(empty, group = null)
        assertEquals("None", rows[1].value)
        assertEquals("Default", rows[2].value)
    }

    @Test
    fun `subtitles off shows Off`() {
        val off = state.copy(
            subtitleChoices = listOf(VideoTrackChoice("Off", true), VideoTrackChoice("EN", false)),
        )
        assertEquals("Off", videoPlayerOptionRows(off, group = null)[1].value)
    }

    @Test
    fun `speed list checks the active speed and opens nothing`() {
        val rows = videoPlayerOptionRows(state, VideoOptionGroup.SPEED)
        assertEquals(listOf("0.5×", "0.75×", "1×", "1.25×", "1.5×", "2×"), rows.map { it.label })
        assertEquals(listOf(4), rows.indices.filter { rows[it].checked })
        assertFalse(rows.any { it.opensMenu })
    }

    @Test
    fun `track lists check the selected choice`() {
        val subs = videoPlayerOptionRows(state, VideoOptionGroup.SUBTITLES)
        assertEquals(listOf("Off", "EN", "FR"), subs.map { it.label })
        assertEquals(listOf(1), subs.indices.filter { subs[it].checked })
        val audio = videoPlayerOptionRows(state, VideoOptionGroup.AUDIO)
        assertEquals(listOf(1), audio.indices.filter { audio[it].checked })
    }

    @Test
    fun `screen mode list checks the active mode`() {
        val rows = videoPlayerOptionRows(state, VideoOptionGroup.SCREEN_MODE)
        assertEquals(listOf("Fit", "Zoom", "Fill"), rows.map { it.label })
        assertEquals(listOf(1), rows.indices.filter { rows[it].checked })
    }

    @Test
    fun `track choices list off first for subtitles and mark the selected track`() {
        val tracks = listOf(
            VideoTrackInfo(language = "en", label = null, selected = false),
            VideoTrackInfo(language = null, label = "Commentary", selected = true),
            VideoTrackInfo(language = null, label = null, selected = false),
        )
        val subs = videoTrackChoices(tracks, allowOff = true, trackTypeDisabled = false)
        assertEquals(listOf("Off", "EN", "Commentary", "Track"), subs.map { it.label })
        assertEquals(listOf(2), subs.indices.filter { subs[it].selected })
        val audio = videoTrackChoices(tracks, allowOff = false, trackTypeDisabled = false)
        assertEquals(listOf("EN", "Commentary", "Track"), audio.map { it.label })
    }

    @Test
    fun `a disabled text type selects Off, and no tracks gives no choices`() {
        val tracks = listOf(VideoTrackInfo("en", null, selected = false))
        val subs = videoTrackChoices(tracks, allowOff = true, trackTypeDisabled = true)
        assertEquals(listOf(0), subs.indices.filter { subs[it].selected })
        assertTrue(videoTrackChoices(emptyList(), allowOff = true, trackTypeDisabled = false).isEmpty())
    }

    @Test
    fun `Back in a list climbs and at the root closes`() {
        assertEquals(PspMenuOutcome.Up, PspMenuNav.resolve(GamepadAction.BACK, 2, 3, depth = 1))
        assertEquals(PspMenuOutcome.Close, PspMenuNav.resolve(GamepadAction.BACK, 2, 4, depth = 0))
    }
}
