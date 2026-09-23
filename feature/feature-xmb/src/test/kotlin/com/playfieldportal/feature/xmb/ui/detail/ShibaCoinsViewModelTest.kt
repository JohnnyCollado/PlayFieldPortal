package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.CoinCounts
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.match.AchievementAutoMatcher
import io.mockk.coEvery
import io.mockk.coVerify
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
 * The per-game coins page's controller contract
 * (docs/plans/PFP_Achievements_Game_Page_Implementation_Plan.md §4): Search is navigation position
 * 0, Square always reaches it, Triangle owns a modal Options menu, L/R cycle the three views, and
 * sorting, searching or a data refresh keeps the cursor on the same coin whenever it is still
 * listed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShibaCoinsViewModelTest {

    private val gameId = 1L

    private fun entity(
        id: String,
        tier: ShibaTier,
        rarity: Double,
        earned: Boolean,
        hidden: Boolean = false,
        title: String = id,
    ) = AccountAchievementEntity(
        provider = AchievementProvider.RETRO_ACHIEVEMENTS.name,
        providerGameId = "ra-9",
        providerAchievementId = id,
        title = title,
        description = "",
        tier = tier.name,
        globalRarity = rarity,
        isHidden = hidden,
        isEarned = earned,
        earnedAt = if (earned) 100L else null,
    )

    // Tier order puts bronze first, then silver, then gold: b1, s1, g1.
    private val coins = MutableStateFlow(
        listOf(
            entity("g1", ShibaTier.GOLD, 2.0, earned = false, title = "Gold One"),
            entity("b1", ShibaTier.BRONZE, 60.0, earned = true, title = "Bronze One"),
            entity("s1", ShibaTier.SILVER, 15.0, earned = false, hidden = true, title = "Secret Silver"),
        ),
    )

    private val summary = MutableStateFlow<GameCoins?>(
        GameCoins(
            provider = AchievementProvider.RETRO_ACHIEVEMENTS,
            earned = CoinCounts(bronze = 1),
            total = CoinCounts(bronze = 1, silver = 1, gold = 1),
            isMastered = false,
            lastSyncedAt = 1_000L,
        ),
    )

    private val link = MutableStateFlow<ProviderGameLinkEntity?>(
        ProviderGameLinkEntity(
            gameId = gameId,
            provider = AchievementProvider.RETRO_ACHIEVEMENTS.name,
            providerGameId = "ra-9",
            source = "MANUAL",
            resolvedAt = 0L,
        ),
    )

    private lateinit var achievements: AchievementController
    private lateinit var autoMatcher: AchievementAutoMatcher
    private lateinit var viewModel: ShibaCoinsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        achievements = mockk(relaxed = true) {
            every { observeGameCoins(gameId) } returns summary
            every { observeCoins(gameId) } returns coins
            every { observeLink(gameId) } returns link
            coEvery { syncGameById(gameId) } returns ProviderSyncResult.Success("ra-9", emptyList())
        }
        autoMatcher = mockk(relaxed = true) {
            // Explicit: a relaxed mock would hand back a stand-in that matches neither branch of
            // the sealed result, and the when over it would blow up at runtime.
            coEvery { matchSingleByHash(gameId) } returns AchievementAutoMatcher.RaMatchResult.Matched
        }
        val games = mockk<GameRepository> {
            coEvery { getById(gameId) } returns Game(id = gameId, title = "Final Fantasy IX", platformId = "nds")
        }
        viewModel = ShibaCoinsViewModel(games, achievements, autoMatcher)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = viewModel.uiState.value

    private fun open() = viewModel.load(ShibaCoinsTarget.LibraryGame(gameId))

    private fun press(vararg actions: GamepadAction) = actions.forEach(viewModel::handleGamepadAction)

    // ── Focus model ─────────────────────────────────────────────────────────────

    @Test
    fun `opening the page focuses the pinned Search row`() {
        open()

        assertEquals(0, state.focusPosition)
        assertTrue(state.searchFocused)
        assertNull(state.focusedRowId)
    }

    @Test
    fun `down from Search reaches the Platinum Crown, then the first coin`() {
        open()

        press(GamepadAction.NAVIGATE_DOWN)
        assertEquals(PLATINUM_ROW_ID, state.focusedRowId)

        press(GamepadAction.NAVIGATE_DOWN)
        assertEquals("b1", state.focusedRowId)
    }

    @Test
    fun `up from the first row returns to Search and stops there`() {
        open()
        press(GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_UP)

        assertTrue(state.searchFocused)
    }

    @Test
    fun `down stops at the last row`() {
        open()
        repeat(20) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }

        assertEquals(state.rows.last().id, state.focusedRowId)
    }

    // ── Views ───────────────────────────────────────────────────────────────────

    @Test
    fun `L1 and R1 cycle the three views and wrap`() {
        open()

        press(GamepadAction.NEXT_CATEGORY)
        assertEquals(CoinFilter.EARNED, state.filter)
        press(GamepadAction.NEXT_CATEGORY)
        assertEquals(CoinFilter.LOCKED, state.filter)
        press(GamepadAction.NEXT_CATEGORY)
        assertEquals(CoinFilter.ALL, state.filter)
        press(GamepadAction.PREV_CATEGORY)
        assertEquals(CoinFilter.LOCKED, state.filter)
    }

    @Test
    fun `left and right are quiet aliases for the shoulder buttons`() {
        open()

        press(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(CoinFilter.EARNED, state.filter)
        press(GamepadAction.NAVIGATE_LEFT)
        assertEquals(CoinFilter.ALL, state.filter)
    }

    @Test
    fun `the view counts come from the whole set, not the current view`() {
        open()
        viewModel.setFilter(CoinFilter.EARNED)

        assertEquals(CoinViewCounts(all = 3, earned = 1, locked = 2), state.viewCounts)
    }

    @Test
    fun `changing the view keeps a still-listed coin focused`() {
        open()
        viewModel.setFilter(CoinFilter.ALL)
        press(GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_DOWN)
        assertEquals("b1", state.focusedRowId)

        viewModel.setFilter(CoinFilter.EARNED)

        assertEquals("b1", state.focusedRowId)
    }

    @Test
    fun `changing the view recovers to the nearest row when the focused coin drops out`() {
        open()
        press(GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_DOWN)
        assertEquals("b1", state.focusedRowId)

        // b1 is the only earned coin, so the Locked view drops it.
        viewModel.setFilter(CoinFilter.LOCKED)

        assertFalse(state.focusedRowId == "b1")
        assertTrue(state.rows.any { it.id == state.focusedRowId })
    }

    // ── The Platinum Crown ──────────────────────────────────────────────────────

    @Test
    fun `an unmastered crown shows in All and Locked, never in Earned`() {
        open()

        viewModel.setFilter(CoinFilter.ALL)
        assertEquals(PLATINUM_ROW_ID, state.rows.first().id)
        viewModel.setFilter(CoinFilter.LOCKED)
        assertEquals(PLATINUM_ROW_ID, state.rows.first().id)
        viewModel.setFilter(CoinFilter.EARNED)
        assertFalse(state.rows.any { it.id == PLATINUM_ROW_ID })
    }

    @Test
    fun `a mastered crown shows in All and Earned, never in Locked`() {
        open()
        summary.value = summary.value!!.copy(isMastered = true)

        viewModel.setFilter(CoinFilter.ALL)
        assertEquals(PLATINUM_ROW_ID, state.rows.first().id)
        viewModel.setFilter(CoinFilter.EARNED)
        assertEquals(PLATINUM_ROW_ID, state.rows.first().id)
        viewModel.setFilter(CoinFilter.LOCKED)
        assertFalse(state.rows.any { it.id == PLATINUM_ROW_ID })
    }

    @Test
    fun `Confirm on the Platinum Crown does nothing`() {
        open()
        press(GamepadAction.NAVIGATE_DOWN, GamepadAction.SELECT)

        assertEquals(PLATINUM_ROW_ID, state.focusedRowId)
        assertFalse(state.searchEditing)
        assertTrue(state.revealedIds.isEmpty())
    }

    // ── Search ──────────────────────────────────────────────────────────────────

    @Test
    fun `Square focuses Search, and Square again starts typing`() {
        open()
        press(GamepadAction.NAVIGATE_DOWN)
        assertFalse(state.searchFocused)

        press(GamepadAction.CHANGE_SORT)
        assertTrue(state.searchFocused)
        assertFalse(state.searchEditing)

        press(GamepadAction.CHANGE_SORT)
        assertTrue(state.searchEditing)
    }

    @Test
    fun `Back ends text entry and keeps the query`() {
        open()
        viewModel.startSearchEdit()
        viewModel.setQuery("bronze")

        press(GamepadAction.BACK)

        assertFalse(state.searchEditing)
        assertEquals("bronze", state.query)
        assertFalse(state.closed)
    }

    @Test
    fun `a query filters the list without touching the set`() {
        open()
        viewModel.setQuery("Bronze")

        assertEquals(listOf("b1"), state.displayed.map { it.id })
        assertEquals(3, state.coins.size)
    }

    @Test
    fun `a search that matches nothing empties the list, crown included`() {
        open()
        viewModel.setQuery("zzz")

        assertTrue(state.rows.isEmpty())
        assertEquals("No coins match \"zzz\".", state.emptyMessage)
    }

    @Test
    fun `the crown is findable by name`() {
        open()
        viewModel.setQuery("crown")

        assertEquals(listOf(PLATINUM_ROW_ID), state.rows.map { it.id })
    }

    // ── Hidden coins ────────────────────────────────────────────────────────────

    @Test
    fun `Confirm on a hidden unearned coin toggles its reveal`() {
        open()
        viewModel.onRowClick("s1")
        press(GamepadAction.SELECT)
        assertEquals(setOf("s1"), state.revealedIds)

        press(GamepadAction.SELECT)
        assertTrue(state.revealedIds.isEmpty())
    }

    @Test
    fun `Confirm on an ordinary coin does nothing`() {
        open()
        viewModel.onRowClick("b1")
        press(GamepadAction.SELECT)

        assertTrue(state.revealedIds.isEmpty())
    }

    @Test
    fun `search cannot give away a redacted coin`() {
        open()
        viewModel.setQuery("Secret Silver")
        assertTrue(state.displayed.isEmpty())

        viewModel.toggleReveal(state.coins.first { it.id == "s1" })
        viewModel.setQuery("Secret Silver")
        assertEquals(listOf("s1"), state.displayed.map { it.id })
    }

    // ── Options menu ────────────────────────────────────────────────────────────

    @Test
    fun `Triangle opens Options and Back closes it before the page`() {
        open()

        press(GamepadAction.OPEN_CONTEXT_MENU)
        assertEquals(CoinOptionsMenu(), state.options)

        press(GamepadAction.BACK)
        assertNull(state.options)
        assertFalse(state.closed)

        press(GamepadAction.BACK)
        assertTrue(state.closed)
    }

    @Test
    fun `the Sort list applies its choice and closes the menu`() {
        open()
        press(GamepadAction.OPEN_CONTEXT_MENU)
        viewModel.onOptionActivated(0)
        assertEquals(CoinOptionGroup.SORT, state.options?.group)

        // Tier, Earned, Rarest — pick Rarest.
        viewModel.onOptionActivated(2)

        assertEquals(CoinSort.RAREST, state.sort)
        assertNull(state.options)
        assertEquals(listOf("g1", "s1", "b1"), state.displayed.map { it.id })
    }

    @Test
    fun `Sync Now syncs and closes the menu`() {
        open()
        press(GamepadAction.OPEN_CONTEXT_MENU)
        viewModel.onOptionActivated(1)

        coVerify { achievements.syncGameById(gameId) }
        assertNull(state.options)
    }

    @Test
    fun `Change Match unlinks a Steam game`() {
        link.value = link.value!!.copy(provider = AchievementProvider.STEAM.name)
        open()
        press(GamepadAction.OPEN_CONTEXT_MENU)

        assertEquals(listOf("Sort (Tier)", "Refresh this game", "Change Match"), state.optionRows.map { it.label })
        viewModel.onOptionActivated(2)

        coVerify { achievements.unlink(gameId) }
    }

    @Test
    fun `the Options menu owns input while it is open`() {
        open()
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.NAVIGATE_DOWN)

        assertEquals(1, state.options?.selectedIndex)
        assertTrue(state.searchFocused)
    }

    // ── Unlinked game ───────────────────────────────────────────────────────────

    @Test
    fun `an unlinked game lists only its link panel and matches on Confirm`() {
        link.value = null
        coins.value = emptyList()
        open()

        assertEquals(listOf(LINK_ROW_ID), state.rows.map { it.id })

        press(GamepadAction.NAVIGATE_DOWN, GamepadAction.SELECT)

        coVerify { autoMatcher.matchSingleByHash(gameId) }
    }

    // ── The Auto-Match prompt stays modal ───────────────────────────────────────

    @Test
    fun `the copy prompt captures left, right, Confirm and Back`() {
        link.value = null
        open()
        viewModel.startAutoMatch()

        assertTrue(state.autoMatchYes)
        press(GamepadAction.NAVIGATE_RIGHT)
        assertFalse(state.autoMatchYes)
        // Not a view change: the prompt owns input.
        assertEquals(CoinFilter.ALL, state.filter)

        press(GamepadAction.BACK)
        assertNull(state.autoMatchStep)
        assertFalse(state.closed)
    }

    // ── Data refresh and reload ─────────────────────────────────────────────────

    @Test
    fun `a refresh that removes the focused coin recovers to a listed row`() {
        open()
        viewModel.onRowClick("s1")
        assertEquals("s1", state.focusedRowId)

        coins.value = coins.value.filterNot { it.providerAchievementId == "s1" }

        assertTrue(state.rows.any { it.id == state.focusedRowId })
    }

    @Test
    fun `a refresh keeps the cursor on a coin that is still listed`() {
        open()
        viewModel.onRowClick("b1")

        coins.value = coins.value + entity("g2", ShibaTier.GOLD, 5.0, earned = false)

        assertEquals("b1", state.focusedRowId)
    }

    @Test
    fun `reopening the page resets the query, focus, menu and reveals`() {
        open()
        viewModel.setQuery("bronze")
        viewModel.onRowClick("b1")
        viewModel.toggleReveal(state.coins.first { it.id == "s1" })
        viewModel.openOptions()

        open()

        assertEquals("", state.query)
        assertNull(state.focusedRowId)
        assertNull(state.options)
        assertTrue(state.revealedIds.isEmpty())
        assertFalse(state.closed)
    }

    @Test
    fun `the last sync time reaches the state`() {
        open()

        assertEquals(1_000L, state.lastSyncedAt)
    }
}
