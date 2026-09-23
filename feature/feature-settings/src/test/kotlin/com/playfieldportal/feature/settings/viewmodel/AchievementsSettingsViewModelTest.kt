package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.achievements.match.AchievementMatchAndUpdate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AchievementsSettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val steamApi = mockk<SteamRemoteDataSource>()
    private val matchAndUpdate = mockk<AchievementMatchAndUpdate>(relaxed = true)
    private val repository = mockk<AchievementController>(relaxed = true)
    // Relaxed: these tests are about credential resolution, not about what reaches the tray. The
    // center posts real Android notifications, which a bare JVM cannot build.
    private val tasks =
        mockk<com.playfieldportal.core.ui.notification.BackgroundTaskCenter>(relaxed = true)
    private lateinit var vm: AchievementsSettingsViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        vm = AchievementsSettingsViewModel(credentials, steamApi, matchAndUpdate, repository, tasks)
    }

    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `connectSteam resolves a vanity name to a SteamID64`() = runTest(dispatcher) {
        coEvery { steamApi.resolveVanity("gaben") } returns "76561197960287930"

        vm.connectSteam("gaben", "key")
        advanceUntilIdle()

        coVerify { credentials.saveSteam("76561197960287930", "key") }
    }

    @Test
    fun `connectSteam keeps a 17-digit id as-is without resolving`() = runTest(dispatcher) {
        vm.connectSteam("76561197960287930", "key")
        advanceUntilIdle()

        coVerify(exactly = 0) { steamApi.resolveVanity(any()) }
        coVerify { credentials.saveSteam("76561197960287930", "key") }
    }

    @Test
    fun `autoMatch runs the shared match-then-update sequence`() = runTest(dispatcher) {
        vm.autoMatch()
        advanceUntilIdle()

        coVerify(exactly = 1) { matchAndUpdate.run(any(), any()) }
    }
}
