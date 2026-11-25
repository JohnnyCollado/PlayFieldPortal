package com.playfieldportal.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A prompt lays the PSP-style silver veil over the screen: silver at 75% over whatever is behind.
 * Drawn over black here, a spot clear of the band reads silver x 0.75. The options menu does not:
 * it keeps its own light scrim and blurs the screen instead.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class PromptVeilTest {

    @get:Rule
    val composeRule = createComposeRule()

    // 0xC6CAD2 at 75% over black.
    private val expected = Triple(0xC6 * 0.75f, 0xCA * 0.75f, 0xD2 * 0.75f)

    private fun sample(content: @androidx.compose.runtime.Composable () -> Unit): Color {
        composeRule.setContent {
            PFPTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
            }
        }
        composeRule.mainClock.advanceTimeBy(2_000) // past the fade-in
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage().toPixelMap()
        // Top-left corner: away from the centred prompt band and the menu panel on the right.
        return image[8, 8]
    }

    private fun assertVeil(c: Color) {
        val (r, g, b) = Triple(c.red * 255, c.green * 255, c.blue * 255)
        val ok = kotlin.math.abs(r - expected.first) <= 4 && kotlin.math.abs(g - expected.second) <= 4 &&
            kotlin.math.abs(b - expected.third) <= 4
        assertTrue("expected silver veil ~(${expected.first.toInt()}, ${expected.second.toInt()}, ${expected.third.toInt()}), got (${r.toInt()}, ${g.toInt()}, ${b.toInt()})", ok)
    }

    @Test
    fun `a prompt lays the silver veil over the screen`() {
        assertVeil(sample {
            PfpConfirmModal(
                title = "Uninstall CPU-Z?", message = "Sure?", confirmLabel = "Uninstall",
                focus = PfpModalFocus.CANCEL, onConfirm = {}, onCancel = {},
            )
        })
    }

    @Test
    fun `the options menu keeps its own light scrim, not the silver (it blurs instead)`() {
        val c = sample {
            PspContextMenuOverlay(
                title = "CPU-Z",
                rows = listOf(PspMenuRow("Open"), PspMenuRow("Uninstall", isDestructive = true)),
                selectedIndex = 0,
                onRowActivated = {},
                onDismiss = {},
            )
        }
        // A black scrim over black stays black; the silver would read ~(148, 151, 157).
        assertTrue("menu backdrop should not be silver: $c", c.red < 0.1f && c.green < 0.1f && c.blue < 0.1f)
    }
}
