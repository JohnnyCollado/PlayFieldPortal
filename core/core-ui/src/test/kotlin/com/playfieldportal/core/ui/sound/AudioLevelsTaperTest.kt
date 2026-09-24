package com.playfieldportal.core.ui.sound

import com.playfieldportal.core.domain.model.AudioChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the one function every launcher sound is multiplied by.
 *
 * The taper lives in exactly one place precisely so eight call sites cannot each get it slightly
 * different; these tests are what make that claim enforceable rather than aspirational. The
 * endpoints matter most: **0 must be silence** (master at 0 is the app's only mute, so a taper
 * that returned 0.0001 would leave a launcher that cannot be silenced) and **1 must be unchanged**
 * (an install that has never opened the screen has to sound exactly as it did before volume
 * existed).
 */
class AudioLevelsTaperTest {

    @Test fun `zero at either end is silence`() {
        assertEquals(0f, AudioLevels.taper(0f, 1f), "master at 0 is the mute")
        assertEquals(0f, AudioLevels.taper(1f, 0f), "a channel at 0 is off")
        assertEquals(0f, AudioLevels.taper(0f, 0f))
    }

    @Test fun `full at both ends passes the sample through untouched`() {
        assertEquals(1f, AudioLevels.taper(1f, 1f))
    }

    @Test fun `the curve is below linear in the middle - that is the point`() {
        // Square law: half travel lands at a quarter amplitude, which is roughly where the ear
        // expects "half as loud". A linear map would return 0.5 and sound closer to three
        // quarters, which is what made the slider feel broken at the bottom of its range.
        assertEquals(0.25f, AudioLevels.taper(1f, 0.5f), 1e-6f)
        assertTrue(AudioLevels.taper(1f, 0.5f) < 0.5f)
    }

    @Test fun `master and channel compose - neither can escape the other`() {
        // Half master under half channel is a quarter linear, squared to a sixteenth. The
        // ordering must not matter: the two percentages are symmetric by construction.
        assertEquals(AudioLevels.taper(0.5f, 0.8f), AudioLevels.taper(0.8f, 0.5f), 1e-6f)
        assertEquals(0.0625f, AudioLevels.taper(0.5f, 0.5f), 1e-6f)
    }

    @Test fun `out-of-range input is clamped rather than amplified`() {
        // A corrupt pref must never make the launcher louder than the user's hardware volume
        // implies, and must never produce a negative gain the player would reject.
        assertEquals(1f, AudioLevels.taper(2f, 2f))
        assertEquals(0f, AudioLevels.taper(-1f, 1f))
        assertEquals(0f, AudioLevels.taper(1f, -1f))
    }

    @Test fun `the curve never decreases as either input rises`() {
        var previous = -1f
        for (step in 0..100) {
            val gain = AudioLevels.taper(1f, step / 100f)
            assertTrue(gain >= previous, "taper dipped at $step%")
            previous = gain
        }
    }

    @Test fun `every channel has a distinct preference key under one prefix`() {
        // The keys are storage contract: a collision would silently tie two sliders together, and
        // a rename would reset that channel to full on every existing install.
        val keys = AudioChannel.ALL_PREFERENCE_KEYS
        assertEquals(keys.size, keys.distinct().size, "a duplicate key ties two levels together")
        assertEquals(AudioChannel.entries.size + 1, keys.size, "master plus one per channel")
        assertTrue(keys.all { it.startsWith("volume_") })
    }
}
