package com.playfieldportal.studio.preview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.playfieldportal.studio.StudioState
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.XmbLayoutAdjust
import com.playfieldportal.themekit.XmbLayoutSpec
import java.awt.EventQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TS-31: the preview's navigation reducer, its sample content, and the launcher-pinned geometry. */
class PreviewNavTest {

    private fun PreviewNavState.send(vararg actions: PreviewNavAction): PreviewNavState =
        actions.fold(this) { s, a -> PreviewNav.reduce(s, a) }

    private val categories get() = SampleContent.categories
    private val gameIndex get() = categories.indexOfFirst { it.slotKey == "catbar_games" }
    private val ps3 get() = SampleContent.rootRows(gameIndex).indexOfFirst { it.slotKey == "sysicon_ps3" }

    private fun openGame(console: Int): PreviewNavState {
        var s = PreviewNavState()
        repeat(gameIndex - s.category) { s = s.send(PreviewNavAction.Right) }
        repeat(console - s.currentIndex) { s = s.send(PreviewNavAction.Down) }
        return s.send(PreviewNavAction.Enter)
    }

    // ── Reducer ──────────────────────────────────────────────────────────────

    @Test
    fun `starts where the launcher does - Game, on All Games`() {
        val s = PreviewNavState()
        assertEquals("catbar_games", categories[s.category].slotKey)
        assertEquals("All Games", PreviewNav.selectedRow(s)?.title)
        // The UMD slot sits above it.
        assertEquals(SampleContent.Leading.UMD, SampleContent.rows[s.currentIndex - 1].leading)
        assertFalse(s.isDrilled)
        assertEquals(s, PreviewNavState.HOME)
    }

    @Test
    fun `left and right step categories and stop at both ends`() {
        var s = PreviewNavState()
        repeat(categories.size + 3) { s = s.send(PreviewNavAction.Left) }
        assertEquals(0, s.category)
        repeat(categories.size + 3) { s = s.send(PreviewNavAction.Right) }
        assertEquals(categories.lastIndex, s.category)
    }

    @Test
    fun `up and down step rows and stop at both ends`() {
        val rows = SampleContent.rootRows(PreviewNavState().category)
        var s = PreviewNavState()
        repeat(rows.size + 3) { s = s.send(PreviewNavAction.Down) }
        assertEquals(rows.lastIndex, s.currentIndex)
        repeat(rows.size + 3) { s = s.send(PreviewNavAction.Up) }
        assertEquals(0, s.currentIndex)
    }

    @Test
    fun `moving onto a category lands on its library card, else its top row, every time`() {
        var s = PreviewNavState().send(PreviewNavAction.Left) // Game -> Video
        assertEquals("Videos", PreviewNav.selectedRow(s)?.title)
        s = s.send(PreviewNavAction.Left)
        assertEquals("Midnight Wave", PreviewNav.selectedRow(s)?.title) // Now Playing
        s = s.send(PreviewNavAction.Left)
        assertEquals("Photos", PreviewNav.selectedRow(s)?.title)
        s = s.send(PreviewNavAction.Left)
        assertEquals("Android Settings", PreviewNav.selectedRow(s)?.title)
        // A row moved to is not remembered: coming back lands again.
        s = s.send(PreviewNavAction.Down, PreviewNavAction.Down, PreviewNavAction.Right, PreviewNavAction.Left)
        assertEquals(0, s.currentIndex)
        // The rows above the landing row stay reachable.
        val photo = PreviewNavState().send(PreviewNavAction.Left, PreviewNavAction.Left, PreviewNavAction.Left)
        assertEquals("Albums", PreviewNav.selectedRow(photo.send(PreviewNavAction.Up, PreviewNavAction.Up))?.title)
    }

    @Test
    fun `enter drills into a branch, back pops it, back at the root does nothing`() {
        val s0 = openGame(ps3)
        assertTrue(s0.isDrilled)
        assertEquals(1, s0.drill.size)
        val s1 = s0.send(PreviewNavAction.Back)
        assertFalse(s1.isDrilled)
        assertEquals(ps3, s1.currentIndex)
        assertTrue(PreviewNav.canGoBack(s0))
        assertFalse(PreviewNav.canGoBack(s1))
        assertEquals(s1, s1.send(PreviewNavAction.Back))
    }

    @Test
    fun `categories are locked while drilled`() {
        val drilled = openGame(ps3)
        assertEquals(drilled, drilled.send(PreviewNavAction.Left))
        assertEquals(drilled, drilled.send(PreviewNavAction.Right))
        assertEquals(drilled, drilled.send(PreviewNavAction.ClickCategory(0)))
    }

