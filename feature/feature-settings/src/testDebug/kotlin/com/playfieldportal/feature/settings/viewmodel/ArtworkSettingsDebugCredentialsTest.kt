package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.feature.artwork.api.ArtworkStatus
import com.playfieldportal.feature.artwork.api.ScrapeOptions
import com.playfieldportal.feature.settings.debug.DebugCredentialsLoader
import com.playfieldportal.feature.settings.debug.DebugCredentialsResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Settings ▸ Artwork's debug credentials row, end to end through the ViewModel. Debug variant only:
 * in release the ViewModel refuses the load and the loader is a stub.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkSettingsDebugCredentialsTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var loader: DebugCredentialsLoader

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        loader = mockk(relaxed = true)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): ArtworkSettingsViewModel {
        val vm = ArtworkSettingsViewModel(
            context = mockk(relaxed = true),
            sgdbKeyProvider = mockk(relaxed = true) { every { apiKeyFlow } returns flowOf(null) },
            metadataKeyProvider = mockk(relaxed = true) {
                every { igdbClientIdFlow } returns flowOf(null)
                every { tgdbKeyFlow } returns flowOf(null)
                every { ssUsernameFlow } returns flowOf(null)
            },
            artworkRepository = mockk(relaxed = true) { coEvery { computeStatus() } returns ArtworkStatus() },
            scrapePreferences = mockk(relaxed = true) {
                every { preferSteamGridDbHeroesFlow } returns flowOf(false)
                coEvery { getOptions() } returns ScrapeOptions()
            },
            igdbApi = mockk(relaxed = true),
            screenScraperApi = mockk(relaxed = true) { every { isEnabledFlow } returns flowOf(false) },
            artworkFolderRepository = mockk(relaxed = true) { coEvery { getTreeUri() } returns null },
            iconDisplayPreferences = mockk(relaxed = true) {
                every { modeFlow } returns flowOf(com.playfieldportal.core.domain.model.IconDisplayMode.DEFAULT)
                every { animatedIconsFlow } returns flowOf(true)
                every { lingerDelaySecondsFlow } returns flowOf(1.5f)
            },
            cropPreviewPreferences = mockk(relaxed = true) { every { enabledFlow } returns flowOf(true) },
            debugCredentialsLoader = loader,
        )
        backgroundScope.launch { vm.uiState.collect { } }
        return vm
    }

    @Test
    fun `loaded text is handed to the loader and its status is shown`() = runTest(testDispatcher) {
        coEvery { loader.load(any()) } returns DebugCredentialsResult("Loaded SteamGridDB", anyUnprotected = false)
        val vm = viewModel()
        advanceUntilIdle()

        vm.loadDebugCredentialsText("steamgriddb.apiKey=k\n")
        advanceUntilIdle()

        coVerify { loader.load("steamgriddb.apiKey=k\n") }
        assertEquals("Loaded SteamGridDB", vm.uiState.value.debugCredentialsStatus)
        assertNull(vm.uiState.value.unprotectedSecretWarning)
    }

    @Test
    fun `a keystore seal failure during a load raises the unprotected warning`() = runTest(testDispatcher) {
        coEvery { loader.load(any()) } returns DebugCredentialsResult("Loaded SteamGridDB", anyUnprotected = true)
        val vm = viewModel()
        advanceUntilIdle()

        vm.loadDebugCredentialsText("steamgriddb.apiKey=k")
        advanceUntilIdle()

        assertFalse(vm.uiState.value.unprotectedSecretWarning.isNullOrBlank())
    }

    @Test
    fun `the status can be dismissed`() = runTest(testDispatcher) {
        coEvery { loader.load(any()) } returns DebugCredentialsResult("Loaded SteamGridDB", anyUnprotected = false)
        val vm = viewModel()
        advanceUntilIdle()
        vm.loadDebugCredentialsText("steamgriddb.apiKey=k")
        advanceUntilIdle()

        vm.dismissDebugCredentialsStatus()
        advanceUntilIdle()

        assertNull(vm.uiState.value.debugCredentialsStatus)
    }
}
