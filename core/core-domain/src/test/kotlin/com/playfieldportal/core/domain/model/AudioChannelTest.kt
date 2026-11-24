package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The new Category and Options levels follow Navigation until the user sets their own. */
class AudioChannelTest {

    @Test fun `category and options follow navigation`() {
        assertEquals(AudioChannel.NAVIGATION, AudioChannel.CATEGORY.fallback)
        assertEquals(AudioChannel.NAVIGATION, AudioChannel.OPTIONS.fallback)
        for (c in AudioChannel.entries - setOf(AudioChannel.CATEGORY, AudioChannel.OPTIONS)) {
            assertNull(c.fallback, "${c.key} has always had a level of its own")
        }
    }

    @Test fun `an unset category level reads navigation's stored level`() {
        val stored = mapOf(AudioChannel.NAVIGATION to 0.4f)
        assertEquals(0.4f, AudioChannel.CATEGORY.resolveLevel { stored[it] })
        assertEquals(0.4f, AudioChannel.OPTIONS.resolveLevel { stored[it] })
    }

    @Test fun `a category level of its own wins over navigation's`() {
        val stored = mapOf(AudioChannel.NAVIGATION to 0.4f, AudioChannel.CATEGORY to 0.9f)
        assertEquals(0.9f, AudioChannel.CATEGORY.resolveLevel { stored[it] })
        assertEquals(0.4f, AudioChannel.OPTIONS.resolveLevel { stored[it] })
    }

    @Test fun `nothing stored anywhere resolves to null so the caller applies its default`() {
        assertNull(AudioChannel.CATEGORY.resolveLevel { null })
        assertNull(AudioChannel.NAVIGATION.resolveLevel { null })
    }

    @Test fun `new channels have backup keys`() {
        assertTrue("volume_category" in AudioChannel.ALL_PREFERENCE_KEYS)
        assertTrue("volume_options" in AudioChannel.ALL_PREFERENCE_KEYS)
    }
}
