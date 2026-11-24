package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The shape of the Options menu: eight rows in four groups, with the editing tools one level down.
 *
 * Pure state, so it needs no ViewModel. What it pins is which panel an action lives in and which
 * entries are offered at all — the things a user navigates by.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GameDetailOptionsMenuTest {

    private val res get() = RuntimeEnvironment.getApplication().resources


    private val rom = GameDetailUiState(
        isLoading = false,
        game = Game(id = 1L, title = "Crash Bandicoot", platformId = "psx", romPath = "/roms/psx/crash.chd"),
    )

    private val withManual = rom.copy(hasManual = true)

    private fun detailMenuRows(state: GameDetailUiState, emulatorName: String?) = detailMenuRows(state, emulatorName, res)

    private fun gameDetailHelperItems(state: GameDetailUiState) = gameDetailHelperItems(state, res)

    private fun DetailAction.label() = res.getString(labelRes)

    private val windows = GameDetailUiState(
        isLoading = false,
        game = Game(id = 2L, title = "Portal 2", platformId = "windows", packageName = "banner.hub"),
    )

    private fun profile(id: String, name: String, pkg: String) = com.playfieldportal.core.domain.model.EmulatorProfile(
        id = id,
        name = name,
        packageName = pkg,
        intentType = com.playfieldportal.core.domain.model.IntentType.ACTION_VIEW,
        supportedPlatformIds = listOf("psx"),
    )

    private val emulators = listOf(
        profile("duckstation", "DuckStation", "com.github.stenzek.duckstation"),
        profile("retroarch_swanstation", "RetroArch (SwanStation)", "com.retroarch.aarch64"),
    )

    @Test
    fun `Choose Emulator is drawn as Options rows, one per emulator, the current one checked`() {
        val rows = emulatorPickerRows(emulators, selectedId = "retroarch_swanstation")
        assertEquals(listOf("DuckStation", "RetroArch (SwanStation)"), rows.map { it.label })
        assertEquals(listOf(false, true), rows.map { it.checked })
        // A pick commits; nothing here opens a further list or is destructive.
        assertFalse(rows.any { it.opensMenu || it.isDestructive })
    }

    @Test
    fun `an override stored as a package name still checks its emulator`() {
        val rows = emulatorPickerRows(emulators, selectedId = "com.github.stenzek.duckstation")
        assertEquals(listOf(true, false), rows.map { it.checked })
    }

    @Test
    fun `with no override nothing is checked`() {
        assertFalse(emulatorPickerRows(emulators, selectedId = null).any { it.checked })
    }

    @Test
    fun `the top level is eight rows, most-used first and Remove last`() {
        assertEquals(
            listOf(
                DetailAction.FAVORITE,
                DetailAction.COLLECTIONS,
                DetailAction.EMULATOR,
                DetailAction.MANUAL,
                DetailAction.MENU_ARTWORK,
                DetailAction.MENU_INFORMATION,
                DetailAction.MENU_FILE,
                DetailAction.REMOVE,
            ),
            withManual.actionsIn(DetailMenu.ROOT),
        )
    }

    @Test
    fun `Manual is hidden when the game has no manual`() {
        assertFalse(DetailAction.MANUAL in rom.actionsIn(DetailMenu.ROOT))
        assertFalse(detailMenuRows(rom, emulatorName = null).any { it.label == "Manual" })
    }

    @Test
    fun `the menu on screen is the top level until a sub-panel is opened`() {
        assertEquals(rom.actionsIn(DetailMenu.ROOT), rom.visibleActions)
        assertEquals(
            rom.actionsIn(DetailMenu.INFORMATION),
            rom.copy(optionsMenu = DetailMenu.INFORMATION).visibleActions,
        )
    }

    @Test
    fun `a package-backed game has no Emulator row`() {
        assertFalse(DetailAction.EMULATOR in windows.actionsIn(DetailMenu.ROOT))
    }

    @Test
    fun `Artwork holds the studio and the fetch`() {
        assertEquals(
            listOf(DetailAction.ARTWORK, DetailAction.FETCH_ARTWORK),
            rom.actionsIn(DetailMenu.ARTWORK),
        )
    }

    @Test
    fun `Information holds the editing tools, and Store Match only for a Windows game`() {
        assertEquals(
            listOf(DetailAction.METADATA, DetailAction.RENAME, DetailAction.EDIT),
            rom.actionsIn(DetailMenu.INFORMATION),
        )
        assertEquals(
            listOf(DetailAction.METADATA, DetailAction.RENAME, DetailAction.EDIT, DetailAction.STOREFRONT),
            windows.actionsIn(DetailMenu.INFORMATION),
        )
    }

    @Test
    fun `File holds the location, and the export only for a Windows game`() {
        assertEquals(listOf(DetailAction.LOCATION), rom.actionsIn(DetailMenu.FILE))
        assertEquals(listOf(DetailAction.LOCATION, DetailAction.EXPORT), windows.actionsIn(DetailMenu.FILE))
    }

    @Test
    fun `every sub-panel is reachable from exactly one top-level row`() {
        DetailMenu.entries.filter { it != DetailMenu.ROOT }.forEach { menu ->
            assertEquals(menu.name, 1, DetailAction.entries.count { it.opens == menu && it.menu == DetailMenu.ROOT })
        }
    }

    @Test
    fun `the renamed rows read as agreed`() {
        assertEquals("Store Match", DetailAction.STOREFRONT.label())
        assertEquals("Show File Location", DetailAction.LOCATION.label())
        assertEquals("Add to Card", DetailAction.COLLECTIONS.label())
        assertEquals("Remove from Library", DetailAction.REMOVE.label())
        assertEquals("Fetching Artwork…", DetailAction.FETCH_ARTWORK.dynamicLabel(refreshing = true, res = res))
        assertEquals("Artwork Studio", DetailAction.ARTWORK.label())
    }

    // -- Rows as drawn ---------------------------------------------------------

    @Test
    fun `a group header sits on the first row of each group and nowhere else`() {
        val rows = detailMenuRows(withManual, emulatorName = "DuckStation")

        assertEquals(
            listOf("Library", null, "Play", null, "Customize", null, "Manage", null),
            rows.map { it.header },
        )
    }

    @Test
    fun `a sub-panel has no group headers`() {
        val rows = detailMenuRows(rom.copy(optionsMenu = DetailMenu.INFORMATION), emulatorName = null)

        assertTrue(rows.all { it.header == null })
    }

    @Test
    fun `Favorite and Emulator say what they are set to, and a sub-panel row says it opens`() {
        // Rows are the shared panel's own type, so the panel draws them with no adapter.
        val shared: List<com.playfieldportal.core.ui.components.PspMenuRow> =
            detailMenuRows(withManual, emulatorName = "DuckStation")
        val rows = shared.associateBy { it.label }

        assertEquals("Off", rows.getValue("Favorite").value)
        assertEquals("DuckStation", rows.getValue("Emulator").value)
        assertTrue(rows.getValue("Information").opensMenu)
        assertFalse(rows.getValue("Manual").opensMenu)
        assertNull(rows.getValue("Manual").value)
        assertTrue(rows.getValue("Add to Card").opensMenu)
        assertTrue(rows.getValue("Favorite").silent)
        assertTrue(rows.getValue("Remove from Library").isDestructive)

        val favorited = detailMenuRows(rom.copy(game = rom.game?.copy(isFavorite = true)), emulatorName = null)
        assertEquals("On", favorited.first { it.label == "Favorite" }.value)
    }

    @Test
    fun `Store Match says which stores the game is matched on`() {
        fun storeMatchValue(state: GameDetailUiState) =
            detailMenuRows(state.copy(optionsMenu = DetailMenu.INFORMATION), emulatorName = null)
                .first { it.label == "Store Match" }.value

        assertEquals("Steam · GOG", storeMatchValue(windows.copy(storeLinks = listOf("Steam", "GOG"))))
        assertEquals("None", storeMatchValue(windows.copy(storeLinks = emptyList())))
        // Not read yet: say nothing rather than a "None" that may be wrong.
        assertNull(storeMatchValue(windows.copy(storeLinks = null)))
    }

    // -- Footer ----------------------------------------------------------------

    @Test
    fun `Back closes the top level and steps back out of a sub-panel`() {
        val open = rom.copy(showOptions = true)

        assertEquals("Close", gameDetailHelperItems(open).last().label)
        assertEquals("Back", gameDetailHelperItems(open.copy(optionsMenu = DetailMenu.FILE)).last().label)
    }
}
