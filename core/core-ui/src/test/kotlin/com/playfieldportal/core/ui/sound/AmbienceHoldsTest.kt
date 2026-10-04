package com.playfieldportal.core.ui.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbienceHoldsTest {

    @Test fun `nothing held means ambience may play`() {
        val holds = AmbienceHolds()
        assertFalse(holds.isHeld)
        assertEquals(emptyList<String>(), holds.owners)
    }

    @Test fun `a hold keeps ambience down until it is released`() {
        val holds = AmbienceHolds()
        val hold = holds.hold(AmbienceController.OWNER_VIDEO)
        assertTrue(holds.isHeld)
        hold.release()
        assertFalse(holds.isHeld)
    }

    @Test fun `releasing one hold never ends another`() {
        val holds = AmbienceHolds()
        val video = holds.hold(AmbienceController.OWNER_VIDEO)
        val music = holds.hold(AmbienceController.OWNER_MUSIC)
        video.release()
        assertTrue("music still holds the room", holds.isHeld)
        assertEquals(listOf(AmbienceController.OWNER_MUSIC), holds.owners)
        music.release()
        assertFalse(holds.isHeld)
    }

    @Test fun `two holds under the same owner name are still two holds`() {
        // The boot and GameBoot overlays once shared an owner name, so the first to leave released
        // the other's hold. Holds are handles now: the name is only a label.
        val holds = AmbienceHolds()
        val first = holds.hold("one")
        val second = holds.hold("one")
        first.release()
        assertTrue(holds.isHeld)
        second.release()
        assertFalse(holds.isHeld)
    }

    @Test fun `a release is idempotent`() {
        val holds = AmbienceHolds()
        val first = holds.hold(AmbienceController.OWNER_GAME)
        val second = holds.hold(AmbienceController.OWNER_GAME)
        first.release()
        first.release()
        assertTrue("a second release of the same handle must not end the other", holds.isHeld)
        second.release()
        assertFalse(holds.isHeld)
    }

    @Test fun `boot and GameBoot hold under their own names`() {
        assertTrue(AmbienceController.OWNER_BOOT != AmbienceController.OWNER_GAMEBOOT)
    }
}
