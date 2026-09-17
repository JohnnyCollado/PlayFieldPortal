package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.navigation.NavigationNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Game Detail navigation contract, tested away from Compose: stable keys, readiness gating,
 * geometry-driven rows, boundary stops, dynamic-node recovery, the touch cursor and modal context
 * isolation. All of it is plain JVM work because [GameDetailNav] owns no UI.
 */
class GameDetailNavTest {

    private val activated = mutableListOf<String>()

    private fun content(
        loaded: Boolean = true,
        hasManual: Boolean = true,
        emulatorControls: Boolean = true,
        discs: List<Long> = emptyList(),
        coins: Boolean = true,
        overview: Boolean = true,
        info: Boolean = true,
        media: List<String> = emptyList(),
    ) = GameDetailNavContent(
        gameId = 7L,
        loaded = loaded,
        hasManual = hasManual,
        showEmulatorControls = emulatorControls,
        discIds = discs,
        showCoins = coins,
        showOverview = overview,
        showInfo = info,
        mediaIds = media,
    )

    private fun nav(
        content: GameDetailNavContent = content(),
        geometry: Map<String, Float> = emptyMap(),
    ): GameDetailNav = GameDetailNav().apply {
        onActivate = { activated += it }
        updateContent(content)
        if (geometry.isNotEmpty()) reportGeometry(geometry)
    }

    private fun readyNav(
        content: GameDetailNavContent = content(),
        geometry: Map<String, Float> = emptyMap(),
    ): GameDetailNav = nav(content, geometry).also { it.markReady() }

    // ── Readiness ─────────────────────────────────────────────────────────

    @Test
    fun `initial focus is Launch`() {
        assertEquals(GameDetailKeys.LAUNCH, readyNav().focusedKey)
    }

    @Test
    fun `direction input before readiness is ignored and never replayed`() {
        val nav = nav()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)

        // Dropped, not buffered: the cursor is still on Launch and nothing accumulated.
        assertEquals(GameDetailKeys.LAUNCH, nav.focusedKey)

