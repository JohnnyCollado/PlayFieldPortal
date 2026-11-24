package com.playfieldportal.core.ui.sound

import com.playfieldportal.core.domain.model.AudioChannel
import com.playfieldportal.core.domain.model.UiMediaSlot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MenuSoundResolutionTest {

    private val defaults = mapOf(
        UiMediaSlot.SOUND_SCROLL to 1,
        UiMediaSlot.SOUND_CATEGORY to 2,
        UiMediaSlot.SOUND_OPTIONS to 3,
    )

    @Test fun `category change and options have their own slot and channel`() {
        assertEquals(UiMediaSlot.SOUND_CATEGORY, MenuSound.SYSTEM_BROWSE.slot)
        assertEquals(AudioChannel.CATEGORY, MenuSound.SYSTEM_BROWSE.channel)
        assertEquals(UiMediaSlot.SOUND_OPTIONS, MenuSound.OPTIONS.slot)
        assertEquals(AudioChannel.OPTIONS, MenuSound.OPTIONS.channel)
        assertEquals(UiMediaSlot.SOUND_SCROLL, MenuSound.SELECT.slot)
    }

    @Test fun `an assigned category sample wins`() {
        val id = resolveSampleId(UiMediaSlot.SOUND_CATEGORY, mapOf(UiMediaSlot.SOUND_CATEGORY to 10, UiMediaSlot.SOUND_SCROLL to 11), defaults, setOf(10, 11))
        assertEquals(10, id)
    }

    @Test fun `unassigned category plays the users navigation sample`() {
        val id = resolveSampleId(UiMediaSlot.SOUND_CATEGORY, mapOf(UiMediaSlot.SOUND_SCROLL to 11), defaults, setOf(11))
        assertEquals(11, id)
    }

    @Test fun `unassigned everything plays the bundled default`() {
        assertEquals(3, resolveSampleId(UiMediaSlot.SOUND_OPTIONS, emptyMap(), defaults, emptySet()))
    }

    @Test fun `a custom sample that never loaded falls through`() {
        val id = resolveSampleId(UiMediaSlot.SOUND_OPTIONS, mapOf(UiMediaSlot.SOUND_OPTIONS to 20), defaults, emptySet())
        assertEquals(3, id)
    }

    @Test fun `navigation itself does not borrow from anyone`() {
        assertEquals(1, resolveSampleId(UiMediaSlot.SOUND_SCROLL, mapOf(UiMediaSlot.SOUND_CATEGORY to 10), defaults, setOf(10)))
        assertNull(resolveSampleId(UiMediaSlot.SOUND_BACK, emptyMap(), defaults, emptySet()))
    }
}
