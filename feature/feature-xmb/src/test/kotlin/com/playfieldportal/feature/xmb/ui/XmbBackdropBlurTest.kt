package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.playfieldportal.core.ui.components.LocalPfpBackdropBlur
import com.playfieldportal.core.ui.components.PfpBackdropBlurState
import com.playfieldportal.core.ui.components.PfpConfirmModal
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.detail.PfpBackdropBlurAppliedKey
import com.playfieldportal.core.ui.theme.PFPTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The shell's blurred content layer: blurred only while a prompt or panel drawn in a sibling layer
 * above it is up and the shell allows it, and never containing that prompt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class XmbBackdropBlurTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val state = PfpBackdropBlurState()
    private val promptShown = mutableStateOf(false)
    private val nestedPromptShown = mutableStateOf(false)

    @androidx.compose.runtime.Composable
    private fun Confirm() {
        PfpConfirmModal(
            title = "Delete",
            message = "Sure?",
            confirmLabel = "Delete",
            focus = PfpModalFocus.CANCEL,
            onConfirm = {},
            onCancel = {},
        )
    }

    private fun setUp() {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalPfpBackdropBlur provides state) {
                    Box(Modifier.fillMaxSize()) {
                        XmbBlurredLayer(state) {
                            Text("bodyProbe", Modifier.testTag("bodyProbe"))
                            if (nestedPromptShown.value) Confirm()
                        }
                        Box(Modifier.testTag("promptLayerProbe"))
                        if (promptShown.value) Confirm()
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun layer(): SemanticsNode =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(PfpBackdropBlurAppliedKey), useUnmergedTree = true)
            .fetchSemanticsNode()

    private fun blurApplied() = layer().config[PfpBackdropBlurAppliedKey]

    private fun insideBlurLayer(tag: String): Boolean {
        var node: SemanticsNode? = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().parent
        while (node != null) {
            if (node.config.contains(PfpBackdropBlurAppliedKey)) return true
            node = node.parent
        }
        return false
    }

    @Test
    fun `the layer is blurred only while a prompt above it is shown`() {
        setUp()
        assertFalse(blurApplied())
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(blurApplied())
        promptShown.value = false
        composeRule.waitForIdle()
        assertFalse(blurApplied())
    }

    @Test
    fun `the content is inside the blurred node and the prompt layer is not`() {
        setUp()
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(insideBlurLayer("bodyProbe"))
        assertFalse(insideBlurLayer("promptLayerProbe"))
    }

    @Test
    fun `performance settings switching the blur off keep the layer sharp`() {
        setUp()
        state.enabled = false
        promptShown.value = true
        composeRule.waitForIdle()
        assertFalse(blurApplied())
        state.enabled = true
        composeRule.waitForIdle()
        assertTrue(blurApplied())
    }

    @Test
    fun `a prompt nested inside the layer never blurs the layer that holds it`() {
        setUp()
        nestedPromptShown.value = true
        composeRule.waitForIdle()
        assertFalse(state.requested)
        assertFalse(blurApplied())
    }
}
