package com.playfieldportal.studio.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.playfieldportal.studio.PreviewAdjustStore
import com.playfieldportal.studio.StudioState
import com.playfieldportal.themekit.ThemeLegibility
import com.playfieldportal.themekit.XmbLayoutAdjust
import com.playfieldportal.themekit.XmbLayoutSpec
import java.awt.EventQueue
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TS-32: options flyout, Games filter, PIC0, legibility rendering and the layout-adjust overlay. */
class PreviewInteractionTest {

    private fun PreviewNavState.send(vararg actions: PreviewNavAction): PreviewNavState =
        actions.fold(this) { s, a -> PreviewNav.reduce(s, a) }

    private val categories get() = SampleContent.categories
    private val gameIndex get() = categories.indexOfFirst { it.slotKey == "catbar_games" }
    private val ps3 get() = SampleContent.rootRows(gameIndex).indexOfFirst { it.slotKey == "sysicon_ps3" }

    /** The Games category, the PlayStation 3 console card selected (not drilled). */
    private fun onConsole(): PreviewNavState {
        var s = PreviewNavState()
        repeat(gameIndex - s.category) { s = s.send(PreviewNavAction.Right) }
        repeat(ps3 - s.currentIndex) { s = s.send(PreviewNavAction.Down) }
        return s
    }

    /** Drilled into PlayStation 3: its four games, the first selected. */
    private fun inGames(): PreviewNavState = onConsole().send(PreviewNavAction.Enter)

    private fun titles(s: PreviewNavState) = PreviewNav.view(s).rows.map { it.title }

    private fun labels(s: PreviewNavState) = PreviewFlyout.rows(s).map { it.label }

    private val allGames = listOf("Crossbar Racing", "Memory Card Blues", "Portal Quest", "Shiba Run")

    // ── Keys ─────────────────────────────────────────────────────────────────

    @Test
    fun `tab and Y open the options flyout, X opens the Games filter`() {
        assertEquals(PreviewNavAction.OpenOptions, PreviewNav.actionFor(Key.Tab))
        assertEquals(PreviewNavAction.OpenOptions, PreviewNav.actionFor(Key.Y))
        assertEquals(PreviewNavAction.OpenFilter, PreviewNav.actionFor(Key.X))
    }

    // ── Options flyout ───────────────────────────────────────────────────────

    @Test
    fun `options open on the selected row with its title and first row under the cursor`() {
        val s = inGames().send(PreviewNavAction.OpenOptions)
        assertEquals(FlyoutKind.OPTIONS, s.flyout?.kind)
        assertEquals(FlyoutMenu.ROOT, s.flyout?.menu)
        assertEquals(0, s.flyout?.cursor)
        assertEquals("Crossbar Racing", PreviewFlyout.title(s))
        assertTrue(PreviewNav.canGoBack(s))
    }

    @Test
    fun `rows per row type`() {
        // game
        assertEquals(
            listOf(
                "View Game Details", "View Shiba Coins", "Add to Favorites", "Add to Collection", "Change Emulator",
                "Icon Display", "Fetch Artwork", "Hide from Games", "Remove from Library",
            ),
            labels(inGames().send(PreviewNavAction.OpenOptions)),
        )
        val game = PreviewFlyout.rows(inGames().send(PreviewNavAction.OpenOptions))
        assertTrue(game.single { it.label == "Add to Collection" }.opensSubmenu)
        assertEquals("Box Art", game.single { it.label == "Icon Display" }.value)
        assertNotNull(game.single { it.label == "Change Emulator" }.value)
        assertTrue(game.last().destructive && game.count { it.destructive } == 1)

        // console card
        assertEquals(
            listOf(
                "Scan This Console", "Update Metadata", "Scrape Missing Artwork", "Icon Display", "Pin To Top",
                "Open in Library Manager", "Hide From Games", "Remove Memory Card",
            ),
            labels(onConsole().send(PreviewNavAction.OpenOptions)),
        )
        assertTrue(PreviewFlyout.rows(onConsole().send(PreviewNavAction.OpenOptions)).last().destructive)

        // video file: Video > Videos > first file
        val video = PreviewNavState().send(PreviewNavAction.Enter, PreviewNavAction.OpenOptions)
        assertEquals(RowKind.VIDEO, PreviewFlyout.kindOf(PreviewNav.view(video).rows[0]))
        assertEquals(
            listOf("Play", "Resume", "Add to Favorites", "Add to Playlist", "Details", "Remove From Library"),
            labels(video),
        )
        assertTrue(PreviewFlyout.rows(video).single { it.label == "Add to Playlist" }.opensSubmenu)

        // library (memory-card list row) and the default
        val library = PreviewNavState().send(PreviewNavAction.OpenOptions)
        assertEquals(RowKind.LIBRARY, PreviewFlyout.kindOf(PreviewNav.selectedRow(PreviewNavState())!!))
        assertEquals(listOf("Open", "Scan Library", "Manage in Settings"), labels(library))
        val default = PreviewNavState().send(PreviewNavAction.Up, PreviewNavAction.OpenOptions)
        assertEquals(listOf("Open", "Information"), labels(default))
    }

