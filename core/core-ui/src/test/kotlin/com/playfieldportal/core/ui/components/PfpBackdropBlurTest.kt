package com.playfieldportal.core.ui.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The backdrop flags: the options panel asks for the blur (API 31+, switchable); a prompt asks the
 * host to hide what is behind it instead, and that wins over the blur while the prompt is up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class PfpBackdropBlurTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val state = PfpBackdropBlurState()
    private val promptShown = mutableStateOf(false)
    private val panelShown = mutableStateOf(false)
    private val allowBlur = mutableStateOf(true)

    private fun setUp() {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalPfpBackdropBlur provides state) {
                    if (promptShown.value) {
                        PfpConfirmModal(
                            title = "Delete",
                            message = "Sure?",
                            confirmLabel = "Delete",
                            focus = PfpModalFocus.CANCEL,
                            onConfirm = {},
                            onCancel = {},
                        )
                    }
                    if (panelShown.value) {
                        PspContextMenuOverlay(
                            title = "Options",
                            rows = listOf(PspMenuRow("Play")),
                            selectedIndex = 0,
                            onRowActivated = {},
                            onDismiss = {},
                            allowBlur = allowBlur.value,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun `nothing is requested while nothing is shown`() {
        setUp()
        assertFalse(state.requested)
        assertFalse(state.active)
    }

    @Test
    fun `a prompt hides the screen behind it while shown, and does not blur it`() {
        setUp()
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(state.hidden)
        assertFalse("a prompt hides; only menus blur", state.active)

        promptShown.value = false
        composeRule.waitForIdle()
        assertFalse(state.hidden)
        assertFalse(state.requested)
    }

    @Test
    fun `hiding behind a prompt ignores the blur switch, since it costs nothing`() {
        setUp()
        state.enabled = false
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(state.hidden)
    }

    @Test
    fun `the options panel raises the flag while shown`() {
        setUp()
        panelShown.value = true
        composeRule.waitForIdle()
        assertTrue(state.active)

        panelShown.value = false
        composeRule.waitForIdle()
        assertFalse(state.requested)
    }

    @Test
    fun `a prompt over a panel hides, and closing the prompt brings the panel's blur back`() {
        setUp()
        panelShown.value = true
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(state.hidden)
        assertFalse(state.active)
        promptShown.value = false
        composeRule.waitForIdle()
        assertFalse(state.hidden)
        assertTrue("the panel still wants its blur", state.active)
        panelShown.value = false
        composeRule.waitForIdle()
        assertFalse(state.requested)
    }

    @Test
    fun `allowBlur false keeps the flat tint`() {
        allowBlur.value = false
        setUp()
        panelShown.value = true
        composeRule.waitForIdle()
        assertFalse(state.requested)
    }

    @Test
    fun `the host can switch the blur off while the panel is up`() {
        setUp()
        panelShown.value = true
        composeRule.waitForIdle()
        state.enabled = false
        assertTrue(state.requested)
        assertFalse(state.active)
    }

    @Test
    fun `below Android 12 nothing asks for the blur`() {
        assertFalse(backdropBlurWanted(allowBlur = true, sdkInt = 30))
        assertTrue(backdropBlurWanted(allowBlur = true, sdkInt = 31))
        assertFalse(backdropBlurWanted(allowBlur = false, sdkInt = 34))
    }

    @Test
    fun `lower never goes below zero`() {
        val fresh = PfpBackdropBlurState()
        fresh.lower()
        fresh.raise()
        assertEquals(true, fresh.requested)
    }
}
