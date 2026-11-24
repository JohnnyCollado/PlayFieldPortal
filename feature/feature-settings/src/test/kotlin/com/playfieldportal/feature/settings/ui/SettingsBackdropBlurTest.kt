package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.playfieldportal.core.ui.components.LocalPfpBackdropBlur
import com.playfieldportal.core.ui.components.PfpBackdropBlurState
import com.playfieldportal.core.ui.components.PfpConfirmModal
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.detail.PfpBackdropBlurAppliedKey
import com.playfieldportal.core.ui.detail.ProvidePfpBackdropBlur
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
 * A settings screen is `Box { SettingsScaffold(...); modal.Content() }` under SettingsNavHost's
 * [ProvidePfpBackdropBlur]: the scaffold blurs its own body while a prompt is up, the prompt (its
 * sibling) is never inside the blurred node, and the shell's switch reaches it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h480dp")
class SettingsBackdropBlurTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val shellState = PfpBackdropBlurState()
    private val promptShown = mutableStateOf(false)

    private fun setUp() {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalPfpBackdropBlur provides shellState) {
                    ProvidePfpBackdropBlur {
                        Box(Modifier.fillMaxSize()) {
                            SettingsScaffold(title = "Settings", subtitle = "Test", onBack = {}) {
                                Text("bodyProbe", Modifier.testTag("bodyProbe"))
                            }
                            Box(Modifier.testTag("promptLayerProbe"))
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
                        }
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
    fun `the body is blurred only while a prompt is shown`() {
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
    fun `the body is inside the blurred node and the prompt layer is not`() {
        setUp()
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(insideBlurLayer("bodyProbe"))
        assertFalse(insideBlurLayer("promptLayerProbe"))
    }

    @Test
    fun `the shell switching the blur off reaches the settings screen`() {
        setUp()
        promptShown.value = true
        shellState.enabled = false
        composeRule.waitForIdle()
        assertFalse(blurApplied())
        shellState.enabled = true
        composeRule.waitForIdle()
        assertTrue(blurApplied())
    }

    @Test
    fun `a settings prompt does not raise the shell's own state`() {
        setUp()
        promptShown.value = true
        composeRule.waitForIdle()
        assertFalse(shellState.requested)
    }
}
