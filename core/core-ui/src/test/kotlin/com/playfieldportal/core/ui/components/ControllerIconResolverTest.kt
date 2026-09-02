package com.playfieldportal.core.ui.components

import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the physical-position → per-family art mapping.
 *
 * The mapping regressed once already: a printed-letter lookup collapsed "B" and
 * "A" onto the same position, so the App Drawer footer drew two identical
 * glyphs. These tests exist so a position can never silently resolve to the
 * wrong family's letter again.
 */
class ControllerIconResolverTest {

    // ── Face positions: the reversal that makes letters unusable as keys ─────

    @Test
    fun `south face is Cross, A, and B respectively`() {
        assertEquals(
            R.drawable.ctl_ps_face_south,
            ControllerIcon.FACE_SOUTH.drawableForOrNull(ControllerDisplayType.PLAYSTATION),
        )
        assertEquals(
            R.drawable.ctl_xb_face_south,
            ControllerIcon.FACE_SOUTH.drawableForOrNull(ControllerDisplayType.XBOX),
        )
        assertEquals(
            R.drawable.ctl_ns_face_south,
            ControllerIcon.FACE_SOUTH.drawableForOrNull(ControllerDisplayType.NINTENDO),
        )
    }

    @Test
    fun `Nintendo mirrors Xbox on both face axes`() {
        // Same physical position, opposite silkscreen — the whole reason the
        // resolver is keyed on position instead of letter.
        assertEquals("A", ControllerIcon.FACE_SOUTH.printedLabelFor(ControllerDisplayType.XBOX))
        assertEquals("B", ControllerIcon.FACE_SOUTH.printedLabelFor(ControllerDisplayType.NINTENDO))
        assertEquals("B", ControllerIcon.FACE_EAST.printedLabelFor(ControllerDisplayType.XBOX))
        assertEquals("A", ControllerIcon.FACE_EAST.printedLabelFor(ControllerDisplayType.NINTENDO))
        assertEquals("X", ControllerIcon.FACE_WEST.printedLabelFor(ControllerDisplayType.XBOX))
        assertEquals("Y", ControllerIcon.FACE_WEST.printedLabelFor(ControllerDisplayType.NINTENDO))
        assertEquals("Y", ControllerIcon.FACE_NORTH.printedLabelFor(ControllerDisplayType.XBOX))
        assertEquals("X", ControllerIcon.FACE_NORTH.printedLabelFor(ControllerDisplayType.NINTENDO))
    }

    @Test
    fun `no family ever borrows another family's art`() {
        val families = ControllerDisplayType.entries
        for (icon in ControllerIcon.entries) {
            val resolved = families.mapNotNull { icon.drawableForOrNull(it) }
            assertEquals(
                "$icon resolves to shared art across families",
                resolved.size,
                resolved.toSet().size,
            )
        }
    }

    @Test
    fun `the four face positions are distinct within every family`() {
        val faces = listOf(
            ControllerIcon.FACE_SOUTH, ControllerIcon.FACE_EAST,
            ControllerIcon.FACE_WEST, ControllerIcon.FACE_NORTH,
        )
        for (family in ControllerDisplayType.entries) {
            val art = faces.map { it.drawableForOrNull(family) }
            assertEquals("$family draws a face position twice", 4, art.toSet().size)
        }
    }

    // ── Every family supports the full command-bar vocabulary ────────────────

    @Test
    fun `command bar positions resolve for all three families`() {
        val commandBar = listOf(
            ControllerIcon.FACE_SOUTH, ControllerIcon.FACE_EAST,
            ControllerIcon.FACE_WEST, ControllerIcon.FACE_NORTH,
            ControllerIcon.BUMPER_LEFT, ControllerIcon.BUMPER_RIGHT,
        )
        for (family in ControllerDisplayType.entries) {
            for (icon in commandBar) {
                assertNotNull("$icon missing for $family", icon.drawableForOrNull(family))
            }
        }
    }

    // ── Family-exclusive inputs degrade instead of crashing ──────────────────

    @Test
    fun `touchpad is PlayStation-only and returns null elsewhere`() {
        assertNotNull(ControllerIcon.TOUCHPAD.drawableForOrNull(ControllerDisplayType.PLAYSTATION))
        assertNotNull(ControllerIcon.TOUCHPAD_LEFT.drawableForOrNull(ControllerDisplayType.PLAYSTATION))
        assertNotNull(ControllerIcon.TOUCHPAD_RIGHT.drawableForOrNull(ControllerDisplayType.PLAYSTATION))
        assertNull(ControllerIcon.TOUCHPAD.drawableForOrNull(ControllerDisplayType.XBOX))
        assertNull(ControllerIcon.TOUCHPAD.drawableForOrNull(ControllerDisplayType.NINTENDO))
    }

    @Test
    fun `Switch-exclusive inputs return null on the other families`() {
        val switchOnly = listOf(
            ControllerIcon.GAME_CHAT, ControllerIcon.CAMERA,
            ControllerIcon.PADDLE_LEFT, ControllerIcon.PADDLE_RIGHT,
            ControllerIcon.JOYCON_SL, ControllerIcon.JOYCON_SR,
        )
        for (icon in switchOnly) {
            assertNotNull("$icon missing for Nintendo", icon.drawableForOrNull(ControllerDisplayType.NINTENDO))
            assertNull(icon.drawableForOrNull(ControllerDisplayType.PLAYSTATION))
            assertNull(icon.drawableForOrNull(ControllerDisplayType.XBOX))
        }
    }

    @Test
    fun `every icon that resolves to art also has a printed label or is stick-or-dpad art`() {
        // Sticks and D-pads are drawn, never lettered, so they are exempt.
        val unlettered = setOf(
            ControllerIcon.DPAD_UP, ControllerIcon.DPAD_DOWN, ControllerIcon.DPAD_LEFT,
            ControllerIcon.DPAD_RIGHT, ControllerIcon.DPAD_ALL,
            ControllerIcon.STICK_LEFT, ControllerIcon.STICK_RIGHT,
        )
        for (family in ControllerDisplayType.entries) {
            for (icon in ControllerIcon.entries) {
                if (icon.drawableForOrNull(family) == null || icon in unlettered) continue
                assertNotNull("$icon has art but no label for $family", icon.printedLabelFor(family))
            }
        }
    }
}
