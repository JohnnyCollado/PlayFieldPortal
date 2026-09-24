package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.network.NetworkMonitor
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.preview.AchievementPreviewRepository
import com.playfieldportal.feature.achievements.preview.PreviewCandidate
import com.playfieldportal.feature.achievements.preview.PreviewSearch
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * Search online (plan Task 8). Two contracts are tested here: the page never writes — its only
 * collaborator is the read-only [AchievementPreviewRepository], and a preview is discarded the
 * moment it closes — and an unreachable provider is never reported as a game that does not exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchOnlineViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val previews = mockk<AchievementPreviewRepository>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>()
    private val network = mockk<NetworkMonitor>()
    private lateinit var viewModel: SearchOnlineViewModel

    private val resonance = PreviewCandidate(AchievementProvider.STEAM, "645730", "Resonance of Fate", "Steam")
    private val other = PreviewCandidate(AchievementProvider.STEAM, "212050", "Resonance", "Steam")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        every { network.isOnline() } returns true
        coEvery { credentials.raUsername() } returns "Chrono"
        coEvery { credentials.raApiKey() } returns "ra-key"
        coEvery { previews.searchSteam(any()) } returns listOf(resonance, other)
        viewModel = SearchOnlineViewModel(previews, credentials, network)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = viewModel.uiState.value

    private fun press(vararg actions: GamepadAction) = actions.forEach(viewModel::handleGamepadAction)

    private fun search(query: String = "resonance") {
        viewModel.setQuery(query)
        scheduler.advanceUntilIdle()
    }

    private fun coin(id: String, title: String, earned: Boolean, hidden: Boolean = false) = SyncedCoin(
        providerAchievementId = id,
        title = title,
        description = "",
        tier = ShibaTier.BRONZE,
        globalRarity = 12.5,
        iconUrl = null,
        isHidden = hidden,
        isEarned = earned,
        earnedHardcore = earned,
        earnedAt = null,
    )

    // ── Search ──────────────────────────────────────────────────────────────────

    @Test
    fun `a query shorter than the minimum asks for nothing`() {
        viewModel.setQuery("r")
        scheduler.advanceUntilIdle()

        assertEquals(SearchStatus.IDLE, state.status)
        assertTrue(state.rows.isEmpty())
        coVerify(exactly = 0) { previews.searchSteam(any()) }
    }

    @Test
    fun `typing searches once per pause, not once per keystroke`() {
        viewModel.setQuery("re")
        viewModel.setQuery("res")
        viewModel.setQuery("reso")
        scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { previews.searchSteam("reso") }
    }

    @Test
    fun `results list themselves with the first one focused`() {
        search()

        assertEquals(SearchStatus.RESULTS, state.status)
        assertEquals(listOf("Resonance of Fate", "Resonance"), state.rows.map { (it as SearchOnlineRow.Result).candidate.title })
        assertEquals("STEAM:645730", state.focusedRowId)
        assertEquals("Steam · 2 results", state.subtitle)
    }

    @Test
    fun `an empty result offline reads as offline, not as a game that does not exist`() {
        coEvery { previews.searchSteam(any()) } returns emptyList()
        every { network.isOnline() } returns false

        search()

        assertEquals(SearchStatus.OFFLINE, state.status)
        assertEquals(SearchRecovery.RETRY, state.recovery)
    }

    @Test
    fun `an empty result online is no results`() {
        coEvery { previews.searchSteam(any()) } returns emptyList()

        search("zzzxqv")

        assertEquals(SearchStatus.NO_RESULTS, state.status)
        assertEquals("No Steam games match \"zzzxqv\".", state.emptyMessage)
        assertNull(state.recovery)
    }

    @Test
    fun `an unreadable RetroAchievements catalog with no credentials asks the user to connect`() {
        coEvery { previews.searchRetroAchievements(any(), any()) } returns PreviewSearch.Unavailable
        coEvery { credentials.raApiKey() } returns null
        viewModel.setProvider(SearchProvider.RETRO_ACHIEVEMENTS)

        search()

        assertEquals(SearchStatus.NOT_CONNECTED, state.status)
        assertEquals(SearchRecovery.OPEN_SETTINGS, state.recovery)

        viewModel.requestCredentials()
        assertTrue(state.openCredentials)
    }

    @Test
    fun `an unreadable catalog with credentials and a connection is a failure, not an empty catalog`() {
        coEvery { previews.searchRetroAchievements(any(), any()) } returns PreviewSearch.Unavailable
        viewModel.setProvider(SearchProvider.RETRO_ACHIEVEMENTS)

        search()

        assertEquals(SearchStatus.FAILED, state.status)
    }

    // ── Preview ─────────────────────────────────────────────────────────────────

    @Test
    fun `Confirm on a result opens its preview, which lists the coins and counts them`() {
        coEvery { previews.open(resonance) } returns ProviderSyncResult.Success(
            providerGameId = resonance.providerGameId,
            coins = listOf(coin("a", "First Contact", earned = false), coin("b", "Tri-Attack", earned = true)),
        )
        search()

        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()

        val preview = requireNotNull(state.preview)
        assertEquals(resonance, preview.candidate)
        assertEquals(2, preview.available)
        assertEquals(CoinViewCounts(all = 2, earned = 1, locked = 1), preview.counts)
        assertEquals(listOf("First Contact", "Tri-Attack"), state.rows.map { (it as SearchOnlineRow.Coin).coin.title })
        assertEquals("Steam · Not installed", state.subtitle)
    }

    @Test
    fun `L and R cycle the preview's views`() {
        coEvery { previews.open(resonance) } returns ProviderSyncResult.Success(
            providerGameId = resonance.providerGameId,
            coins = listOf(coin("a", "First Contact", earned = false), coin("b", "Tri-Attack", earned = true)),
        )
        search()
        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()

        press(GamepadAction.NEXT_CATEGORY)
        assertEquals(CoinFilter.EARNED, state.preview?.filter)
        assertEquals(listOf("Tri-Attack"), state.rows.map { (it as SearchOnlineRow.Coin).coin.title })

        press(GamepadAction.NEXT_CATEGORY)
        assertEquals(CoinFilter.LOCKED, state.preview?.filter)
        assertEquals(listOf("First Contact"), state.rows.map { (it as SearchOnlineRow.Coin).coin.title })
    }

    @Test
    fun `a failed fetch explains itself instead of showing an empty set`() {
        coEvery { previews.open(resonance) } returns ProviderSyncResult.ProfileNotPublic
        search()

        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()

        assertTrue(state.inPreview)
        assertTrue(state.rows.isEmpty())
        assertEquals("Steam Game Details aren't available for your profile.", state.emptyMessage)
    }

    @Test
    fun `Back closes the preview, discards it, and lands back on the results`() {
        coEvery { previews.open(resonance) } returns ProviderSyncResult.Success(resonance.providerGameId, emptyList())
        search()
        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()

        press(GamepadAction.BACK)
        scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { previews.close(resonance) }
        assertFalse(state.inPreview)
        assertFalse("Back leaves the preview before it leaves the page", state.closed)
        assertEquals("STEAM:645730", state.focusedRowId)
    }

    @Test
    fun `leaving the page discards an open preview with it`() {
        coEvery { previews.open(resonance) } returns ProviderSyncResult.Success(resonance.providerGameId, emptyList())
        search()
        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()

        viewModel.close()
        scheduler.advanceUntilIdle()

        assertTrue(state.closed)
        coVerify(exactly = 1) { previews.close(resonance) }
    }

    @Test
    fun `searching, opening and closing a preview touches nothing but the preview repository`() {
        coEvery { previews.open(resonance) } returns ProviderSyncResult.Success(resonance.providerGameId, emptyList())

        search()
        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()
        press(GamepadAction.BACK)
        scheduler.advanceUntilIdle()

        // The repository itself holds no DAO, ledger, writer or coordinator, so these three calls
        // are the whole footprint of a search and a preview: no row anywhere can have changed.
        coVerify(exactly = 1) { previews.searchSteam("resonance") }
        coVerify(exactly = 1) { previews.open(resonance) }
        coVerify(exactly = 1) { previews.close(resonance) }
        confirmVerified(previews)
    }

    // ── Options menu ────────────────────────────────────────────────────────────

    @Test
    fun `the Options root lists the provider, and RetroAchievements adds its system list`() {
        press(GamepadAction.OPEN_CONTEXT_MENU)
        assertEquals(listOf("Provider (Steam)"), state.optionRows.map { it.label })

        viewModel.setProvider(SearchProvider.RETRO_ACHIEVEMENTS)
        assertEquals(
            listOf("Provider (RetroAchievements)", "System (${state.console.label})"),
            state.optionRows.map { it.label },
        )
    }

    @Test
    fun `an open preview offers a refresh, which re-fetches after discarding the held result`() {
        coEvery { previews.open(resonance) } returns ProviderSyncResult.Success(resonance.providerGameId, emptyList())
        search()
        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()

        press(GamepadAction.OPEN_CONTEXT_MENU)
        assertEquals(listOf("Refresh preview"), state.optionRows.map { it.label })
        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { previews.close(resonance) }
        coVerify(exactly = 2) { previews.open(resonance) }
    }
}