    @Test
    fun `up and down move the flyout cursor, stop at both ends, and leave the cursor row alone`() {
        var s = inGames().send(PreviewNavAction.OpenOptions)
        val rows = PreviewFlyout.rows(s).size
        repeat(rows + 3) { s = s.send(PreviewNavAction.Down) }
        assertEquals(rows - 1, s.flyout?.cursor)
        repeat(rows + 3) { s = s.send(PreviewNavAction.Up) }
        assertEquals(0, s.flyout?.cursor)
        assertEquals(0, s.currentIndex)
        // Left / Right do nothing while a menu is up.
        val base = inGames().send(PreviewNavAction.OpenOptions)
        assertEquals(base, base.send(PreviewNavAction.Left))
        assertEquals(base, base.send(PreviewNavAction.Right))
    }

    @Test
    fun `a submenu swaps in place with its check, and back returns to the row that led there`() {
        var s = inGames().send(PreviewNavAction.OpenOptions)
        val at = PreviewFlyout.rows(s).indexOfFirst { it.label == "Add to Collection" }
        repeat(at) { s = s.send(PreviewNavAction.Down) }
        s = s.send(PreviewNavAction.Enter)
        assertEquals(FlyoutMenu.SUBMENU, s.flyout?.menu)
        assertEquals(0, s.flyout?.cursor)
        assertEquals("Add to Collection", PreviewFlyout.title(s))
        assertTrue(PreviewFlyout.rows(s).any { it.checked })
        s = s.send(PreviewNavAction.Down).send(PreviewNavAction.Back)
        assertEquals(FlyoutMenu.ROOT, s.flyout?.menu)
        assertEquals(at, s.flyout?.cursor)
        // One more back closes it; the drill underneath is untouched.
        s = s.send(PreviewNavAction.Back)
        assertNull(s.flyout)
        assertTrue(s.isDrilled)
    }

    @Test
    fun `the menu eats back before the drill does`() {
        val s = inGames().send(PreviewNavAction.OpenOptions, PreviewNavAction.Back)
        assertNull(s.flyout)
        assertTrue(s.isDrilled)
    }

    @Test
    fun `choosing a plain row closes the menu and says it happens on the device`() {
        val s = inGames().send(PreviewNavAction.OpenOptions, PreviewNavAction.Enter)
        assertNull(s.flyout)
        assertNotNull(s.message)
        assertTrue(s.message.contains("View Game Details"))
    }

    @Test
    fun `clicking a row activates it, the scrim dismisses, and tab again closes`() {
        val open = inGames().send(PreviewNavAction.OpenOptions)
        val at = PreviewFlyout.rows(open).indexOfFirst { it.label == "Add to Collection" }
        val sub = open.send(PreviewNavAction.ClickFlyoutRow(at))
        assertEquals(FlyoutMenu.SUBMENU, sub.flyout?.menu)
        assertNull(open.send(PreviewNavAction.DismissFlyout).flyout)
        assertNull(open.send(PreviewNavAction.OpenOptions).flyout)
        assertEquals(open, open.send(PreviewNavAction.ClickFlyoutRow(99)))
    }

    @Test
    fun `the open menu shields the list behind it`() {
        val open = inGames().send(PreviewNavAction.OpenOptions)
        assertEquals(open, open.send(PreviewNavAction.ClickRow(2)))
        assertEquals(open, open.send(PreviewNavAction.ClickCategory(0)))
        assertEquals(open, open.send(PreviewNavAction.ClickSibling))
    }

