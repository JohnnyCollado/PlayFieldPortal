package com.playfieldportal.core.ui.components

import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.domain.model.gamepadMappingsFor
import com.playfieldportal.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The end of the chain: settings → bindings → position → family art.
 *
 * [ControllerIconLookupTest] proves the position is right and
 * [ControllerIconResolverTest] proves the art is right; this proves the two
 * compose into the drawable a footer actually paints, which is the thing the
 * user sees and the thing that was wrong.
 */
class ControllerPromptStyleTest {

    private fun style(
        family: ControllerDisplayType,
        confirmBack: ConfirmBackLayout = ConfirmBackLayout.STANDARD,
        xy: XYLayout = XYLayout.STANDARD,
    ) = ControllerPromptStyle(family, gamepadMappingsFor(confirmBack, xy))

    /** What a footer ends up drawing for [action] under [this] style. */
    private fun ControllerPromptStyle.artFor(action: GamepadAction): Int? =
        mappings.iconFor(action)?.drawableForOrNull(family)

    @Test
    fun `the default style is a stock Xbox pad`() {
        // Previews and any composable rendered without a provider must still
        // produce a real prompt rather than an empty footer.
        val default = ControllerPromptStyle()
        assertEquals(ControllerDisplayType.XBOX, default.family)
        assertEquals(R.drawable.ctl_xb_face_south, default.artFor(GamepadAction.SELECT))
    }

    @Test
    fun `confirm draws each family's own bottom face button`() {
        assertEquals(R.drawable.ctl_ps_face_south, style(ControllerDisplayType.PLAYSTATION).artFor(GamepadAction.SELECT))
        assertEquals(R.drawable.ctl_xb_face_south, style(ControllerDisplayType.XBOX).artFor(GamepadAction.SELECT))
        assertEquals(R.drawable.ctl_ns_face_south, style(ControllerDisplayType.NINTENDO).artFor(GamepadAction.SELECT))
    }

    @Test
    fun `reversing Confirm-Back moves the art, for every family`() {
        for (family in ControllerDisplayType.entries) {
            val standard = style(family).artFor(GamepadAction.SELECT)
            val reversed = style(family, confirmBack = ConfirmBackLayout.REVERSED).artFor(GamepadAction.SELECT)
            assertNotNull(reversed)
            assert(standard != reversed) { "$family draws the same Confirm art in both layouts" }
            // Reversed Confirm must be exactly the art Back used to have.
            assertEquals(style(family).artFor(GamepadAction.BACK), reversed)
        }
    }

    @Test
    fun `swapping X-Y moves the options art, for every family`() {
        for (family in ControllerDisplayType.entries) {
            val standard = style(family).artFor(GamepadAction.OPEN_CONTEXT_MENU)
            val swapped = style(family, xy = XYLayout.SWAPPED).artFor(GamepadAction.OPEN_CONTEXT_MENU)
            assertNotNull(swapped)
            assert(standard != swapped) { "$family draws the same Options art in both layouts" }
            assertEquals(style(family).artFor(GamepadAction.CHANGE_SORT), swapped)
        }
    }

    @Test
    fun `Nintendo confirm is its B glyph, never the Xbox A glyph`() {
        // The reversal that a letter-keyed lookup gets wrong: Nintendo's bottom
        // face button is silkscreened B, and its art must come from its own pack.
        val ns = style(ControllerDisplayType.NINTENDO)
        assertEquals(R.drawable.ctl_ns_face_south, ns.artFor(GamepadAction.SELECT))
        assert(ns.artFor(GamepadAction.SELECT) != R.drawable.ctl_xb_face_south)
        assertEquals(R.drawable.ctl_ns_face_east, ns.artFor(GamepadAction.BACK))
    }

    @Test
    fun `the full drawer command bar paints distinct art in every configuration`() {
        val commandBar = listOf(
            GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY,
            GamepadAction.BACK, GamepadAction.SELECT,
            GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.CHANGE_SORT,
        )
        for (family in ControllerDisplayType.entries) {
            for (confirmBack in ConfirmBackLayout.entries) {
                for (xy in XYLayout.entries) {
                    val s = style(family, confirmBack, xy)
                    val art = commandBar.map { action ->
                        requireNotNull(s.artFor(action)) { "$action has no art for $family" }
                    }
                    assertEquals(
                        "$family/$confirmBack/$xy repeats a glyph in the command bar",
                        commandBar.size,
                        art.toSet().size,
                    )
                }
            }
        }
    }
}
