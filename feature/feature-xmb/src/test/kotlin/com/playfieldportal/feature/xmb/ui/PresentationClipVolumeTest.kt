package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A boot or GameBoot clip's own audio track follows the Sound screen's Boot Sequence / GameBoot
 * level, exactly as the built-in chime does — and is silent only when a separate sound plays over it.
 */
class PresentationClipVolumeTest {

    @Test
    fun `a clip carrying its own track plays at the presentation's level`() {
        assertEquals(0.25f, presentationClipVolume(separateAudio = false, gain = 0.25f), 0f)
        assertEquals(0f, presentationClipVolume(separateAudio = false, gain = 0f), 0f)
    }

    @Test
    fun `a clip under a separate sound stays muted whatever the level`() {
        assertEquals(0f, presentationClipVolume(separateAudio = true, gain = 1f), 0f)
    }
}