    @Test
    fun `the menu check joins the on-screen slot keys while a menu is open`() {
        assertFalse("menu_check" in PreviewNav.shownSlotKeys(inGames()))
        assertTrue("menu_check" in PreviewNav.shownSlotKeys(inGames().send(PreviewNavAction.OpenOptions)))
    }

    // ── Games filter ─────────────────────────────────────────────────────────

    @Test
    fun `the filter only opens in the Games category`() {
        assertEquals(PreviewNavState(), PreviewNavState().send(PreviewNavAction.OpenFilter))
        val s = onConsole().send(PreviewNavAction.OpenFilter)
        assertEquals(FlyoutKind.GAMES_FILTER, s.flyout?.kind)
        assertEquals("Filter", PreviewFlyout.title(s))
    }

    @Test
    fun `the filter root names Search and Sort with their values, and Clear Search only with a term`() {
        val root = PreviewFilter.rows(GamesFilter(), FlyoutMenu.ROOT)
        assertEquals(listOf("Search" to "None", "Sort" to "Title"), root.map { it.label to it.value })
        val withTerm = PreviewFilter.rows(GamesFilter(term = "zel"), FlyoutMenu.ROOT)
        assertEquals(listOf("Search" to "\"zel\"", "Sort" to "Title", "Clear Search" to null), withTerm.map { it.label to it.value })
        // Blank is not a term.
        assertEquals(2, PreviewFilter.rows(GamesFilter(term = "   "), FlyoutMenu.ROOT).size)
        val sort = PreviewFilter.rows(GamesFilter(sort = GameSort.RECENT_PLAYED), FlyoutMenu.SORT)
        assertEquals(listOf("Title", "Recently Played", "Date Added"), sort.map { it.label })
        assertEquals(listOf(false, true, false), sort.map { it.checked })
    }

    @Test
    fun `status label names the sort and the active search`() {
        assertEquals("Filter: Title", PreviewFilter.chipLabel(GamesFilter()))
        assertEquals("Filter: \"zel\" · Title", PreviewFilter.chipLabel(GamesFilter(term = "zel")))
        assertEquals("Filter: \"zel\" · Date Added", PreviewFilter.chipLabel(GamesFilter(term = " zel ", sort = GameSort.DATE_ADDED)))
        assertEquals("Filter: Recently Played", PreviewFilter.chipLabel(GamesFilter(term = "  ", sort = GameSort.RECENT_PLAYED)))
        // Only a game list carries the chip.
        assertNull(PreviewNav.statusLabel(PreviewNavState()))
        assertNull(PreviewNav.statusLabel(onConsole()))
        assertEquals("Filter: Title", PreviewNav.statusLabel(inGames()))
    }

    @Test
    fun `sort opens on the checked choice, picking one applies it and returns the cursor to the top`() {
        var s = inGames().send(PreviewNavAction.Down, PreviewNavAction.Down) // third game selected
        s = s.send(PreviewNavAction.OpenFilter, PreviewNavAction.Down, PreviewNavAction.Enter)
        assertEquals(FlyoutMenu.SORT, s.flyout?.menu)
        assertEquals("Sort", PreviewFlyout.title(s))
        assertEquals(0, s.flyout?.cursor) // Title is the checked one
        s = s.send(PreviewNavAction.Down, PreviewNavAction.Enter) // Recently Played
        assertNull(s.flyout)
        assertEquals(GameSort.RECENT_PLAYED, s.filter.sort)
        assertEquals(listOf("Shiba Run", "Crossbar Racing", "Portal Quest", "Memory Card Blues"), titles(s))
        assertEquals(0, s.currentIndex)
        assertEquals("Filter: Recently Played", PreviewNav.statusLabel(s))
        // Back from the sort group lands on the Sort row.
        val again = s.send(PreviewNavAction.OpenFilter, PreviewNavAction.Down, PreviewNavAction.Enter)
        assertEquals(1, again.flyout?.cursor) // opens on the checked row
        assertEquals(1, again.send(PreviewNavAction.Back).flyout?.cursor)
        assertEquals(FlyoutMenu.ROOT, again.send(PreviewNavAction.Back).flyout?.menu)
    }

