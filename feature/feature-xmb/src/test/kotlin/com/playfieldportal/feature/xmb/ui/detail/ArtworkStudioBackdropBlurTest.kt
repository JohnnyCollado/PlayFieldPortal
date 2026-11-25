package com.playfieldportal.feature.xmb.ui.detail

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.ui.components.PfpConfirmModal
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.detail.PfpBackdropBlurAppliedKey
import com.playfieldportal.core.ui.detail.PfpBackdropHiddenKey
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Artwork Studio blurs its page under its options menu and hides it under one of its prompts;
 * the menus and prompts themselves are drawn above the page and never touched. Its menus used to sit
 * inside the page, where nothing blurred it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h468dp-land")
class ArtworkStudioBackdropBlurTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // Nothing is pressed in these tests; every action is a no-op, and anything read is empty.
    private val actions = Proxy.newProxyInstance(
        ArtworkStudioActions::class.java.classLoader,
        arrayOf(ArtworkStudioActions::class.java),
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Float.TYPE -> 0f
            List::class.java, Collection::class.java, Iterable::class.java -> emptyList<Any>()
            Set::class.java -> emptySet<Any>()
            Map::class.java -> emptyMap<Any, Any>()
            else -> null
        }
    } as ArtworkStudioActions

    private fun setUp(state: ArtworkStudioUiState, prompt: Boolean = false) {
        composeRule.setContent {
            PfpScreenPreview {
                ArtworkStudioContent(
                    state = state,
                    actions = actions,
                    showTouchControls = false,
                    onTouchInput = {},
                    overlay = {
                        Box(Modifier.testTag("overlayProbe"))
                        if (prompt) {
                            PfpConfirmModal(
                                title = "Clear Artwork?", message = "Sure?", confirmLabel = "Clear",
                                focus = PfpModalFocus.CANCEL, onConfirm = {}, onCancel = {},
                            )
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun layer(key: SemanticsPropertyKey<Boolean>): Boolean =
        composeRule.onNode(SemanticsMatcher.keyIsDefined(key), useUnmergedTree = true).fetchSemanticsNode().config[key]

    private fun SemanticsNode.insideLayer(): Boolean {
        var node: SemanticsNode? = parent
        while (node != null) {
            if (node.config.contains(PfpBackdropBlurAppliedKey)) return true
            node = node.parent
        }
        return false
    }

    private val loaded = ArtworkStudioUiState(game = Game(id = 1, title = "Tactics Ogre: Reborn", platformId = "windows"), isLoading = false)

    @Test
    fun `the page is as it is with nothing open`() {
        setUp(loaded)
        assertFalse(layer(PfpBackdropBlurAppliedKey))
        assertFalse(layer(PfpBackdropHiddenKey))
    }

    @Test
    fun `the options menu blurs the page`() {
        setUp(loaded.copy(actionsOpen = true))
        assertTrue(layer(PfpBackdropBlurAppliedKey))
    }

    @Test
    fun `a prompt hides the page and is drawn above it`() {
        setUp(loaded, prompt = true)
        assertTrue(layer(PfpBackdropHiddenKey))
        assertFalse(composeRule.onNodeWithTag("overlayProbe", useUnmergedTree = true).fetchSemanticsNode().insideLayer())
    }
}