    @Test
    fun `up and down inside a drill move the drilled row and leave the parent row alone`() {
        val drilled = openGame(ps3)
        val moved = drilled.send(PreviewNavAction.Down)
        assertEquals(1, moved.currentIndex)
        val out = moved.send(PreviewNavAction.Back)
        assertEquals(ps3, out.currentIndex)
        // Re-entering starts at the top again.
        assertEquals(0, out.send(PreviewNavAction.Enter).currentIndex)
    }

    @Test
    fun `the drilled view pairs the parent column with the children`() {
        val drilled = openGame(ps3)
        val view = PreviewNav.view(drilled)
        val parentRows = SampleContent.rootRows(gameIndex)
        assertEquals(parentRows, view.siblings)
        assertEquals(ps3, view.siblingIndex)
        assertEquals(parentRows[ps3].children, view.rows)
        assertEquals(0, view.selected)
    }

    @Test
    fun `at the root the view has no sibling column`() {
        val view = PreviewNav.view(PreviewNavState())
        assertNull(view.siblings)
        assertEquals(SampleContent.rows, view.rows)
    }

    @Test
    fun `drilling two levels deep slides the sibling column down a level`() {
        val video = categories.indexOfFirst { it.slotKey == "catbar_video" }
        val collections = SampleContent.rootRows(video).indexOfFirst { it.title == "Collections" }
        var s = PreviewNavState()
        repeat(s.category - video) { s = s.send(PreviewNavAction.Left) }
        repeat(s.currentIndex - collections) { s = s.send(PreviewNavAction.Up) }
        s = s.send(PreviewNavAction.Enter) // into Collections
        val children = SampleContent.rootRows(video)[collections].children
        assertEquals(listOf("Recently Watched", "Favorites", "Playlists"), children.map { it.title })
        val playlists = children.indexOfFirst { it.title == "Playlists" }
        repeat(playlists) { s = s.send(PreviewNavAction.Down) }
        s = s.send(PreviewNavAction.Enter) // into the video playlists
        assertEquals(2, s.drill.size)
        val view = PreviewNav.view(s)
        assertEquals(children, view.siblings)
        assertEquals(playlists, view.siblingIndex)
        assertEquals(listOf("Highlights", "Create Playlist", "Import Playlist"), view.rows.map { it.title })
        assertEquals(1, s.send(PreviewNavAction.Back).drill.size)
    }

    @Test
    fun `a scanned library drills into its files, which open on the device`() {
        val s = PreviewNavState().send(PreviewNavAction.Left, PreviewNavAction.Enter) // Video > Videos
        val row = PreviewNav.selectedRow(s)!!
        assertEquals("item_video_file", row.slotKey)
        assertEquals(SampleContent.Leading.THUMB, row.leading)
        assertNotNull(s.send(PreviewNavAction.Enter).message)
    }

    @Test
    fun `social is signed in and its hub drills`() {
        var s = PreviewNavState().send(*Array(3) { PreviewNavAction.Right })
        assertEquals("PlayerOne", PreviewNav.selectedRow(s)?.title)
        s = s.send(PreviewNavAction.Enter)
        assertEquals(listOf("Friends", "Voice", "Activity Settings", "Discord Settings"), PreviewNav.view(s).rows.map { it.title })
        s = s.send(PreviewNavAction.Down, PreviewNavAction.Enter) // Voice
        assertEquals(listOf("Create Lobby", "Invites", "Voice Settings"), PreviewNav.view(s).rows.map { it.title })
        s = s.send(PreviewNavAction.Down, PreviewNavAction.Down, PreviewNavAction.Enter) // Voice Settings
        assertEquals(3, s.drill.size)
        assertEquals("Mic Sensitivity", PreviewNav.selectedRow(s)?.title)
    }

    @Test
    fun `a game inside a console is a leaf and enter reports instead of drilling`() {
        val s = openGame(ps3).send(PreviewNavAction.Enter)
        assertEquals(1, s.drill.size)
        assertNotNull(s.message)
    }

    @Test
    fun `any other action clears the status message`() {
        val opened = openGame(ps3).send(PreviewNavAction.Enter)
        assertNotNull(opened.message)
        assertNull(opened.send(PreviewNavAction.Down).message)
    }

    @Test
    fun `clicks select first and open on the second click`() {
        val s0 = PreviewNavState()
        val s1 = s0.send(PreviewNavAction.ClickRow(2))
        assertEquals(2, s1.currentIndex)
        assertFalse(s1.isDrilled)
        val s2 = s1.send(PreviewNavAction.ClickRow(2))
        assertTrue(s2.isDrilled || s2.message != null)
        assertEquals(s0, s0.send(PreviewNavAction.ClickRow(99)))
    }

