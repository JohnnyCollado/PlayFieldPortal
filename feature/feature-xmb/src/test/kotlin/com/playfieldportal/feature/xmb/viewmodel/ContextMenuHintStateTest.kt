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
    fun `does not show when the focused item has no context menu and the list cannot sort`() {
        val plain = XMBItem(id = "x", title = "Plain", type = XMBItemType.STANDARD)
        val s = eligibleState().copy(currentItems = listOf(plain))
        assertFalse(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `shows while drilled into a sub-item`() {
        // Previously suppressed. Drilled-in rows (the game flyout, a library's files) have
        // context menus and sort, so this is where the affordance is least discoverable.
        val s = eligibleState().copy(selectedPlatformId = "psp") // drilled into a memory card
        assertTrue(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    // ── Sort half of the pill ───────────────────────────────────────────────

    @Test
    fun `an unsortable root list offers no sort prompt`() {
        // The Games memory-card root: no platform or collection drilled into.
        assertFalse(eligibleState().canSortCurrentList)
    }

    @Test
    fun `drilling into a platform makes the list sortable`() {
        assertTrue(eligibleState().copy(selectedPlatformId = "psp").canSortCurrentList)
        assertTrue(eligibleState().copy(selectedCollectionId = 7L).canSortCurrentList)
    }

    @Test
    fun `a sortable list shows the pill even when the focused item has no context menu`() {
        // Only the Sort half is drawn; the pill is still worth showing.
        val plain = XMBItem(id = "x", title = "Plain", type = XMBItemType.STANDARD)
        val s = eligibleState().copy(currentItems = listOf(plain), selectedPlatformId = "psp")
        assertFalse(s.focusedItemHasContextMenu)
        assertTrue(s.canSortCurrentList)
        assertTrue(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `neither half applicable means no pill`() {
        val plain = XMBItem(id = "x", title = "Plain", type = XMBItemType.STANDARD)
        val s = eligibleState().copy(currentItems = listOf(plain))
        assertFalse(s.focusedItemHasContextMenu)
        assertFalse(s.canSortCurrentList)
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

    // ── App Drawer hint branch ────────────────────────────────────────────

    /** A state where the drawer hint is eligible: drawer open, controller last used, idle. */
    private fun drawerEligibleState() = XMBUiState(
        activeAppDrawerFilter = "ALL",
        lastInputWasTouch = false,
        showBootSequence = false,
    ).let { it.copy(showAppDrawerHint = false) } // hint field itself is irrelevant to the gate

    @Test
    fun `drawer hint shows when idle long enough with a controller while the drawer is open`() {
        assertTrue(shouldShowAppDrawerHint(drawerEligibleState(), XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `drawer hint does not show before the idle delay elapses`() {
        assertFalse(shouldShowAppDrawerHint(drawerEligibleState(), XMBViewModel.IDLE_HINT_DELAY_MS - 1))
    }

    @Test
    fun `drawer hint never shows while the drawer is closed`() {
        // eligibleState() has no activeAppDrawerFilter, so it exercises the closed-drawer side of
        // the gate while remaining fully eligible for the XMB pill gate (a focused game item).
        val s = eligibleState()
        assertFalse(shouldShowAppDrawerHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
        assertTrue(shouldShowContextMenuHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `drawer hint does not show after touch input`() {
        val s = drawerEligibleState().copy(lastInputWasTouch = true)
        assertFalse(shouldShowAppDrawerHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `drawer hint does not show when a context menu is open`() {
        val s = drawerEligibleState().copy(activeContextMenu = XMBContextMenu("X", emptyList()))
        assertFalse(shouldShowAppDrawerHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `drawer hint stops when the context-menu hint setting is disabled`() {
        val s = drawerEligibleState().copy(contextMenuHintEnabled = false)
        assertFalse(shouldShowAppDrawerHint(s, XMBViewModel.IDLE_HINT_DELAY_MS))
    }

    @Test
    fun `drawer hint uses the configured delay instead of the default delay`() {
        val s = drawerEligibleState().copy(contextMenuHintDelaySeconds = 4.5f)
        assertFalse(shouldShowAppDrawerHint(s, 4_499))
        assertTrue(shouldShowAppDrawerHint(s, 4_500))
    }

    @Test
    fun `drawer open is a blocking overlay so the drawer hint and XMB pill are mutually exclusive`() {
        val open = drawerEligibleState()
        assertTrue(shouldShowAppDrawerHint(open, XMBViewModel.IDLE_HINT_DELAY_MS))
        assertFalse(shouldShowContextMenuHint(open, XMBViewModel.IDLE_HINT_DELAY_MS))
    }
}
