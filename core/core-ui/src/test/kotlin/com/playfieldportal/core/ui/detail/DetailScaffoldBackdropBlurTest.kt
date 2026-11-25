package com.playfieldportal.core.ui.detail

import androidx.compose.foundation.layout.Box
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
 * The detail page blurs its own content layer, never its overlay layer, and only while a prompt or
 * panel in that overlay is up and the host allows it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp")
class DetailScaffoldBackdropBlurTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val hostState = PfpBackdropBlurState()
    // The options menu: it blurs the page. (Named for the overlay it is; the tests below are the blur's.)
    private val promptShown = mutableStateOf(false)
    // A prompt: it hides the page body instead.
    private val confirmShown = mutableStateOf(false)

    private fun setUp(host: PfpBackdropBlurState? = hostState) {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalPfpBackdropBlur provides host) {
                    PfpDetailScaffold(
                        overlay = {
                            Box(Modifier.testTag("overlayProbe"))
                            if (promptShown.value) {
                                com.playfieldportal.core.ui.components.PspContextMenuOverlay(
                                    title = "Options",
                                    rows = listOf(com.playfieldportal.core.ui.components.PspMenuRow("Play")),
                                    selectedIndex = 0,
                                    onRowActivated = {},
                                    onDismiss = {},
                                )
                            }
                            if (confirmShown.value) {
                                PfpConfirmModal(
                                    title = "Delete",
                                    message = "Sure?",
                                    confirmLabel = "Delete",
                                    focus = PfpModalFocus.CANCEL,
                                    onConfirm = {},
                                    onCancel = {},
                                )
                            }
                        },
                    ) {
                        Text("bodyProbe", Modifier.testTag("bodyProbe"))
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun layerNode(): SemanticsNode =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(PfpBackdropBlurAppliedKey), useUnmergedTree = true)
            .fetchSemanticsNode()

    private fun blurApplied(): Boolean = layerNode().config[PfpBackdropBlurAppliedKey]

    private fun hasLayerAncestor(tag: String): Boolean {
        var node: SemanticsNode? = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().parent
        while (node != null) {
            if (node.config.contains(PfpBackdropBlurAppliedKey)) return true
            node = node.parent
        }
        return false
    }

    @Test
    fun `the body sits inside the blur layer and the overlay does not`() {
        setUp()
        assertTrue(hasLayerAncestor("bodyProbe"))
        assertFalse(hasLayerAncestor("overlayProbe"))
    }

    @Test
    fun `a prompt in the overlay is never inside the blurred node`() {
        setUp()
        promptShown.value = true
        composeRule.waitForIdle()
        assertFalse(hasLayerAncestor("overlayProbe"))
        assertEquals(1, composeRule.onAllNodesWithKey().size)
    }

    @Test
    fun `no blur until a prompt is shown`() {
        setUp()
        assertFalse(blurApplied())
    }

    @Test
    fun `a prompt turns the blur on and dismissing it turns it off`() {
        setUp()
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(blurApplied())

        promptShown.value = false
        composeRule.waitForIdle()
        assertFalse(blurApplied())
    }

    @Test
    fun `the host switching blur off wins over a prompt`() {
        setUp()
        hostState.enabled = false
        promptShown.value = true
        composeRule.waitForIdle()
        assertFalse(blurApplied())

        hostState.enabled = true
        composeRule.waitForIdle()
        assertTrue(blurApplied())
    }

    @Test
    fun `the page keeps its own state so the host's layer is not raised`() {
        setUp()
        promptShown.value = true
        composeRule.waitForIdle()
        assertFalse("the enclosing host must not blur the page that holds the prompt", hostState.requested)
    }

    private fun hidden(): Boolean = layerNode().config[PfpBackdropHiddenKey]

    @Test
    fun `a prompt hides the page body instead of blurring it, and it comes back after`() {
        setUp()
        assertFalse(hidden())
        confirmShown.value = true
        composeRule.waitForIdle()
        assertTrue(hidden())
        assertFalse(blurApplied())
        assertFalse("the prompt itself stays", hasLayerAncestor("overlayProbe"))
        confirmShown.value = false
        composeRule.waitForIdle()
        assertFalse(hidden())
    }

    @Test
    fun `a prompt hides the page even with the blur switched off`() {
        setUp()
        hostState.enabled = false
        confirmShown.value = true
        composeRule.waitForIdle()
        assertTrue(hidden())
    }

    @Test
    fun `without any host the page still blurs itself`() {
        setUp(host = null)
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue(blurApplied())
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithKey() =
        onAllNodes(SemanticsMatcher.keyIsDefined(PfpBackdropBlurAppliedKey), useUnmergedTree = true)
            .fetchSemanticsNodes()
}