        nav.markReady()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.FAVORITE, nav.focusedKey)
    }

    @Test
    fun `confirm before readiness does not activate anything`() {
        val nav = nav()
        nav.handleAction(GamepadAction.SELECT)
        assertTrue(activated.isEmpty())
    }

    // ── Rows and boundaries ───────────────────────────────────────────────

    @Test
    fun `down enters the quick actions and right walks them without wrapping`() {
        val nav = readyNav()

        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.FAVORITE, nav.focusedKey)

        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.ARTWORK, nav.focusedKey)

        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.MANUAL, nav.focusedKey)

        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.OPTIONS_ACTION, nav.focusedKey)

        // Right at the end of the row stops there instead of wrapping to Favorite.
        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.OPTIONS_ACTION, nav.focusedKey)

        // Left at the start stops on the row's first action, not on the invisible band.
        repeat(4) { nav.handleAction(GamepadAction.NAVIGATE_LEFT) }
        assertEquals(GameDetailKeys.FAVORITE, nav.focusedKey)
    }

    @Test
    fun `up from the quick actions returns to Launch and stops there`() {
        val nav = readyNav()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        nav.handleAction(GamepadAction.NAVIGATE_UP)
        assertEquals(GameDetailKeys.LAUNCH, nav.focusedKey)

        nav.handleAction(GamepadAction.NAVIGATE_UP)
        assertEquals(GameDetailKeys.LAUNCH, nav.focusedKey)
    }

    @Test
    fun `down walks the page rows in order`() {
        val nav = readyNav(content(discs = listOf(1L, 2L), media = listOf("i:shot")))
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // quick actions → Favorite
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // discs
        assertEquals(GameDetailKeys.disc(1L), nav.focusedKey)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // Shiba Coins
        assertEquals(GameDetailKeys.COINS, nav.focusedKey)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // overview
        assertEquals(GameDetailKeys.OVERVIEW, nav.focusedKey)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // information band
        assertEquals(GameDetailKeys.INFO, nav.focusedKey)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // media strip
        assertEquals(GameDetailKeys.media("i:shot"), nav.focusedKey)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // bottom boundary
        assertEquals(GameDetailKeys.media("i:shot"), nav.focusedKey)
    }

    @Test
    fun `the disc row hands the cursor to the member nearest where it came from`() {
        val nav = readyNav(content(discs = listOf(1L, 2L, 3L)))
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // Favorite (child 0)
        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)  // Artwork (child 1)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.disc(2L), nav.focusedKey)
    }

    @Test
    fun `media strip boundaries stop at the ends`() {
        val nav = readyNav(content(media = listOf("i:a", "i:b"), coins = false, overview = false, info = false))
        repeat(3) { nav.handleAction(GamepadAction.NAVIGATE_DOWN) }
        assertEquals(GameDetailKeys.media("i:a"), nav.focusedKey)

        nav.handleAction(GamepadAction.NAVIGATE_LEFT)
        assertEquals(GameDetailKeys.media("i:a"), nav.focusedKey)

        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.media("i:b"), nav.focusedKey)

        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.media("i:b"), nav.focusedKey)
    }

    @Test
    fun `up from the media strip lands on the row above, not on the band`() {
        val nav = readyNav(content(media = listOf("i:a")))
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // Favorite
        repeat(4) { nav.handleAction(GamepadAction.NAVIGATE_DOWN) }  // coins, overview, info, media
        assertEquals(GameDetailKeys.media("i:a"), nav.focusedKey)

        nav.handleAction(GamepadAction.NAVIGATE_UP)
        assertEquals(GameDetailKeys.INFO, nav.focusedKey)
    }

    // ── Geometry ──────────────────────────────────────────────────────────

    @Test
    fun `vertical order follows reported geometry, not registration order`() {
        // Registered order is coins-then-overview; on screen they are the other way round, and the
        // cursor must follow what the user sees.
        val nav = readyNav(
            content = content(info = false),
            geometry = mapOf(
                GameDetailKeys.LAUNCH to 0f,
                GameDetailKeys.ACTIONS to 60f,
                GameDetailKeys.OVERVIEW to 200f,
                GameDetailKeys.COINS to 320f,
            ),
        )
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)   // Favorite
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.OVERVIEW, nav.focusedKey)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.COINS, nav.focusedKey)
    }

    // ── Dynamic content ───────────────────────────────────────────────────

    @Test
    fun `a disabled manual is never focusable and the cursor steps over it`() {
        val nav = readyNav(content(hasManual = false))
        assertTrue(GameDetailKeys.FAVORITE in nav.reachableKeys())
        assertFalse(GameDetailKeys.MANUAL in nav.reachableKeys())

        // Right from Artwork skips the disabled Manual and lands on Options.
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.ARTWORK, nav.focusedKey)
        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.OPTIONS_ACTION, nav.focusedKey)
    }

    @Test
    fun `the Options quick action hands back its own key`() {
        val nav = readyNav()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        repeat(3) { nav.handleAction(GamepadAction.NAVIGATE_RIGHT) }
        assertEquals(GameDetailKeys.OPTIONS_ACTION, nav.focusedKey)

        nav.handleAction(GamepadAction.SELECT)
        assertEquals(listOf(GameDetailKeys.OPTIONS_ACTION), activated)
    }

    @Test
    fun `a package-backed entry keeps Options but has no emulator information field`() {
        val nav = readyNav(content(emulatorControls = false))
        assertTrue(GameDetailKeys.COINS in nav.reachableKeys())
        // Options is the context menu, which every entry has; only the emulator field is ROM-only.
        assertTrue(GameDetailKeys.OPTIONS_ACTION in nav.reachableKeys())

        // The information band is still a reading stop, but confirming it does nothing.
        focusInfo(nav)
        nav.handleAction(GamepadAction.SELECT)
        assertTrue(activated.isEmpty())
    }

    @Test
    fun `confirming the highlighted information band opens the emulator picker straight away`() {
        val nav = readyNav()
        focusInfo(nav)
        assertEquals(GameDetailKeys.INFO, nav.focusedKey)

        // No RIGHT into an inner field first: the highlighted row IS the action.
        nav.handleAction(GamepadAction.SELECT)
        assertEquals(listOf(GameDetailKeys.INFO), activated)

        // And RIGHT has nowhere to go inside it.
        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.INFO, nav.focusedKey)
    }

    /** Walks Launch → quick actions → coins → overview → information band. */
    private fun focusInfo(nav: GameDetailNav) {
        repeat(4) { nav.handleAction(GamepadAction.NAVIGATE_DOWN) }
        assertEquals(GameDetailKeys.INFO, nav.focusedKey)
    }

    @Test
    fun `losing the focused node recovers to the nearest visible one`() {
        val nav = readyNav(content(media = listOf("i:a", "i:b")))
        repeat(6) { nav.handleAction(GamepadAction.NAVIGATE_DOWN) }
        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.media("i:b"), nav.focusedKey)

        // The scraped asset is removed while the cursor sits on it.
        nav.updateContent(content(media = listOf("i:a")))

        // Recovery stays in the same row (never a jump back to Launch), and never parks on the
        // invisible strip band.
        assertEquals(GameDetailKeys.media("i:a"), nav.focusedKey)
    }

    @Test
    fun `an unrelated content change keeps the cursor where it is`() {
        val nav = readyNav()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.ARTWORK, nav.focusedKey)

        // Achievements finished loading: the graph changed, the focused node did not.
        nav.updateContent(content())
        assertEquals(GameDetailKeys.ARTWORK, nav.focusedKey)
    }

    @Test
    fun `a manual that appears later becomes focusable`() {
        val nav = readyNav(content(hasManual = false))
        assertFalse(GameDetailKeys.MANUAL in nav.reachableKeys())

        nav.updateContent(content(hasManual = true))
        assertTrue(GameDetailKeys.MANUAL in nav.reachableKeys())
    }

    @Test
    fun `an unloaded page has nothing focusable`() {
        val nav = readyNav(content(loaded = false))
        assertNull(nav.focusedKey)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertNull(nav.focusedKey)
    }

    // ── Cursor visibility ─────────────────────────────────────────────────

    @Test
    fun `touch hides the cursor but keeps logical focus, and controller input brings it back`() {
        val nav = readyNav()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.FAVORITE, nav.focusedKey)
        assertTrue(nav.cursorVisible)

        nav.markTouchInput()
        assertFalse(nav.cursorVisible)
        assertEquals(GameDetailKeys.FAVORITE, nav.focusedKey)

        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertTrue(nav.cursorVisible)
    }

    @Test
    fun `tapping a node moves logical focus there and activates it`() {
        val nav = readyNav()
        nav.markTouchInput()

        assertTrue(nav.touch(GameDetailKeys.ARTWORK))
        assertEquals(listOf(GameDetailKeys.ARTWORK), activated)
        assertEquals(GameDetailKeys.ARTWORK, nav.focusedKey)
        // A tap is still touch input: the cursor stays hidden.
        assertFalse(nav.cursorVisible)
    }

    // ── Recovery lock ─────────────────────────────────────────────────────

    @Test
    fun `repeated input during an alignment is dropped, not queued`() {
        val nav = readyNav()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.FAVORITE, nav.focusedKey)

        nav.beginRecoveryLock()
        repeat(3) { nav.handleAction(GamepadAction.NAVIGATE_DOWN) }
        assertEquals(GameDetailKeys.FAVORITE, nav.focusedKey)

        nav.endRecoveryLock()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals(GameDetailKeys.COINS, nav.focusedKey)
    }

    // ── Modal contexts ────────────────────────────────────────────────────

    @Test
    fun `a modal owns all input and hands back the exact page node it interrupted`() {
        val nav = readyNav()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        nav.handleAction(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(GameDetailKeys.ARTWORK, nav.focusedKey)

        val modalRows = listOf(
            NavigationNode("modal:row0", onSelect = { activated += "modal:row0" }),
            NavigationNode("modal:row1", onSelect = { activated += "modal:row1" }),
        )
        nav.pushModal(GameDetailKeys.MODAL_OPTIONS, modalRows, preferredFocus = "modal:row0")
        assertTrue(nav.isModalActive)
        assertEquals("modal:row0", nav.focusedKey)

        // Direction and confirm both belong to the overlay: no page node may fire through it.
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        assertEquals("modal:row1", nav.focusedKey)
        nav.handleAction(GamepadAction.SELECT)
        assertEquals(listOf("modal:row1"), activated)

        // Closing restores the exact node the page was on — not Launch, not the first row.
        assertEquals(GameDetailKeys.ARTWORK, nav.popModal())
        assertFalse(nav.isModalActive)
        assertEquals(GameDetailKeys.ARTWORK, nav.focusedKey)
    }

    @Test
    fun `a modal with no rows consumes navigation without moving the page cursor`() {
        val nav = readyNav()
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        val before = nav.focusedKey

        nav.pushModal(GameDetailKeys.MODAL_MANUAL_VIEWER)
        nav.handleAction(GamepadAction.NAVIGATE_DOWN)
        nav.handleAction(GamepadAction.SELECT)
        assertTrue(activated.isEmpty())

        assertEquals(before, nav.popModal())
    }

    @Test
    fun `page content updates wait for the modal to close`() {
        val nav = readyNav(content(hasManual = false))
        nav.pushModal(GameDetailKeys.MODAL_OPTIONS)
        // A refresh lands while Options is up: it must not overwrite the overlay's own graph.
        nav.updateContent(content(hasManual = true))

        nav.popModal()
        assertTrue(GameDetailKeys.MANUAL in nav.reachableKeys())
    }
}
