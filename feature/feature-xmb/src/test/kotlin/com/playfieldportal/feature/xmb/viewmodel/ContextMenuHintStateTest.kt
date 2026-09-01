package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [shouldShowContextMenuHint] — the gate the idle-timer loop consults. Pure, no coroutine
 * or time dependency: the loop passes in the elapsed idle ms.
 */
class ContextMenuHintStateTest {

    private val gameItem = XMBItem(id = "g1", title = "Game", gameId = 1L)

    /** A state where the hint is eligible: controller last used, root, game focused, no overlay, idle. */
    private fun eligibleState(idleMs: Long = XMBViewModel.IDLE_HINT_DELAY_MS) = XMBUiState(
        categories = listOf(Category(BuiltInCategory.GAMES, "Games", "games", type = CategoryType.BUILT_IN, position = 0)),
        selectedCategoryIndex = 0,
        currentItems = listOf(gameItem),
        selectedItemIndex = 0,
        lastInputWasTouch = false, // controller last used → hint is eligible
        touchNavButtonMode = TouchNavButtonMode.AUTO,
        showBootSequence = false, // boot overlay is a blocking overlay — must be past it
    ).let { it.copy(showContextMenuHint = false) } // hint field itself is irrelevant to the gate

    @Test
    fun `shows hint when idle long enough over a context-menu item after controller input`() {
        assertTrue(shouldShowContextMenuHint(eligibleState(), XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `does not show before the idle delay elapses`() {
        assertFalse(shouldShowContextMenuHint(eligibleState(), XMBViewModel.IDLE_HINT_DELAY_MS - 1))
    }

    @Test
    fun `does not show after touch input`() {
        val s = eligibleState().copy(lastInputWasTouch = true)
        assertFalse(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `controller input enables the hint regardless of touch-button mode`() {
        val s = eligibleState().copy(
            lastInputWasTouch = false,
            touchNavButtonMode = TouchNavButtonMode.ALWAYS_HIDE,
        )
        assertTrue(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `does not show when a blocking overlay is up`() {
        val s = eligibleState().copy(activeGameId = 1L) // detail screen = blocking overlay
        assertFalse(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `does not show when a context menu is already open`() {
        val s = eligibleState().copy(activeContextMenu = XMBContextMenu("X", emptyList()))
        assertFalse(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `does not show when the focused item has no context menu`() {
        val plain = XMBItem(id = "x", title = "Plain", type = XMBItemType.STANDARD)
        val s = eligibleState().copy(currentItems = listOf(plain))
        assertFalse(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `does not show while drilled into a sub-item`() {
        val s = eligibleState().copy(selectedPlatformId = "psp") // drilled into a memory card
        assertFalse(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `does not show when boot sequence is still playing`() {
        val s = eligibleState().copy(showBootSequence = true)
        assertFalse(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `does not show when the context-menu hint setting is disabled`() {
        val s = eligibleState().copy(contextMenuHintEnabled = false)
        assertFalse(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `uses the configured delay instead of the default delay`() {
        val s = eligibleState().copy(contextMenuHintDelaySeconds = 4.5f)
        assertFalse(shouldShowContextMenuHint(s, 4_499))
        assertTrue(shouldShowContextMenuHint(s, 4_500))
    }

    @Test
    fun `delay values below one second and above five seconds are not treated specially by the pure gate`() {
        val oneSecond = eligibleState().copy(contextMenuHintDelaySeconds = 1f)
        val fiveSeconds = eligibleState().copy(contextMenuHintDelaySeconds = 5f)
        assertTrue(shouldShowContextMenuHint(oneSecond, 1_000))
        assertFalse(shouldShowContextMenuHint(fiveSeconds, 4_999))
        assertTrue(shouldShowContextMenuHint(fiveSeconds, 5_000))
    }
}
