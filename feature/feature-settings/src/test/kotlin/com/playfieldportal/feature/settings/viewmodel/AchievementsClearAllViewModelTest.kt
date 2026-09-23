package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.domain.achievement.CoinWallet
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.match.AchievementMatchAndUpdate
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.achievements.sync.AchievementUpdateSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Selective sync, Task 9/10: Settings ▸ Achievements. Clear all tracked achievements is never a
 * one-press action — it opens a confirmation first, Cancel (or Back) changes nothing, and only
 * Confirm clears. While updates are paused after a clear the screen says so, and Update installed
 * achievements is the single selective path that resumes them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AchievementsClearAllViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val steamApi = mockk<SteamRemoteDataSource>()
    private val matchAndUpdate = mockk<AchievementMatchAndUpdate>(relaxed = true)
    private val repository = mockk<AchievementController>(relaxed = true)
    private val tasks = mockk<com.playfieldportal.core.ui.notification.BackgroundTaskCenter>(relaxed = true)
    private val paused = MutableStateFlow(false)
    private lateinit var vm: AchievementsSettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { credentials.raUsernameFlow } returns flowOf(null)
        every { credentials.steamId64Flow } returns flowOf(null)
        every { credentials.enabledFlow } returns flowOf(true)
        every { credentials.localSteamTrackingEnabledFlow } returns flowOf(false)
        every { credentials.goldbergInstallerEnabledFlow } returns flowOf(false)
        every { credentials.lastSyncedAtFlow } returns flowOf(null)
        every { repository.observeWallet() } returns flowOf(CoinWallet.EMPTY)
        every { repository.observeAutoUpdatesPaused() } returns paused
        coEvery { repository.clearAllTrackedAchievements() } returns true
        vm = AchievementsSettingsViewModel(credentials, steamApi, matchAndUpdate, repository, tasks)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun kotlinx.coroutines.test.TestScope.collectState() =
        backgroundScope.launch(dispatcher) { vm.uiState.collect {} }

    @Test
    fun `clear asks for confirmation before touching anything`() = runTest(dispatcher) {
        collectState()

        vm.requestClearAll()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.confirmClearVisible)
        coVerify(exactly = 0) { repository.clearAllTrackedAchievements() }
    }

    @Test
    fun `cancel dismisses the confirmation and clears nothing`() = runTest(dispatcher) {
        collectState()
        vm.requestClearAll()

        vm.dismissClearAll()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.confirmClearVisible)
        coVerify(exactly = 0) { repository.clearAllTrackedAchievements() }
    }

    @Test
    fun `confirm clears once and closes the dialog`() = runTest(dispatcher) {
        collectState()
        vm.requestClearAll()

        vm.confirmClearAll()
        vm.confirmClearAll()   // a double press must not clear twice
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.clearAllTrackedAchievements() }
        assertFalse(vm.uiState.value.confirmClearVisible)
        assertFalse(vm.uiState.value.isClearing)
    }

    @Test
    fun `confirm without an open dialog does nothing`() = runTest(dispatcher) {
        vm.confirmClearAll()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.clearAllTrackedAchievements() }
    }

    @Test
    fun `the paused state after a clear is shown`() = runTest(dispatcher) {
        collectState()
        paused.value = true
        advanceUntilIdle()

        assertTrue(vm.uiState.value.updatesPaused)
    }

    @Test
    fun `update installed achievements runs the selective update with progress`() = runTest(dispatcher) {
        collectState()
        val gate = CompletableDeferred<AchievementUpdateSummary>()
        coEvery { repository.updateInstalledAchievements(any()) } coAnswers {
            firstArg<(Int, Int) -> Unit>().invoke(1, 4)
            gate.await()
        }

        vm.updateInstalledAchievements()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.isSyncing)
        assertEquals(1 to 4, vm.uiState.value.syncDone to vm.uiState.value.syncTotal)

        gate.complete(AchievementUpdateSummary(total = 4, checked = 4, unchanged = 4))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isSyncing)
    }

    @Test
    fun `nothing starts while a clear is running`() = runTest(dispatcher) {
        collectState()
        val clearGate = CompletableDeferred<Boolean>()
        coEvery { repository.clearAllTrackedAchievements() } coAnswers { clearGate.await() }
        vm.requestClearAll()
        vm.confirmClearAll()
        advanceUntilIdle()

        vm.updateInstalledAchievements()
        vm.autoMatch()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateInstalledAchievements(any()) }
        coVerify(exactly = 0) { matchAndUpdate.run(any(), any()) }
        clearGate.complete(true)
    }
}
