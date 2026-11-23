package com.playfieldportal.core.ui.detail

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.playfieldportal.core.ui.components.LocalPfpBackdropBehind
import com.playfieldportal.core.ui.components.PfpBackdropBlurState
import com.playfieldportal.core.ui.components.PfpConfirmModal
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A detail page is see-through: while it blurs itself under a prompt or panel, what shows through it
 * (the wallpaper) must blur too, or its sharp lines read through the blur like screen burn-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp")
class DetailScaffoldBlursBehindTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val behind = PfpBackdropBlurState()
    private val promptShown = mutableStateOf(false)

    private fun setUp() {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalPfpBackdropBehind provides behind) {
                    PfpDetailScaffold(
                        overlay = {
                            if (promptShown.value) {
                                PfpConfirmModal(
                                    title = "Delete", message = "Sure?", confirmLabel = "Delete",
                                    focus = PfpModalFocus.CANCEL, onConfirm = {}, onCancel = {},
                                )
                            }
                        },
                    ) {}
                }
            }
        }
    }

    @Test
    fun `the layer behind blurs while the page does, and stops with it`() {
        setUp()
        composeRule.waitForIdle()
        assertFalse(behind.active)

        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(behind.active)

        promptShown.value = false
        composeRule.waitForIdle()
        assertFalse(behind.active)
    }
}
