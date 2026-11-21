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

/** The backdrop-blur flag: raised only while a prompt or the options panel is shown, on API 31+. */
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
    fun `a prompt raises the flag while shown and lowers it when dismissed`() {
        setUp()
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(state.active)

        promptShown.value = false
        composeRule.waitForIdle()
        assertFalse(state.requested)
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
    fun `a prompt over a panel is two requests and both must go`() {
        setUp()
        panelShown.value = true
        promptShown.value = true
        composeRule.waitForIdle()
        promptShown.value = false
        composeRule.waitForIdle()
        assertTrue("the panel still wants it", state.requested)
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
    fun `the host can switch the blur off while a prompt is up`() {
        setUp()
        promptShown.value = true
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