    @Test
    fun `date added orders the sample games too, and title is the declared order`() {
        assertEquals(allGames, titles(inGames()))
        val s = inGames().send(PreviewNavAction.OpenFilter, PreviewNavAction.Down, PreviewNavAction.Enter)
            .send(PreviewNavAction.Down, PreviewNavAction.Down, PreviewNavAction.Enter)
        assertEquals(GameSort.DATE_ADDED, s.filter.sort)
        assertEquals(listOf("Portal Quest", "Shiba Run", "Memory Card Blues", "Crossbar Racing"), titles(s))
    }

    @Test
    fun `search opens its field from the filter, filters live, and enter keeps the term`() {
        var s = inGames().send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter)
        assertNull(s.flyout)
        assertNotNull(s.search)
        assertTrue(PreviewNav.canGoBack(s))
        s = s.send(PreviewNavAction.SetSearch("AR"))
        assertEquals(listOf("Crossbar Racing", "Memory Card Blues"), titles(s))
        assertEquals("Filter: \"AR\" · Title", PreviewNav.statusLabel(s))
        s = s.send(PreviewNavAction.Enter)
        assertNull(s.search)
        assertEquals("AR", s.filter.term)
        // Clear Search is now in the root and clears it.
        val menu = s.send(PreviewNavAction.OpenFilter)
        assertEquals(listOf("Search", "Sort", "Clear Search"), labels(menu))
        val cleared = menu.send(PreviewNavAction.Down, PreviewNavAction.Down, PreviewNavAction.Enter)
        assertEquals("", cleared.filter.term)
        assertNull(cleared.flyout)
        assertEquals(allGames, titles(cleared))
    }

    @Test
    fun `typing sends the cursor back to the top and a search with no hits leaves an empty list`() {
        var s = inGames().send(PreviewNavAction.Down, PreviewNavAction.Down)
        s = s.send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter, PreviewNavAction.SetSearch("ar"))
        assertEquals(0, s.currentIndex)
        s = s.send(PreviewNavAction.SetSearch("zzz"))
        assertTrue(titles(s).isEmpty())
        assertEquals(s, s.send(PreviewNavAction.Enter).copy(search = s.search, flyout = s.flyout))
    }

    @Test
    fun `escape in the search field restores the term it opened with`() {
        val kept = inGames().send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter, PreviewNavAction.SetSearch("ar"), PreviewNavAction.Enter)
        val s = kept.send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter, PreviewNavAction.SetSearch("shiba"))
        assertEquals(listOf("Shiba Run"), titles(s))
        val back = s.send(PreviewNavAction.Back)
        assertNull(back.search)
        assertEquals("ar", back.filter.term)
        assertEquals(listOf("Crossbar Racing", "Memory Card Blues"), titles(back))
    }

    @Test
    fun `navigation is shut out while the search field has the keyboard`() {
        val s = inGames().send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter)
        listOf(
            PreviewNavAction.Up, PreviewNavAction.Down, PreviewNavAction.Left, PreviewNavAction.Right,
            PreviewNavAction.OpenOptions, PreviewNavAction.OpenFilter, PreviewNavAction.ClickRow(1),
        ).forEach { assertEquals(s, s.send(it), "$it") }
    }

    @Test
    fun `the term clears when the game list is left, the sort stays`() {
        val searched = inGames().send(
            PreviewNavAction.OpenFilter, PreviewNavAction.Down, PreviewNavAction.Enter, PreviewNavAction.Down, PreviewNavAction.Enter,
            PreviewNavAction.OpenFilter, PreviewNavAction.Enter, PreviewNavAction.SetSearch("ar"), PreviewNavAction.Enter,
        )
        assertEquals("ar", searched.filter.term)
        val out = searched.send(PreviewNavAction.Back)
        assertEquals("", out.filter.term)
        assertEquals(GameSort.RECENT_PLAYED, out.filter.sort)
        // Changing category clears it too.
        val mid = onConsole().send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter, PreviewNavAction.SetSearch("x"), PreviewNavAction.Enter)
        assertEquals("x", mid.filter.term)
        assertEquals("", mid.send(PreviewNavAction.Left).filter.term)
    }

    @Test
    fun `the filter only touches game lists`() {
        val s = PreviewNavState().send(PreviewNavAction.Right) // Games, console cards
        val before = titles(s)
        val sorted = s.copy(filter = GamesFilter(term = "zzz", sort = GameSort.DATE_ADDED))
        assertEquals(before, titles(sorted))
        val music = SampleContent.rootRows(categories.indexOfFirst { it.slotKey == "catbar_video" })
        assertEquals(music, PreviewFilter.apply(music, GamesFilter(term = "zzz")))
    }

    // ── PIC0 ─────────────────────────────────────────────────────────────────

    @Test
    fun `pic0 timing and placement mirror the launcher`() {
        assertEquals(650L, PreviewPic0.DELAY_MS)
        assertEquals(500, PreviewPic0.fadeMs(visible = true))
        assertEquals(0, PreviewPic0.fadeMs(visible = false)) // hides instantly
        assertEquals(0.30f, PreviewPic0.WIDTH_FRACTION)
        assertEquals(0.38f, PreviewPic0.HEIGHT_FRACTION)
        assertEquals(44f, PreviewPic0.END_PADDING)
    }

    @Test
    fun `only a focused game has a logo`() {
        assertNull(PreviewPic0.logoTitle(PreviewNavState()))
        assertNull(PreviewPic0.logoTitle(onConsole()))
        assertEquals("Crossbar Racing", PreviewPic0.logoTitle(inGames()))
        assertEquals("Memory Card Blues", PreviewPic0.logoTitle(inGames().send(PreviewNavAction.Down)))
        assertNull(PreviewPic0.logoTitle(inGames().send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter, PreviewNavAction.SetSearch("zzz"))))
    }

    @Test
    fun `the logo centres on the active row, clamped to the screen`() {
        val spec = XmbLayoutSpec.DEFAULT
        val adjust = XmbLayoutAdjust(1f, 0f, spec.barTopFraction)
        assertEquals(0f, PreviewPic0.centerOffsetDp(spec, adjust, 468f, drilled = false))
        val cross = 468f - spec.contentTopPaddingDp
        val expected = spec.contentTopPaddingDp + cross * 0.11f + 112f + 44f - 468f / 2
        assertTrue(kotlin.math.abs(expected - PreviewPic0.centerOffsetDp(spec, adjust, 468f, drilled = true)) < 0.001f)
        // A short box pushes the row centre past 81 %: the clamp holds the logo on screen.
        assertTrue(kotlin.math.abs(0.81f * 200f - 100f - PreviewPic0.centerOffsetDp(spec, adjust, 200f, drilled = true)) < 0.001f)
    }

    // ── Legibility ───────────────────────────────────────────────────────────

    @Test
    fun `a theme that says nothing renders today's shadow, plain icons, dimmed unfocused icons`() {
        val none = PreviewLegibility.of(null)
        assertEquals(PreviewLegibility.DEFAULT, none)
        assertEquals(LabelProtection.SHADOW, none.text)
        assertEquals(IconMatteStyle.NONE, none.icon)
        assertFalse(none.solidUnfocusedIcons)
        assertEquals(0.58f, none.categoryIconAlpha(selected = false))
    }

    @Test
    fun `text styles map to render params and auto keeps the shadow floor`() {
        fun text(v: String?) = PreviewLegibility.of(ThemeLegibility(text = v)).text
        assertEquals(LabelProtection.SHADOW, text("auto"))
        assertEquals(LabelProtection.NONE, text("none"))
        assertEquals(LabelProtection.SHADOW, text("shadow"))
        assertEquals(LabelProtection.OUTLINE, text("outline"))
        assertEquals(LabelProtection.PLATE, text("plate"))
        assertEquals(LabelProtection.SHADOW, text("something-from-v9"))
        assertTrue(LabelProtection.SHADOW.hasShadow && !LabelProtection.NONE.hasShadow && !LabelProtection.OUTLINE.hasShadow)
    }

    @Test
    fun `icon styles map to launcher matte geometry and colours`() {
        fun icon(v: String?) = PreviewLegibility.of(ThemeLegibility(icon = v))
        assertEquals(0, icon("none").matteOffsets().size)
        assertEquals(listOf(1f to 1f), icon("offset_shadow").matteOffsets().map { it.x to it.y })
        assertEquals(8, icon("contour_dark").matteOffsets().size)
        assertEquals(8, icon("contour_auto").matteOffsets().size)
        assertEquals(1.25f, icon("offset_shadow").matteRadiusDp)
        assertEquals(1.75f, icon("contour_dark").matteRadiusDp)
        assertNull(icon("none").matteColor(Color.White))
        // Auto: a light glyph gets the dark matte, a dark glyph the light one.
        val dark = icon("contour_dark").matteColor(Color.White)!!
        val light = icon("contour_light").matteColor(Color.White)!!
        assertEquals(dark, icon("contour_auto").matteColor(Color.White))
        assertEquals(light, icon("contour_auto").matteColor(Color.Black))
        assertEquals(IconMatteStyle.NONE, icon("junk").icon)
    }

    @Test
    fun `solid unfocused icons lift the category dim`() {
        val solid = PreviewLegibility.of(ThemeLegibility(solidUnfocusedIcons = true))
        assertEquals(1f, solid.categoryIconAlpha(selected = false))
        assertEquals(1f, solid.categoryIconAlpha(selected = true))
        assertEquals(PreviewLegibility.of(ThemeLegibility(text = "outline")).categoryIconAlpha(selected = false), 0.58f)
    }

    @Test
    fun `the model carries the theme's legibility`() {
        val state = StudioState(legibility = ThemeLegibility("plate", "contour_light", true))
        val m = state.toPreviewModel().legibility
        assertEquals(LabelProtection.PLATE, m.text)
        assertEquals(IconMatteStyle.CONTOUR_LIGHT, m.icon)
        assertTrue(m.solidUnfocusedIcons)
    }

    // ── Layout-adjust overlay ────────────────────────────────────────────────

    private val nodes = mutableListOf<Preferences>()

    @AfterTest
    fun cleanUp() {
        nodes.forEach { runCatching { it.removeNode() } }
        nodes.clear()
    }

    private fun store(): PreviewAdjustStore =
        PreviewAdjustStore(Preferences.userRoot().node("pfp-studio-test-${UUID.randomUUID()}").also { nodes += it })

    private fun AdjustOverlayState.edit(vararg actions: AdjustAction): AdjustOverlayState = actions.fold(this) { s, a ->
        (AdjustOverlay.step(s, a) as AdjustStep.Editing).state
    }

    @Test
    fun `the readout reads like the launcher's`() {
        assertEquals(
            "Scale 1.00x    Horizontal 0%    Vertical 11%",
            AdjustOverlay.readout(XmbLayoutAdjust.DEFAULT),
        )
        assertEquals(
            "Scale 1.30x    Horizontal 5%    Vertical 20%",
            AdjustOverlay.readout(XmbLayoutAdjust(1.3f, 0.05f, 0.2f)),
        )
    }

    @Test
    fun `the overlay opens on the stored values`() {
        val store = store()
        store.setScale(1.4f)
        assertEquals(store.adjust.value, AdjustOverlay.open(store).draft)
        assertFalse(AdjustOverlay.open(store).slidersVisible)
    }

    @Test
    fun `arrows nudge position by one percent and Q E nudge scale by two hundredths`() {
        val s = AdjustOverlayState(XmbLayoutAdjust.DEFAULT)
        assertEquals(0.01f, s.edit(AdjustAction.MoveRight).draft.barLeftFraction)
        assertEquals(-0.01f, s.edit(AdjustAction.MoveLeft).draft.barLeftFraction)
        assertEquals(0.10f, s.edit(AdjustAction.MoveUp).draft.barTopFraction)
        assertEquals(0.12f, s.edit(AdjustAction.MoveDown).draft.barTopFraction)
        assertEquals(1.02f, s.edit(AdjustAction.ScaleUp).draft.scale)
        assertEquals(0.98f, s.edit(AdjustAction.ScaleDown).draft.scale)
        // Repeated nudges do not drift off the grid.
        assertEquals(0.10f, s.edit(*Array(10) { AdjustAction.MoveRight }).draft.barLeftFraction)
    }

    @Test
    fun `nudges clamp at the launcher's bounds`() {
        val s = AdjustOverlayState(XmbLayoutAdjust.DEFAULT)
        assertEquals(XmbLayoutAdjust.SCALE_MAX, s.edit(*Array(100) { AdjustAction.ScaleUp }).draft.scale)
        assertEquals(XmbLayoutAdjust.SCALE_MIN, s.edit(*Array(100) { AdjustAction.ScaleDown }).draft.scale)
        assertEquals(XmbLayoutAdjust.LEFT_MIN, s.edit(*Array(100) { AdjustAction.MoveLeft }).draft.barLeftFraction)
        assertEquals(XmbLayoutAdjust.LEFT_MAX, s.edit(*Array(100) { AdjustAction.MoveRight }).draft.barLeftFraction)
        assertEquals(XmbLayoutAdjust.TOP_MIN, s.edit(*Array(100) { AdjustAction.MoveUp }).draft.barTopFraction)
        assertEquals(XmbLayoutAdjust.TOP_MAX, s.edit(*Array(100) { AdjustAction.MoveDown }).draft.barTopFraction)
    }

    @Test
    fun `slider values clamp and snap, reset restores defaults, sliders toggle`() {
        val s = AdjustOverlayState(XmbLayoutAdjust(1.5f, 0.2f, 0.3f))
        assertEquals(1.02f, s.edit(AdjustAction.SetScale(1.013f)).draft.scale)
        assertEquals(XmbLayoutAdjust.LEFT_MAX, s.edit(AdjustAction.SetHorizontal(9f)).draft.barLeftFraction)
        assertEquals(0.3f, s.edit(AdjustAction.SetVertical(Float.NaN)).draft.barTopFraction)
        assertEquals(XmbLayoutAdjust.DEFAULT, s.edit(AdjustAction.Reset).draft)
        assertTrue(s.edit(AdjustAction.ToggleSliders).slidersVisible)
        assertFalse(s.edit(AdjustAction.ToggleSliders, AdjustAction.ToggleSliders).slidersVisible)
        // Reset keeps the sliders as they were.
        assertTrue(s.edit(AdjustAction.ToggleSliders, AdjustAction.Reset).slidersVisible)
    }

    @Test
    fun `save hands back the draft and commit writes the store, cancel leaves it alone`() {
        val store = store()
        val before = store.adjust.value
        val moved = AdjustOverlay.open(store).edit(AdjustAction.ScaleUp, AdjustAction.MoveRight, AdjustAction.MoveDown)

        assertEquals(AdjustStep.Cancelled, AdjustOverlay.step(moved, AdjustAction.Cancel))
        assertEquals(before, store.adjust.value)
        assertFalse(store.enabled.value)

        val saved = AdjustOverlay.step(moved, AdjustAction.Save) as AdjustStep.Saved
        assertEquals(moved.draft, saved.adjust)
        assertEquals(before, store.adjust.value) // step itself is pure
        AdjustOverlay.commit(store, saved.adjust)
        assertEquals(XmbLayoutAdjust(1.02f, 0.01f, 0.12f), store.adjust.value)
        assertTrue(store.enabled.value) // a saved layout is one you want to see
    }

    @Test
    fun `overlay keys`() {
        assertEquals(AdjustAction.MoveLeft, AdjustOverlay.actionFor(Key.DirectionLeft))
        assertEquals(AdjustAction.MoveRight, AdjustOverlay.actionFor(Key.DirectionRight))
        assertEquals(AdjustAction.MoveUp, AdjustOverlay.actionFor(Key.DirectionUp))
        assertEquals(AdjustAction.MoveDown, AdjustOverlay.actionFor(Key.DirectionDown))
        assertEquals(AdjustAction.ScaleDown, AdjustOverlay.actionFor(Key.Q))
        assertEquals(AdjustAction.ScaleUp, AdjustOverlay.actionFor(Key.E))
        assertEquals(AdjustAction.Reset, AdjustOverlay.actionFor(Key.R))
        assertEquals(AdjustAction.ToggleSliders, AdjustOverlay.actionFor(Key.S))
        assertEquals(AdjustAction.Save, AdjustOverlay.actionFor(Key.Enter))
        assertEquals(AdjustAction.Cancel, AdjustOverlay.actionFor(Key.Escape))
        assertNull(AdjustOverlay.actionFor(Key.Tab))
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /** Renders [nav] / [overlay] through [frames] frames of 16.7 ms starting at [startMs]. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(
        state: StudioState,
        nav: PreviewNavState,
        overlay: AdjustOverlayState? = null,
        frames: Int = 3,
        startMs: Long = 0,
    ) {
        val model = state.toPreviewModel(overlay?.draft)
        EventQueue.invokeAndWait {
            ImageComposeScene(width = 1280, height = 720, density = Density(1280f / PreviewGeometry.BASE_WIDTH)).use { scene ->
                scene.setContent { XmbFrame(model, nav, adjustOverlay = overlay) }
                var t = startMs * 1_000_000L
                repeat(frames) { scene.render(t); t += 16_666_667L }
            }
        }
    }

    @Test
    fun `flyouts render for every row type, the submenu and the filter menus`() {
        val s = StudioState()
        render(s, inGames().send(PreviewNavAction.OpenOptions))
        render(s, onConsole().send(PreviewNavAction.OpenOptions))
        render(s, PreviewNavState().send(PreviewNavAction.OpenOptions))
        val sub = inGames().send(PreviewNavAction.OpenOptions, PreviewNavAction.ClickFlyoutRow(3))
        assertEquals(FlyoutMenu.SUBMENU, sub.flyout?.menu)
        render(s, sub)
        render(s, inGames().send(PreviewNavAction.OpenFilter))
        render(s, inGames().send(PreviewNavAction.OpenFilter, PreviewNavAction.Down, PreviewNavAction.Enter))
        // A themed check mark takes the override path.
        render(s.copy(iconBitmaps = mapOf("menu_check" to androidx.compose.ui.graphics.ImageBitmap(15, 15))), sub)
    }

    @Test
    fun `the search field and its chip render, with and without a term`() {
        val s = StudioState()
        render(s, inGames().send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter))
        render(s, inGames().send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter, PreviewNavAction.SetSearch("ar")))
        render(s, inGames().send(PreviewNavAction.OpenFilter, PreviewNavAction.Enter, PreviewNavAction.SetSearch("zzz")))
    }

    @Test
    fun `pic0 renders before, during and after its fade`() {
        val s = StudioState()
        render(s, inGames(), frames = 20, startMs = 0)      // inside the 650 ms linger
        render(s, inGames(), frames = 90, startMs = 0)      // past the delay, mid fade-in
        render(s, inGames(), frames = 200, startMs = 0)     // settled
    }

    @Test
    fun `every legibility style renders over labels and icons`() {
        listOf("none", "shadow", "outline", "plate", "auto").forEach { text ->
            listOf("none", "offset_shadow", "contour_dark", "contour_light", "contour_auto").forEach { icon ->
                render(StudioState(legibility = ThemeLegibility(text, icon, true)), inGames(), frames = 2)
            }
        }
        render(StudioState(legibility = ThemeLegibility("outline", "contour_auto", false)), PreviewNavState().send(PreviewNavAction.Enter))
    }

    @Test
    fun `the adjust overlay renders with and without sliders over the live draft`() {
        val s = StudioState()
        val open = AdjustOverlayState(XmbLayoutAdjust(1.2f, 0.04f, 0.2f))
        render(s, PreviewNavState(), open)
        render(s, PreviewNavState(), open.copy(slidersVisible = true))
    }

    @Test
    fun `a nav change mid-flyout re-renders without throwing`() {
        val model = StudioState().toPreviewModel()
        var nav by mutableStateOf(inGames())
        EventQueue.invokeAndWait {
            ImageComposeScene(width = 1280, height = 720, density = Density(1280f / PreviewGeometry.BASE_WIDTH)).use { scene ->
                scene.setContent { XmbFrame(model, nav) }
                var t = 0L
                fun frames(n: Int) = repeat(n) { scene.render(t); t += 16_666_667L }
                frames(3)
                listOf(
                    PreviewNavAction.OpenOptions, PreviewNavAction.Down, PreviewNavAction.Enter, PreviewNavAction.Back,
                    PreviewNavAction.Back, PreviewNavAction.OpenFilter, PreviewNavAction.Enter, PreviewNavAction.SetSearch("ar"),
                    PreviewNavAction.Enter, PreviewNavAction.Down, PreviewNavAction.Back,
                ).forEach { nav = PreviewNav.reduce(nav, it); frames(4) }
            }
        }
    }
}