    @Test
    fun `clicking a category selects it and clicking the active sibling backs out`() {
        val s = PreviewNavState().send(PreviewNavAction.ClickCategory(0))
        assertEquals(0, s.category)
        val drilled = openGame(ps3)
        assertFalse(drilled.send(PreviewNavAction.ClickSibling).isDrilled)
    }

    @Test
    fun `every category has rows and every branch has children`() {
        categories.indices.forEach { c ->
            val rows = SampleContent.rootRows(c)
            assertTrue(rows.isNotEmpty(), "category $c has no rows")
        }
    }

    @Test
    fun `sample content mirrors the launcher's rows`() {
        val titles = { c: String -> SampleContent.rootRows(categories.indexOfFirst { it.slotKey == c }).map { it.title } }
        assertEquals(
            listOf(
                "catbar_settings", "catbar_photos", "catbar_music", "catbar_video", "catbar_games",
                "catbar_network", "catbar_appstore", "catbar_social", "catbar_achievements",
            ),
            categories.map { it.slotKey },
        )
        assertEquals(
            listOf("Android Settings", "Library", "Emulators", "Interface", "Achievements", "Media", "System"),
            titles("catbar_settings"),
        )
        assertEquals(listOf("Camera", "Albums", "Photo Apps", "Photos"), titles("catbar_photos"))
        assertEquals(listOf("Midnight Wave", "Playlist", "Music Apps", "Music"), titles("catbar_music"))
        assertEquals(listOf("Collections", "Video Libraries", "Video Apps", "Videos"), titles("catbar_video"))
        // Network and App Store hold installed apps then Add Apps; Wi-Fi and Bluetooth live in the status strip only.
        assertEquals("Add Apps", titles("catbar_network").last())
        assertEquals("Add Apps", titles("catbar_appstore").last())
        val network = SampleContent.rootRows(categories.indexOfFirst { it.slotKey == "catbar_network" })
        assertTrue(network.none { it.slotKey?.startsWith("status_") == true })
        assertEquals(listOf("PlayerOne"), titles("catbar_social"))
        assertEquals(listOf("Ruffian", "All Tracked Games", "Untracked"), titles("catbar_achievements"))
        val games = SampleContent.rootRows(gameIndex)
        assertEquals(listOf("All Games", "Favorites", "Co-op Night"), games.drop(1).take(3).map { it.title })
        val all = games.single { it.title == "All Games" }
        assertEquals("Total Games ${all.children.size}", all.subtitle)
        listOf("sysicon_ps3", "sysicon_psp", "sysicon_windows").forEach { key ->
            assertTrue(games.any { it.slotKey == key && it.children.isNotEmpty() }, "$key console missing or empty")
        }
    }

    @Test
    fun `every sample slot key is a real icon slot and only icon rows carry one`() {
        fun walk(rows: List<SampleContent.Row>): Unit = rows.forEach {
            val themeable = it.leading in setOf(SampleContent.Leading.SLOT, SampleContent.Leading.THUMB, SampleContent.Leading.COVER)
            if (themeable) {
                val key = it.slotKey
                assertNotNull(key, "${it.title} has no slot key")
                assertTrue(CustomizableIcons.isValidKey(key), "bad slot key $key")
            } else {
                assertNull(it.slotKey, "${it.title} is not themeable")
            }
            walk(it.children)
        }
        categories.indices.forEach { walk(SampleContent.rootRows(it)) }
    }

    @Test
    fun `the strip and the hint pill name what the list on screen does`() {
        val root = PreviewNavState()
        assertNull(PreviewNav.statusLabel(root)) // a Games root never custom-arranged names nothing
        assertEquals(PreviewNav.IdleHint("Sort", options = true), PreviewNav.idleHint(root))
        val network = root.send(PreviewNavAction.Right)
        assertEquals("Sort: A–Z", PreviewNav.statusLabel(network))
        val settings = root.send(*Array(4) { PreviewNavAction.Left })
        assertNull(PreviewNav.statusLabel(settings))
        assertNull(PreviewNav.idleHint(settings)) // nothing to sort, no menu: no pill
        // The Social account row has a menu (Reconnect) but nothing to sort.
        val account = root.send(*Array(3) { PreviewNavAction.Right })
        assertEquals(PreviewNav.IdleHint(null, options = true), PreviewNav.idleHint(account))
        val videos = root.send(PreviewNavAction.Left, PreviewNavAction.Enter)
        assertEquals("Sort: Title", PreviewNav.statusLabel(videos))
        val games = openGame(ps3)
        assertEquals("Filter: Title", PreviewNav.statusLabel(games))
        assertEquals(PreviewNav.IdleHint("Filter", options = true), PreviewNav.idleHint(games))
    }

