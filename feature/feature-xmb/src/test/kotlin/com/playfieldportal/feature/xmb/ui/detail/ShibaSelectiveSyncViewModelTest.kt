package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.AwaitingSyncGame
import com.playfieldportal.core.domain.achievement.CoinCounts
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.core.domain.achievement.GameCoins
import com.playfieldportal.core.domain.achievement.GameStanding
import com.playfieldportal.core.domain.achievement.LibraryStanding
import com.playfieldportal.core.domain.achievement.TrackedIdentityStatus
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.match.AchievementAutoMatcher
import com.playfieldportal.feature.xmb.viewmodel.ShibaLibraryMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Selective sync, Tasks 4 and 9 on the XMB: a removed game stays in Tracked Games labelled
 * "Not installed" and offers no refresh; a matched game with no data yet (after a clear) reads
 * "Awaiting sync" rather than a false 0%; and opening a present game's page asks for at most a
 * stale-on-open check, never a sync of its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShibaSelectiveSyncViewModelTest {

    private val achievements = mockk<AchievementController>(relaxed = true)
    private val gameRepository = mockk<GameRepository>(relaxed = true)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun coins(provider: AchievementProvider) = GameCoins(
        provider = provider,
        earned = CoinCounts(bronze = 2),
        total = CoinCounts(bronze = 4),
        isMastered = false,
    )

    // ── Tracked Games ───────────────────────────────────────────────────────────

    private fun libraryVm(standing: LibraryStanding): ShibaLibraryViewModel {
        every { achievements.observeLibraryStanding(any()) } returns MutableStateFlow(standing)
        every { gameRepository.observeGamesOnly() } returns MutableStateFlow(
            listOf(Game(id = 5, title = "Chrono Trigger", platformId = "snes")),
        )
        return ShibaLibraryViewModel(gameRepository, achievements).also { it.load(ShibaLibraryMode.TRACKED) }
    }

    @Test
    fun `a removed game stays tracked and is labelled not installed`() {
        val vm = libraryVm(
            LibraryStanding(
                wallet = CoinWallet(totalCoins = 30),
                tracked = listOf(
                    GameStanding("220", null, "Half-Life 2", null, coins(AchievementProvider.STEAM), isInstalled = false),
                    GameStanding("319", 5L, "Chrono Trigger", null, coins(AchievementProvider.RETRO_ACHIEVEMENTS)),
                ),
            ),
        )

        val rows = vm.uiState.value.rows.associateBy { it.title }
        assertTrue(rows.getValue("Half-Life 2").platformLabel.endsWith("Not installed"))
        assertFalse(rows.getValue("Chrono Trigger").platformLabel.contains("Not installed"))
        assertTrue(rows.getValue("Half-Life 2").isTracked)
    }

    @Test
    fun `a matched game with no data yet reads awaiting sync, not zero percent`() {
        val vm = libraryVm(
            LibraryStanding(
                awaitingSync = listOf(AwaitingSyncGame(5L, AchievementProvider.RETRO_ACHIEVEMENTS, "319", "Chrono Trigger")),
            ),
        )

        val row = vm.uiState.value.rows.single()
        assertEquals("Awaiting sync", row.reason)
        assertTrue(row.awaitingSync)
        assertEquals(ShibaCoinsTarget.LibraryGame(5L), row.coinsTarget)
    }

    // ── Per-game page ───────────────────────────────────────────────────────────

    private fun coinsVm(game: Game): ShibaCoinsViewModel {
        coEvery { gameRepository.getById(game.id) } returns game
        every { achievements.observeGameCoins(game.id) } returns flowOf(null)
        every { achievements.observeCoins(game.id) } returns flowOf(emptyList())
        every { achievements.observeLink(game.id) } returns flowOf(
            ProviderGameLinkEntity(game.id, "RETRO_ACHIEVEMENTS", "319", "MANUAL", 0L),
        )
        return ShibaCoinsViewModel(gameRepository, achievements, mockk<AchievementAutoMatcher>(relaxed = true))
    }

    @Test
    fun `opening a present game asks only for a stale-on-open check`() {
        val vm = coinsVm(Game(id = 5, title = "Chrono Trigger", platformId = "snes"))

        vm.load(ShibaCoinsTarget.LibraryGame(5L))

        coVerify(exactly = 1) { achievements.refreshGameIfStale(5L) }
        coVerify(exactly = 0) { achievements.syncGameById(any()) }
        assertTrue(vm.uiState.value.canSync)
        assertTrue(vm.uiState.value.optionRows.any { it.label == "Refresh this game" })
    }

    @Test
    fun `a removed account entry shows cached coins with no refresh and no network`() {
        every { achievements.observeAccountSet(any(), any()) } returns flowOf(null)
        every { achievements.observeAccountGameCoins(any(), any()) } returns flowOf(null)
        every { achievements.observeAccountCoins(any(), any()) } returns flowOf(emptyList())
        every { achievements.observeIdentityStatus(AchievementProvider.STEAM, "220") } returns flowOf(
            TrackedIdentityStatus(isPresent = false, lastCheckedAt = 7L, lastDetailAt = 7L),
        )
        val vm = ShibaCoinsViewModel(gameRepository, achievements, mockk(relaxed = true))

        vm.load(ShibaCoinsTarget.AccountEntry(AchievementProvider.STEAM, "220"))

        assertFalse(vm.uiState.value.installed)
        assertFalse(vm.uiState.value.canSync)
        assertFalse(vm.uiState.value.optionRows.any { it.option == CoinOption.SyncNow })
        coVerify(exactly = 0) { achievements.refreshAccountEntryIfStale(any(), any()) }
    }
}
