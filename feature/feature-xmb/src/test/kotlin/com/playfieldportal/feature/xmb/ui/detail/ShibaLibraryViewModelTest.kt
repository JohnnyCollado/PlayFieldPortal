package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.CoinCounts
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.GameStanding
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.core.domain.achievement.UntrackedGame
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.xmb.viewmodel.ShibaLibraryMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The achievements library's controller contract (docs/plans/PFP_Achievements_Screen_Design.md
 * §5, §10–§12, §14): Search is navigation position 0, Square always reaches it, Triangle owns a
 * modal Options menu, L/R switch views, and sorting or filtering keeps the cursor on the same game.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShibaLibraryViewModelTest {

    private val standing = MutableStateFlow(
        LibraryStanding(
            wallet = CoinWallet(totalCoins = 0),
            tracked = listOf(
                tracked("ff9", "Final Fantasy IX", AchievementProvider.RETRO_ACHIEVEMENTS, earned = 3, total = 10),
                tracked("hl2", "Half-Life 2", AchievementProvider.STEAM, earned = 10, total = 10, mastered = true),
                tracked("crash", "Crash Bandicoot", AchievementProvider.RETRO_ACHIEVEMENTS, earned = 6, total = 10),
            ),
            untracked = listOf(
                UntrackedGame(gameId = 11L, title = "Sonic Adventure 2", platformId = "windows", reason = "Not found on Steam"),
                UntrackedGame(gameId = 12L, title = "Gravity Rush", platformId = "psvita", reason = "System not supported by RetroAchievements"),
            ),
        ),
    )

    private val games = MutableStateFlow<List<Game>>(emptyList())

    private lateinit var viewModel: ShibaLibraryViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val achievements = mockk<AchievementController> {
            every { observeLibraryStanding(any()) } returns standing
        }
        val gameRepository = mockk<GameRepository> {
            every { observeGamesOnly() } returns games
        }
        viewModel = ShibaLibraryViewModel(gameRepository, achievements)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = viewModel.uiState.value

    private fun press(vararg actions: GamepadAction) = actions.forEach(viewModel::handleGamepadAction)

    /** The listed games, without the pinned Search online row that sits above them in both views. */
    private val gameTitles get() = state.rows.filter { it.action == null }.map { it.title }

    // ── Focus model ─────────────────────────────────────────────────────────────

    @Test
    fun `opening a view focuses its first game, below Search and the pinned Search online row`() {
        viewModel.load(ShibaLibraryMode.TRACKED)

        assertEquals(SEARCH_ONLINE_ROW_ID, state.rows.first().id)
        assertEquals(2, state.focusPosition)
        assertEquals("Crash Bandicoot", state.focused?.title)
        assertFalse(state.searchFocused)
    }

    @Test
    fun `up from the first game passes Search online, reaches Search, and comes back down`() {
        viewModel.load(ShibaLibraryMode.TRACKED)

        press(GamepadAction.NAVIGATE_UP)
        assertEquals(SEARCH_ONLINE_ROW_ID, state.focusedRowId)

        press(GamepadAction.NAVIGATE_UP)
        assertTrue(state.searchFocused)
        assertEquals(0, state.focusPosition)

        press(GamepadAction.NAVIGATE_UP)
        assertTrue("Search is the top edge; up never wraps", state.searchFocused)

        press(GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_DOWN)
        assertEquals("Crash Bandicoot", state.focused?.title)
    }

    @Test
    fun `down stops on the last game`() {
        viewModel.load(ShibaLibraryMode.TRACKED)

        press(GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_DOWN)

        assertEquals(4, state.focusPosition)
        assertEquals("Half-Life 2", state.focused?.title)
    }

    @Test
    fun `Square jumps to Search from any game without starting text entry`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_DOWN)

        press(GamepadAction.CHANGE_SORT)

        assertTrue(state.searchFocused)
        assertFalse(state.searchEditing)
    }

    @Test
    fun `Square on a focused Search starts text entry, as does Confirm`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.CHANGE_SORT, GamepadAction.CHANGE_SORT)
        assertTrue(state.searchEditing)

        viewModel.onSearchEditEnded()
        assertFalse(state.searchEditing)

        press(GamepadAction.SELECT)
        assertTrue(state.searchEditing)
    }

    @Test
    fun `leaving text entry keeps the query`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.CHANGE_SORT, GamepadAction.SELECT)
        viewModel.setQuery("final")

        press(GamepadAction.BACK)

        assertFalse("Back ends text entry before it closes anything", state.searchEditing)
        assertFalse(state.closed)
        assertEquals("final", state.query)
        assertEquals(listOf("Final Fantasy IX"), gameTitles)
    }

    @Test
    fun `clearing the query restores the full sorted list`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        viewModel.setQuery("half")
        viewModel.setQuery("")

        assertEquals(listOf("Crash Bandicoot", "Final Fantasy IX", "Half-Life 2"), gameTitles)
    }

    @Test
    fun `a query that matches nothing leaves only Search online, which is where it is wanted`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        viewModel.setQuery("zelda")

        assertFalse(state.hasGames)
        assertEquals(listOf(SEARCH_ONLINE_ROW_ID), state.rows.map { it.id })
        assertEquals(SEARCH_ONLINE_ROW_ID, state.focusedRowId)
        press(GamepadAction.NAVIGATE_UP)
        assertTrue(state.searchFocused)
    }

    // ── Focus recovery ──────────────────────────────────────────────────────────

    @Test
    fun `re-sorting keeps the cursor on the same game`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.NAVIGATE_DOWN) // Final Fantasy IX

        viewModel.setSort(LibrarySortField.TITLE, ascending = false)

        assertEquals("Final Fantasy IX", state.focused?.title)
        assertEquals(listOf("Half-Life 2", "Final Fantasy IX", "Crash Bandicoot"), gameTitles)
    }

    @Test
    fun `filtering out the focused game recovers to the nearest remaining row`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_DOWN) // Half-Life 2, position 3

        viewModel.setProviderFilter(LibraryProviderFilter.RETRO)

        assertEquals(3, state.focusPosition)
        assertEquals("Final Fantasy IX", state.focused?.title)
    }

    @Test
    fun `a data refresh that drops the focused game recovers instead of losing the cursor`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.NAVIGATE_DOWN) // Final Fantasy IX

        standing.value = standing.value.copy(tracked = standing.value.tracked.filterNot { it.providerGameId == "ff9" })

        assertEquals(3, state.focusPosition)
        assertEquals("Half-Life 2", state.focused?.title)
    }

    // ── View switching ──────────────────────────────────────────────────────────

    @Test
    fun `L and R switch between Tracked and Untracked`() {
        viewModel.load(ShibaLibraryMode.TRACKED)

        press(GamepadAction.NEXT_CATEGORY)
        assertEquals(ShibaLibraryMode.UNTRACKED, state.mode)
        assertEquals("Gravity Rush", state.focused?.title)

        press(GamepadAction.PREV_CATEGORY)
        assertEquals(ShibaLibraryMode.TRACKED, state.mode)
    }

    @Test
    fun `the header summary totals every coin including Platinums`() {
        viewModel.load(ShibaLibraryMode.TRACKED)

        with(state.summary) {
            assertEquals(1, platinum)
            assertEquals(19, bronze)
            assertEquals(20, total)
        }
    }

    // ── Options menu (modal) ────────────────────────────────────────────────────

    @Test
    fun `Triangle opens the Options root, like Icon Display, and pauses list navigation`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.NAVIGATE_DOWN)
        val focusedBefore = state.focusedRowId

        press(GamepadAction.OPEN_CONTEXT_MENU)
        val menu = requireNotNull(state.options)
        assertNull(menu.group)
        assertEquals(0, menu.selectedIndex)
        assertEquals(listOf("Filter (Title A–Z)", "Provider (All)"), state.optionRows.map { it.label })

        press(GamepadAction.NAVIGATE_DOWN)
        assertEquals("the menu owns input, not the list", focusedBefore, state.focusedRowId)
        assertEquals(1, state.options?.selectedIndex)
    }

    @Test
    fun `a root row opens its list with the cursor on the active choice`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        viewModel.setSort(LibrarySortField.PLATFORM, ascending = false)
        press(GamepadAction.OPEN_CONTEXT_MENU)

        press(GamepadAction.SELECT) // Filter

        val menu = requireNotNull(state.options)
        assertEquals(LibraryOptionGroup.FILTER, menu.group)
        assertEquals(LibraryOption.Sort(LibrarySortField.PLATFORM, ascending = false), state.optionRows[menu.selectedIndex].option)
        assertTrue(state.optionRows[menu.selectedIndex].checked)
    }

    @Test
    fun `choosing from a list applies it and closes the menu`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.NAVIGATE_DOWN, GamepadAction.SELECT) // Provider
        assertEquals(LibraryOptionGroup.PROVIDER, state.options?.group)
        val steam = state.optionRows.indexOfFirst { it.option == LibraryOption.Provider(LibraryProviderFilter.STEAM) }

        viewModel.onOptionActivated(steam)

        assertEquals(LibraryProviderFilter.STEAM, state.providerFilter)
        assertNull(state.options)
        assertEquals(listOf("Half-Life 2"), gameTitles)
    }

    @Test
    fun `the Filter list pairs each sort with its direction`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.SELECT)

        assertEquals(
            listOf("Title A–Z", "Title Z–A", "Progress Highest First", "Progress Lowest First", "Platform A–Z", "Platform Z–A"),
            state.optionRows.map { it.label },
        )
    }

    @Test
    fun `closing Options returns focus to the previously focused game`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        press(GamepadAction.NAVIGATE_DOWN) // Final Fantasy IX
        press(GamepadAction.OPEN_CONTEXT_MENU)

        press(GamepadAction.BACK)

        assertNull(state.options)
        assertFalse("Back closes the modal, not the screen", state.closed)
        assertEquals("Final Fantasy IX", state.focused?.title)

        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.OPEN_CONTEXT_MENU)
        assertNull("Triangle toggles the menu closed too", state.options)
    }

    @Test
    fun `Provider is offered only in Tracked Games`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        assertTrue(state.optionRows.any { it.option == LibraryOption.OpenGroup(LibraryOptionGroup.PROVIDER) })

        viewModel.setMode(ShibaLibraryMode.UNTRACKED)
        assertEquals(listOf("Filter (Title A–Z)"), state.optionRows.map { it.label })

        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.SELECT)
        assertFalse(
            "untracked games have no progress to sort by",
            state.optionRows.any { (it.option as? LibraryOption.Sort)?.field == LibrarySortField.PROGRESS },
        )
    }

    // ── Artwork ─────────────────────────────────────────────────────────────────

    @Test
    fun `rows use the game's ICON0 and leave it unassigned otherwise`() {
        games.value = listOf(Game(id = 7L, title = "Crash Bandicoot", platformId = "psx", iconUri = "/icons/crash.png", artworkUri = "/art/crash.png"))
        standing.value = standing.value.copy(
            tracked = standing.value.tracked.map { if (it.providerGameId == "crash") it.copy(libraryGameId = 7L, iconUrl = "https://ra/icon.png") else it },
        )
        viewModel.load(ShibaLibraryMode.TRACKED)

        assertEquals("/icons/crash.png", state.rows.first { it.title == "Crash Bandicoot" }.icon0Uri)
        assertNull("no ICON0 means the default tile, not other art", state.rows.first { it.title == "Half-Life 2" }.icon0Uri)
    }

    // ── Confirm ─────────────────────────────────────────────────────────────────

    @Test
    fun `Confirm on a tracked game opens its achievements`() {
        viewModel.load(ShibaLibraryMode.TRACKED)

        press(GamepadAction.SELECT)

        assertEquals(ShibaCoinsTarget.AccountEntry(AchievementProvider.RETRO_ACHIEVEMENTS, "crash"), state.openCoins)
    }

    @Test
    fun `Confirm on a matchable untracked game opens the existing match flow`() {
        viewModel.load(ShibaLibraryMode.UNTRACKED)
        press(GamepadAction.NAVIGATE_DOWN) // Sonic Adventure 2 (windows → Steam auto-match)

        press(GamepadAction.SELECT)

        assertEquals(ShibaCoinsTarget.LibraryGame(11L), state.openCoins)
    }

    @Test
    fun `Confirm on an untracked game no provider can match does nothing`() {
        viewModel.load(ShibaLibraryMode.UNTRACKED) // Gravity Rush (psvita, no RA console)

        press(GamepadAction.SELECT)

        assertFalse(requireNotNull(state.focused).canAttemptMatch)
        assertNull(state.openCoins)
    }

    @Test
    fun `Confirm on the pinned Search online row opens the provider search, tracking nothing`() {
        viewModel.load(ShibaLibraryMode.TRACKED)

        press(GamepadAction.NAVIGATE_UP) // Search online sits between Search and the first game
        press(GamepadAction.SELECT)

        assertTrue(state.openSearchOnline)
        assertNull("a preview is not a game: nothing opens a coins page", state.openCoins)

        viewModel.onSearchOnlineHandled()
        assertFalse(state.openSearchOnline)
    }

    @Test
    fun `the Search online row is pinned above the games in both views and never sorts or filters`() {
        viewModel.load(ShibaLibraryMode.TRACKED)
        viewModel.setSort(LibrarySortField.TITLE, ascending = false)
        assertEquals(SEARCH_ONLINE_ROW_ID, state.rows.first().id)

        viewModel.setProviderFilter(LibraryProviderFilter.VITA)
        assertEquals(listOf(SEARCH_ONLINE_ROW_ID), state.rows.map { it.id })

        viewModel.setMode(ShibaLibraryMode.UNTRACKED)
        assertEquals(SEARCH_ONLINE_ROW_ID, state.rows.first().id)
    }

    @Test
    fun `touch - tapping another row focuses it, tapping the focused row activates it`() {
        viewModel.load(ShibaLibraryMode.TRACKED)

        viewModel.onRowClick("RETRO_ACHIEVEMENTS:ff9")
        assertEquals("Final Fantasy IX", state.focused?.title)
        assertNull(state.openCoins)

        viewModel.onRowClick("RETRO_ACHIEVEMENTS:ff9")
        assertEquals(ShibaCoinsTarget.AccountEntry(AchievementProvider.RETRO_ACHIEVEMENTS, "ff9"), state.openCoins)
    }

    @Test
    fun `touch - tapping Search focuses it and starts text entry`() {
        viewModel.load(ShibaLibraryMode.TRACKED)

        viewModel.onSearchClick()

        assertTrue(state.searchFocused)
        assertTrue(state.searchEditing)
    }

    private fun tracked(
        id: String,
        title: String,
        provider: AchievementProvider,
        earned: Int,
        total: Int,
        mastered: Boolean = false,
    ) = GameStanding(
        providerGameId = id,
        libraryGameId = null,
        title = title,
        iconUrl = null,
        coins = GameCoins(
            provider = provider,
            earned = CoinCounts(bronze = earned),
            total = CoinCounts(bronze = total),
            isMastered = mastered,
        ),
    )
}