    @Test
    fun `shown slot keys cover the selected category, the sibling column and the children`() {
        val keys = PreviewNav.shownSlotKeys(openGame(ps3))
        assertTrue("catbar_games" in keys)
        assertTrue("sysicon_ps3" in keys && "sysicon_psp" in keys)
        assertEquals("catbar_games", PreviewNav.categoryKey(openGame(ps3)))
    }

    @Test
    fun `keys map to actions`() {
        assertEquals(PreviewNavAction.Left, PreviewNav.actionFor(Key.DirectionLeft))
        assertEquals(PreviewNavAction.Right, PreviewNav.actionFor(Key.DirectionRight))
        assertEquals(PreviewNavAction.Up, PreviewNav.actionFor(Key.DirectionUp))
        assertEquals(PreviewNavAction.Down, PreviewNav.actionFor(Key.DirectionDown))
        assertEquals(PreviewNavAction.Enter, PreviewNav.actionFor(Key.Enter))
        assertEquals(PreviewNavAction.Enter, PreviewNav.actionFor(Key.NumPadEnter))
        assertEquals(PreviewNavAction.Back, PreviewNav.actionFor(Key.Escape))
        assertEquals(PreviewNavAction.Back, PreviewNav.actionFor(Key.Backspace))
        assertNull(PreviewNav.actionFor(Key.Spacebar)) // Tab / Y / X are the flyout's (TS-32)
    }

    // ── Geometry (pinned to XMBShell / XMBCategoryBar / XMBItemList) ──────────

    private val spec = XmbLayoutSpec.DEFAULT
    private val noAdjust = XmbLayoutAdjust(scale = 1f, barLeftFraction = 0f, barTopFraction = spec.barTopFraction)
    private val eps = 0.001f

    private fun assertNear(expected: Float, actual: Float) =
        assertTrue(kotlin.math.abs(expected - actual) < eps, "expected $expected but was $actual")

    @Test
    fun `base canvas and launcher constants`() {
        assertEquals(832f, PreviewGeometry.BASE_WIDTH)
        assertEquals(468f, PreviewGeometry.BASE_HEIGHT)
        assertEquals(124f, PreviewGeometry.CATEGORY_SLOT)
        assertEquals(112f, PreviewGeometry.CAT_BAR_HEIGHT)
        assertEquals(88f, PreviewGeometry.ROW_HEIGHT)
        assertEquals(16f, PreviewGeometry.DRILL_LEFT_MARGIN)
        assertEquals(138f, PreviewGeometry.DRILL_CHILD_COLUMN_LEFT)
    }

    @Test
    fun `anchor and item column start pad`() {
        assertNear(130f, PreviewGeometry.leftAnchor(spec))
        assertNear(55f, PreviewGeometry.leadingIconCenter(spec))
        assertNear(137f, PreviewGeometry.columnBaseInset(spec))
    }

    @Test
    fun `bar slides one slot per category`() {
        assertNear(0f, PreviewGeometry.barSlide(0))
        assertNear(-372f, PreviewGeometry.barSlide(3))
        assertNear(130f - 372f, PreviewGeometry.barLeft(spec, 3, drilled = false, noAdjust, 832f))
    }

    @Test
    fun `anchor top follows barTopFraction under the content padding`() {
        val crossHeight = 468f - spec.contentTopPaddingDp
        assertNear(crossHeight * 0.11f, PreviewGeometry.barTop(spec, noAdjust, 468f))
        assertNear(crossHeight * 0.11f + 112f, PreviewGeometry.anchorTop(spec, noAdjust, 468f))
        val moved = noAdjust.copy(barTopFraction = 0.3f)
        assertNear(crossHeight * 0.3f + 112f, PreviewGeometry.anchorTop(spec, moved, 468f))
    }

    @Test
    fun `drilling shifts the cross to the left margin`() {
        assertNear(-121f, PreviewGeometry.hShift(spec, drilled = true, noAdjust, 832f))
        assertNear(16f, PreviewGeometry.startPad(spec, drilled = true, noAdjust, 832f))
        assertNear(154f, PreviewGeometry.childColumnLeft(spec, noAdjust, 832f))
        // The bar's anchor rides the same shift: 130 - 121.
        assertNear(9f, PreviewGeometry.barLeft(spec, 3, drilled = true, noAdjust, 832f) - PreviewGeometry.barSlide(3))
    }

