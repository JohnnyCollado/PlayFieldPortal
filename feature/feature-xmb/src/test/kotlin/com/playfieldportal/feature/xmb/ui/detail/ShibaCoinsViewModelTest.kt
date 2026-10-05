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
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.match.AchievementAutoMatcher
import com.playfieldportal.feature.achievements.provider.steam.SteamCandidate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * The per-game coins page's controller contract:
 * Search is navigation position
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
    private lateinit var games: GameRepository
    private lateinit var autoMatcher: AchievementAutoMatcher
    private lateinit var folderLinker: com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamFolderLinker
    private lateinit var steamGate: com.playfieldportal.feature.achievements.provider.steam.WindowsSteamGate
    private lateinit var menuSound: MenuSoundPlayer
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
        folderLinker = mockk(relaxed = true) {
            // No folder has been pointed at, so the pre-check never short-circuits the flow.
            coEvery { registeredFolderFor(any()) } returns null
        }
        games = mockk {
            coEvery { getById(gameId) } returns Game(id = gameId, title = "Final Fantasy IX", platformId = "nds")
        }
        steamGate = mockk {
            coEvery { isLocalCopy(any()) } returns false
        }
        menuSound = mockk(relaxed = true)
        viewModel = ShibaCoinsViewModel(games, achievements, autoMatcher, folderLinker, steamGate, menuSound)
    }

    // ── A local copy of a Steam game ────────────────────────────────────────────
    //
    // A Windows game whose Steam id is not in the user's Steam library is never linked to Steam —
    // Steam would serve it nothing. Its progress is in its own folder, so its page asks for that
    // folder as soon as it opens, rather than asking whether the copy is a Steam one first.

    private val windowsGame = Game(id = gameId, title = "Digimon Story Time Stranger", platformId = "windows")

    private fun openWindows(local: Boolean, links: List<ProviderGameLinkEntity> = emptyList()) {
        coEvery { games.getById(gameId) } returns windowsGame
        coEvery { steamGate.isLocalCopy(windowsGame) } returns local
        every { achievements.observeLinks(gameId) } returns flowOf(links)
        open()
    }

    @Test
    fun `an unlinked local copy opens on the folder picker`() {
        openWindows(local = true)

        assertEquals(AutoMatchStep.PICK_FOLDER, state.autoMatchStep)
        assertTrue(state.requestFolderPick)
    }

    @Test
    fun `a game whose ownership is unknown waits for the user to say what it is`() {
        openWindows(local = false)

        assertNull(state.autoMatchStep)
        assertFalse(state.requestFolderPick)
    }

    @Test
    fun `a local copy that is already linked is not asked again`() {
        openWindows(
            local = true,
            links = listOf(
                ProviderGameLinkEntity(
                    gameId = gameId,
                    provider = AchievementProvider.LOCAL_STEAM.name,
                    providerGameId = "1984270",
                    source = "MANUAL",
                    resolvedAt = 0L,
                ),
            ),
        )

        assertNull(state.autoMatchStep)
    }

    // ── A result that arrives after the page moved on ───────────────────────────

    @Test
    fun `a manual app id that fails after the page moved on unlinks the game it was entered for`() {
        // The ViewModel is reused from game to game and keeps the open game in a field. A sync is
        // a network call — long enough to back out and open another game — and a failed one
        // unlinks. It has to unlink the game the id was typed for, not whichever is open now.
        val otherGameId = 2L
        coEvery { games.getById(otherGameId) } returns Game(id = otherGameId, title = "Chrono Trigger", platformId = "nds")
        every { achievements.observeGameCoins(otherGameId) } returns MutableStateFlow(null)
        every { achievements.observeCoins(otherGameId) } returns MutableStateFlow(emptyList())
        every { achievements.observeLink(otherGameId) } returns MutableStateFlow(null)
        val sync = kotlinx.coroutines.CompletableDeferred<ProviderSyncResult>()
        coEvery { achievements.syncGameById(gameId) } coAnswers { sync.await() }
        open()

        viewModel.submitManualAppId("480490")
        viewModel.load(ShibaCoinsTarget.LibraryGame(otherGameId))
        sync.complete(ProviderSyncResult.NotFound)

        coVerify(exactly = 1) { achievements.unlink(gameId) }
        coVerify(exactly = 0) { achievements.unlink(otherGameId) }
        // And the other game's page is not told that an app id it never saw "doesn't match".
        assertFalse(state.message.orEmpty().contains("doesn't match"))
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
    fun `Back from the Sort list climbs to the root on its row, and a second Back closes`() {
        open()
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.SELECT)
        assertEquals(CoinOptionGroup.SORT, state.options?.group)

        press(GamepadAction.BACK)
        assertEquals(CoinOptionsMenu(selectedIndex = 0), state.options)
        assertFalse(state.closed)

        press(GamepadAction.BACK)
        assertNull(state.options)
        assertFalse(state.closed)
    }

    @Test
    fun `Triangle closes the menu from inside the Sort list`() {
        open()
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.SELECT, GamepadAction.OPEN_CONTEXT_MENU)

        assertNull(state.options)
    }

    @Test
    fun `the Options cursor clamps at both ends and sounds only when it moves`() {
        open()
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.NAVIGATE_UP)
        assertEquals(0, state.options?.selectedIndex)
        verify(exactly = 0) { menuSound.play(MenuSound.SCROLL, any()) }

        repeat(10) { press(GamepadAction.NAVIGATE_DOWN) }
        assertEquals(state.optionRows.lastIndex, state.options?.selectedIndex)
        verify(exactly = state.optionRows.lastIndex) { menuSound.play(MenuSound.SCROLL, any()) }
    }

    @Test
    fun `opening a list sounds SELECT, a choice CONFIRM, and leaving BACK`() {
        open()
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.SELECT)
        verify(exactly = 1) { menuSound.play(MenuSound.SELECT, any()) }

        press(GamepadAction.SELECT)
        verify(exactly = 1) { menuSound.play(MenuSound.CONFIRM, any()) }

        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.BACK)
        verify(exactly = 1) { menuSound.play(MenuSound.BACK, any()) }
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

    // ── Change Match (Task 4.9): unlink, then pick the new Steam match in Coins' own panel ──────

    private val halo = SteamCandidate("976730", "Halo: The Master Chief Collection")
    private val haloOther = SteamCandidate("1064221", "Halo Infinite")

    private fun openChangeMatch(found: List<SteamCandidate> = listOf(halo, haloOther)) {
        coEvery { achievements.searchSteam(any()) } returns found
        link.value = link.value!!.copy(provider = AchievementProvider.STEAM.name)
        open()
        chooseOption(CoinOption.ChangeMatch)
    }

    @Test
    fun `Change Match unlinks the game and opens the Steam picker seeded with a title search`() {
        openChangeMatch()

        coVerify(exactly = 1) { achievements.unlink(gameId) }
        coVerify { achievements.searchSteam("Final Fantasy IX") }
        assertNull(state.options)
        assertEquals(AutoMatchStep.IDENTIFY, state.autoMatchStep)
        val picker = state.storefrontMatch!!
        assertFalse(picker.loading)
        assertEquals(listOf("976730", "1064221"), picker.rows.map { it.storeId })
    }

    @Test
    fun `Change Match does not ask to confirm - the user re-picks the match`() {
        openChangeMatch()

        assertFalse(state.unlinkConfirm)
    }

    @Test
    fun `choosing a Steam candidate links that app id and syncs`() {
        openChangeMatch()

        viewModel.onStorefrontRowTapped(1)   // focus the second row
        viewModel.onStorefrontRowTapped(1)   // then activate it

        coVerify { achievements.linkManually(gameId, AchievementProvider.STEAM, "1064221") }
        coVerify { achievements.syncGameById(gameId) }
        assertNull(state.storefrontMatch)
        assertNull(state.autoMatchStep)
    }

    @Test
    fun `No correct match closes the picker and links nothing`() {
        openChangeMatch()

        viewModel.chooseStorefrontCandidate(state.storefrontMatch!!.noMatchIndex)

        coVerify(exactly = 0) { achievements.linkManually(any(), any(), any()) }
        assertNull(state.storefrontMatch)
        assertNull(state.autoMatchStep)
    }

    @Test
    fun `Back leaves the Steam picker without linking`() {
        openChangeMatch()

        press(GamepadAction.BACK)

        coVerify(exactly = 0) { achievements.linkManually(any(), any(), any()) }
        assertNull(state.storefrontMatch)
        assertFalse(state.closed)
    }

    @Test
    fun `a title Steam does not list still opens the picker, with only No correct match`() {
        openChangeMatch(found = emptyList())

        val picker = state.storefrontMatch!!
        assertTrue(picker.rows.isEmpty())
        assertEquals(1, picker.stopCount)
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

    // ── Two sets for one game: Steam and Local Steam (owned, played locally) ───

    private fun steamFamilyCoin(provider: AchievementProvider, id: String, earned: Boolean) =
        entity(id, ShibaTier.BRONZE, 30.0, earned).copy(provider = provider.name, providerGameId = "524220")

    private fun steamFamilyLink(provider: AchievementProvider) = ProviderGameLinkEntity(
        gameId = gameId, provider = provider.name, providerGameId = "524220", source = "MANUAL", resolvedAt = 0L,
    )

    private fun steamFamilySummary(provider: AchievementProvider, earned: Int) = GameCoins(
        provider = provider,
        earned = CoinCounts(bronze = earned),
        total = CoinCounts(bronze = 2),
        isMastered = false,
        lastSyncedAt = 1_000L,
    )

    private val bothLinks = MutableStateFlow(
        listOf(steamFamilyLink(AchievementProvider.LOCAL_STEAM), steamFamilyLink(AchievementProvider.STEAM)),
    )
    private val firstOfBoth = MutableStateFlow<ProviderGameLinkEntity?>(steamFamilyLink(AchievementProvider.LOCAL_STEAM))

    /** NieR, linked to both providers. The game-keyed reads report LOCAL_STEAM, as the DAO does. */
    private fun stubBothSets() {
        val local = AchievementProvider.LOCAL_STEAM
        val steam = AchievementProvider.STEAM
        val localCoins = listOf(steamFamilyCoin(local, "ACH_A", true), steamFamilyCoin(local, "ACH_B", false))
        val steamCoins = listOf(steamFamilyCoin(steam, "ACH_A", true), steamFamilyCoin(steam, "ACH_B", true))
        every { achievements.observeLinks(gameId) } returns bothLinks
        every { achievements.observeLink(gameId) } returns firstOfBoth
        every { achievements.observeGameCoins(gameId) } returns MutableStateFlow(steamFamilySummary(local, 1))
        every { achievements.observeCoins(gameId) } returns MutableStateFlow(localCoins)
        every { achievements.observeAccountGameCoins(local, "524220") } returns
            MutableStateFlow(steamFamilySummary(local, 1))
        every { achievements.observeAccountCoins(local, "524220") } returns MutableStateFlow(localCoins)
        every { achievements.observeAccountGameCoins(steam, "524220") } returns
            MutableStateFlow(steamFamilySummary(steam, 2))
        every { achievements.observeAccountCoins(steam, "524220") } returns MutableStateFlow(steamCoins)
    }

    private val earnedCount get() = state.coins.count { it.isEarned }

    @Test
    fun `a game with both sets lists both sources and opens on the one its link reports`() {
        stubBothSets()
        open()

        assertEquals(
            listOf(
                CoinSource(AchievementProvider.LOCAL_STEAM, earned = 1, total = 2),
                CoinSource(AchievementProvider.STEAM, earned = 2, total = 2),
            ),
            state.sources,
        )
        assertTrue(state.hasSourceSwitch)
        assertEquals(AchievementProvider.LOCAL_STEAM, state.provider)
        assertEquals(1, earnedCount)
    }

    @Test
    fun `L and R switch between the two sets, and the D-pad changes the view instead`() {
        stubBothSets()
        open()

        press(GamepadAction.NEXT_CATEGORY)
        assertEquals(AchievementProvider.STEAM, state.provider)
        assertEquals(2, earnedCount)
        assertEquals(CoinFilter.ALL, state.filter)

        press(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(AchievementProvider.STEAM, state.provider)
        assertEquals(CoinFilter.EARNED, state.filter)

        press(GamepadAction.PREV_CATEGORY)
        assertEquals(AchievementProvider.LOCAL_STEAM, state.provider)
        assertEquals(1, earnedCount)
    }

    @Test
    fun `opening a game on a named set starts there`() {
        stubBothSets()
        viewModel.load(ShibaCoinsTarget.LibraryGame(gameId, AchievementProvider.STEAM))

        assertEquals(AchievementProvider.STEAM, state.provider)
        assertEquals(2, earnedCount)
    }

    @Test
    fun `a game with one set keeps L and R for the view`() {
        open()

        assertFalse(state.hasSourceSwitch)
        press(GamepadAction.NEXT_CATEGORY)
        assertEquals(CoinFilter.EARNED, state.filter)
    }

    // ── Unlink Game ────────────────────────────────────────────────────────────

    private fun chooseOption(option: CoinOption) {
        viewModel.openOptions()
        val index = state.optionRows.indexOfFirst { it.option == option }
        assertTrue("$option is not in the menu: ${state.optionRows.map { it.label }}", index >= 0)
        viewModel.onOptionActivated(index)
    }

    @Test
    fun `Unlink Game removes the link and the page falls back to the link panel`() {
        open()

        chooseOption(CoinOption.Unlink)
        viewModel.confirmUnlink()
        coVerify { achievements.unlink(gameId) }
        link.value = null   // the stored link is gone

        assertNull(state.options)
        assertFalse(state.linked)
        assertTrue(state.showLinkPanel)
    }

    @Test
    fun `choosing Unlink Game asks first and does not unlink`() {
        open()

        chooseOption(CoinOption.Unlink)

        assertTrue(state.unlinkConfirm)
        assertNull(state.options)
        coVerify(exactly = 0) { achievements.unlink(any()) }
    }

    @Test
    fun `cancelling the Unlink confirm leaves the link alone`() {
        open()
        chooseOption(CoinOption.Unlink)

        viewModel.cancelUnlink()

        assertFalse(state.unlinkConfirm)
        assertTrue(state.linked)
        coVerify(exactly = 0) { achievements.unlink(any()) }
    }

    @Test
    fun `Confirm on the Unlink confirm unlinks once and closes it`() {
        open()
        chooseOption(CoinOption.Unlink)

        viewModel.confirmUnlink()

        assertFalse(state.unlinkConfirm)
        coVerify(exactly = 1) { achievements.unlink(gameId) }
    }

    @Test
    fun `the page ignores presses while the Unlink confirm is open`() {
        open()
        chooseOption(CoinOption.Unlink)

        press(GamepadAction.BACK)

        assertFalse(state.closed)
    }

    @Test
    fun `unlinking a game with two sets drops both and returns to its platform's match flow`() {
        stubBothSets()
        open()
        press(GamepadAction.NEXT_CATEGORY)   // viewing the Steam set

        chooseOption(CoinOption.Unlink)
        viewModel.confirmUnlink()
        bothLinks.value = emptyList()
        firstOfBoth.value = null

        assertFalse(state.hasSourceSwitch)
        assertTrue(state.showLinkPanel)
        // The page's own default for the platform ("nds" here), not the Local Steam it last showed:
        // an unlinked page offers the match flow its platform has.
        assertEquals(AchievementProvider.RETRO_ACHIEVEMENTS, state.provider)
    }
}
