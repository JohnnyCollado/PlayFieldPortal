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

    // The options menu: what blurs the layer (a prompt hides it instead).
    @androidx.compose.runtime.Composable
    private fun Menu() {
        com.playfieldportal.core.ui.components.PspContextMenuOverlay(
            title = "Options",
            rows = listOf(com.playfieldportal.core.ui.components.PspMenuRow("Play")),
            selectedIndex = 0,
            onRowActivated = {},
            onDismiss = {},
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
                        if (promptShown.value) Menu()
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
    fun `a prompt over the XMB hides the cross and keeps the wallpaper`() {
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(LocalPfpBackdropBlur provides state) {
                    Box(Modifier.fillMaxSize()) {
                        XmbBlurredLayer(state, background = { Text("wallpaper", Modifier.testTag("wallpaper")) }) {
                            Text("cross", Modifier.testTag("cross"))
                        }
                        if (promptShown.value) Confirm()
                    }
                }
            }
        }
        promptShown.value = true
        composeRule.waitForIdle()
        fun hiddenAncestor(tag: String): Boolean {
            var node: SemanticsNode? = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().parent
            while (node != null) {
                if (node.config.getOrElse(com.playfieldportal.core.ui.detail.PfpBackdropHiddenKey) { false }) return true
                node = node.parent
            }
            return false
        }
        assertTrue("the cross goes while the prompt is up", hiddenAncestor("cross"))
        assertFalse("the wallpaper stays", hiddenAncestor("wallpaper"))
        assertTrue("the wallpaper blurs, so it does not bleed through the silver", backgroundBlurred())
        promptShown.value = false
        composeRule.waitForIdle()
        assertFalse("the cross comes back", hiddenAncestor("cross"))
        assertFalse(backgroundBlurred())
    }

    private fun backgroundBlurred(): Boolean {
        var node: SemanticsNode? = composeRule.onNodeWithTag("wallpaper", useUnmergedTree = true).fetchSemanticsNode().parent
        while (node != null) {
            if (node.config.getOrElse(XmbBackgroundBlurredKey) { false }) return true
            node = node.parent
        }
        return false
    }

    @Test
    fun `a prompt in the App Drawer hides the drawer and blurs the wallpaper behind it`() {
        val behind = PfpBackdropBlurState()
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(
                    LocalPfpBackdropBlur provides state,
                    com.playfieldportal.core.ui.components.LocalPfpBackdropBehind provides behind,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        XmbBlurredLayer(state, behind = behind, background = { Box(Modifier.testTag("wallpaper")) }) {
                            com.playfieldportal.core.ui.detail.PfpBlurLayers(overlay = { if (promptShown.value) Confirm() }) {
                                Text("drawerGrid", Modifier.testTag("drawerGrid"))
                            }
                        }
                    }
                }
            }
        }
        promptShown.value = true
        composeRule.waitForIdle()
        assertTrue("the wallpaper behind the drawer blurs", backgroundBlurred())
        assertFalse("layer A itself is not asked", behind.requested)
    }

    @Test
    fun `a screen inside the layer that blurs for pages above (the App Drawer) blurs itself and the wallpaper`() {
        // Layer A: the wallpaper (its background), the XMB and the App Drawer (its content). The
        // drawer's menu must blur the drawer and the wallpaper showing through it (or that reads sharp
        // through the blur, ghosting), but never layer A's content, which holds the menu itself.
        val behind = PfpBackdropBlurState()
        composeRule.setContent {
            PFPTheme {
                CompositionLocalProvider(
                    LocalPfpBackdropBlur provides state,
                    com.playfieldportal.core.ui.components.LocalPfpBackdropBehind provides behind,
                ) {
                    Box(Modifier.fillMaxSize()) {
                        XmbBlurredLayer(state, behind = behind, background = { Box(Modifier.testTag("wallpaper")) }) {
                            com.playfieldportal.core.ui.detail.PfpBlurLayers(
                                overlay = { Box(Modifier.testTag("drawerMenu")); Menu() },
                            ) {
                                Text("drawerGrid", Modifier.testTag("drawerGrid"))
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertFalse("layer A must not be asked to blur", behind.requested)
        assertFalse(state.requested)
        val applied = composeRule.onAllNodes(SemanticsMatcher.expectValue(PfpBackdropBlurAppliedKey, true), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("one layer is blurred: the drawer's content", applied.size == 1)
        fun blurredAncestor(tag: String): Boolean {
            var node: SemanticsNode? = composeRule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().parent
            while (node != null) {
                if (node.config.getOrElse(PfpBackdropBlurAppliedKey) { false }) return true
                node = node.parent
            }
            return false
        }
        assertTrue(blurredAncestor("drawerGrid"))
        assertFalse("the menu itself stays sharp", blurredAncestor("drawerMenu"))
        fun backgroundBlurred(): Boolean {
            var node: SemanticsNode? = composeRule.onNodeWithTag("wallpaper", useUnmergedTree = true).fetchSemanticsNode().parent
            while (node != null) {
                if (node.config.getOrElse(XmbBackgroundBlurredKey) { false }) return true
                node = node.parent
            }
            return false
        }
        assertTrue("the wallpaper behind the drawer blurs with it", backgroundBlurred())
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