    @Test
    fun `the bar anchor never goes negative`() {
        val far = noAdjust.copy(barLeftFraction = -0.25f)
        assertNear(0f, PreviewGeometry.barLeft(spec, 0, drilled = false, far, 832f))
    }

    @Test
    fun `a drilled bar keeps only the categories up to the selected one`() {
        assertEquals(4, PreviewGeometry.visibleCategoryCount(selected = 3, count = 10, drilled = true))
        assertEquals(10, PreviewGeometry.visibleCategoryCount(selected = 3, count = 10, drilled = false))
    }

    @Test
    fun `layout adjust shrinks the layout box and shifts by width fraction`() {
        val adj = XmbLayoutAdjust(scale = 1.25f, barLeftFraction = 0.1f, barTopFraction = 0.2f)
        val size = PreviewGeometry.layoutSize(adj)
        assertNear(832f / 1.25f, size.first)
        assertNear(468f / 1.25f, size.second)
        assertNear(size.first * 0.1f, PreviewGeometry.hShift(spec, drilled = false, adj, size.first))
        assertNear((size.second - spec.contentTopPaddingDp) * 0.2f, PreviewGeometry.barTop(spec, adj, size.second))
    }

    @Test
    fun `effective adjust is the theme's own bar line unless the preview layout is on`() {
        val stored = XmbLayoutAdjust(scale = 1.4f, barLeftFraction = 0.2f, barTopFraction = 0.3f)
        val themed = XmbLayoutSpec(barTopFraction = 0.2f)
        assertEquals(XmbLayoutAdjust(1f, 0f, 0.2f), PreviewGeometry.effectiveAdjust(themed, enabled = false, stored))
        assertEquals(stored, PreviewGeometry.effectiveAdjust(themed, enabled = true, stored))
    }

    // ── Model ────────────────────────────────────────────────────────────────

    @Test
    fun `wave modes use the exact value`() {
        // The model carries the exact value; WaveMotion (WaveMotionTest) turns it into the four behaviours.
        for (style in listOf(
            PfpThemeManifest.WAVE_ANIMATED, PfpThemeManifest.WAVE_REDUCED,
            PfpThemeManifest.WAVE_STATIC, PfpThemeManifest.WAVE_REDUCED_STATIC,
        )) {
            assertEquals(style, StudioState(waveStyle = style).toPreviewModel().waveStyle)
        }
    }

    @Test
    fun `the model carries the effective adjust`() {
        val stored = XmbLayoutAdjust(scale = 1.4f, barLeftFraction = 0.2f, barTopFraction = 0.3f)
        val state = StudioState()
        assertEquals(
            XmbLayoutAdjust(1f, 0f, state.layout.barTopFraction),
            state.toPreviewModel().layoutAdjust,
        )
        assertEquals(stored, state.toPreviewModel(adjust = stored).layoutAdjust)
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the frame renders drilled, mid-transition and with a layout adjust without throwing`() {
        val model = StudioState().toPreviewModel(XmbLayoutAdjust(scale = 1.3f, barLeftFraction = 0.05f, barTopFraction = 0.2f))
        var nav by mutableStateOf(PreviewNavState())
        EventQueue.invokeAndWait {
            ImageComposeScene(width = 1280, height = 720, density = Density(1280f / PreviewGeometry.BASE_WIDTH)).use { scene ->
                scene.setContent { XmbFrame(model, nav) }
                var t = 0L
                fun frames(n: Int) = repeat(n) { scene.render(t); t += 16_666_667L }
                frames(3)
                nav = PreviewNav.reduce(nav, PreviewNavAction.Right) // category switch: AnimatedContent mid-flight
                frames(5)
                nav = PreviewNav.reduce(nav, PreviewNavAction.Down)
                nav = PreviewNav.reduce(nav, PreviewNavAction.Enter) // drill in
                frames(30)
                nav = PreviewNav.reduce(nav, PreviewNavAction.Enter) // leaf message
                frames(3)
                nav = PreviewNav.reduce(nav, PreviewNavAction.Back)
                frames(10)
            }
        }
    }

    @Test
    fun `preview png is still produced for the static Home frame for every wave mode`() {
        listOf(
            PfpThemeManifest.WAVE_ANIMATED, PfpThemeManifest.WAVE_REDUCED,
            PfpThemeManifest.WAVE_STATIC, PfpThemeManifest.WAVE_REDUCED_STATIC,
        ).forEach { style ->
            val png = PreviewRenderer.renderPreviewPng(StudioState(waveStyle = style))
            assertTrue(png.size > 1000, "empty preview for $style")
        }
    }
}
