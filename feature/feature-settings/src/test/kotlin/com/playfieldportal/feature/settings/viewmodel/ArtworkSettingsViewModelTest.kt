package com.playfieldportal.feature.settings.viewmodel

import app.cash.turbine.test
import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.artwork.api.ArtworkScrapePreferences
import com.playfieldportal.feature.artwork.api.ArtworkStatus
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScrapeOptions
import com.playfieldportal.feature.artwork.api.ScrapeProgress
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtworkSettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var sgdbKeyProvider: SgdbApiKeyProvider
    private lateinit var metadataKeyProvider: MetadataApiKeyProvider
    private lateinit var artworkRepository: ArtworkRepository
    private lateinit var scrapePreferences: ArtworkScrapePreferences
    private lateinit var igdbApi: IgdbApi
    private lateinit var viewModel: ArtworkSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        sgdbKeyProvider     = mockk(relaxed = true)
        metadataKeyProvider = mockk(relaxed = true)
        artworkRepository   = mockk(relaxed = true)
        scrapePreferences   = mockk(relaxed = true)
        igdbApi             = mockk(relaxed = true)

        every { sgdbKeyProvider.apiKeyFlow }             returns flowOf(null)
        every { metadataKeyProvider.igdbClientIdFlow }   returns flowOf(null)
        every { scrapePreferences.preferSteamGridDbHeroesFlow } returns flowOf(false)
        coEvery { artworkRepository.computeStatus() }    returns ArtworkStatus(total = 10, complete = 8, missing = 2)
        coEvery { scrapePreferences.getOptions() }       returns ScrapeOptions()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel() = ArtworkSettingsViewModel(
        sgdbKeyProvider     = sgdbKeyProvider,
        metadataKeyProvider = metadataKeyProvider,
        artworkRepository   = artworkRepository,
        scrapePreferences   = scrapePreferences,
        igdbApi             = igdbApi,
    )

    // ── Credential state ──────────────────────────────────────────────────────

    @Test
    fun `hasApiKey is false when sgdb key flow emits null`() = runTest {
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasApiKey)
    }

    @Test
    fun `hasApiKey is true when sgdb key flow emits a key`() = runTest {
        every { sgdbKeyProvider.apiKeyFlow } returns flowOf("abc123")
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasApiKey)
    }

    @Test
    fun `hasIgdbCredentials is true when igdb client id flow is non-blank`() = runTest {
        every { metadataKeyProvider.igdbClientIdFlow } returns flowOf("my-client")
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasIgdbCredentials)
    }

    // ── Scrape modes ──────────────────────────────────────────────────────────

    @Test
    fun `scrapeMissingOnly delegates to artworkRepository and shows summary`() = runTest {
        val finalProgress = ScrapeProgress(5, 5, 4, 1, "")
        coEvery { artworkRepository.scrapeMissingOnly(any()) } returns finalProgress
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.scrapeMissingOnly()
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isScraping)
        assertTrue(state.summary?.contains("4 succeeded") == true)
        assertTrue(state.summary?.contains("1 failed") == true)
    }

    @Test
    fun `confirmRescrapeAll delegates to artworkRepository reScrapeAllGames`() = runTest {
        val finalProgress = ScrapeProgress(3, 3, 3, 0, "")
        coEvery { artworkRepository.reScrapeAllGames(any()) } returns finalProgress
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.requestRescrapeAll()
        assertTrue(viewModel.uiState.value.confirmRescrapeAll)

        viewModel.confirmRescrapeAll()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.confirmRescrapeAll)
        assertFalse(viewModel.uiState.value.isScraping)
        coVerify { artworkRepository.reScrapeAllGames(any()) }
    }

    @Test
    fun `cancelRescrapeAll dismisses confirmation dialog`() = runTest {
        viewModel = buildViewModel()
        viewModel.requestRescrapeAll()
        assertTrue(viewModel.uiState.value.confirmRescrapeAll)
        viewModel.cancelRescrapeAll()
        assertFalse(viewModel.uiState.value.confirmRescrapeAll)
    }

    // ── Scrape preference toggles ─────────────────────────────────────────────

    @Test
    fun `setPreferSteamGridDbHeroes persists to scrapePreferences`() = runTest {
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setPreferSteamGridDbHeroes(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { scrapePreferences.setPreferSteamGridDbHeroes(true) }
    }

    @Test
    fun `setDownloadLogos updates state and persists as clear logos`() = runTest {
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.setDownloadLogos(false)
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.downloadLogos)
        coVerify { scrapePreferences.setDownloadClearLogos(false) }
    }

    // ── IGDB credential test ───────────────────────────────────────────────────

    @Test
    fun `testIgdbCredentials shows Valid on success`() = runTest {
        coEvery { igdbApi.testCredentials("id", "secret") } returns true
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.testIgdbCredentials("id", "secret")
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals("Valid", viewModel.uiState.value.igdbCredentialStatus)
    }

    @Test
    fun `testIgdbCredentials shows failure message on invalid credentials`() = runTest {
        coEvery { igdbApi.testCredentials("bad", "creds") } returns false
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.testIgdbCredentials("bad", "creds")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.igdbCredentialStatus?.contains("Invalid") == true)
    }

    // ── Scrape progress source/asset display ──────────────────────────────────

    @Test
    fun `scrape progress exposes source and asset from ScrapeProgress`() = runTest {
        coEvery { artworkRepository.scrapeMissingOnly(any()) } coAnswers {
            val callback = firstArg<(ScrapeProgress) -> Unit>()
            callback(ScrapeProgress(1, 5, 0, 0, "Doom", scrapeSource = "TheGamesDB", scrapeAsset = "Box Art"))
            ScrapeProgress(5, 5, 4, 1, "")
        }
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.uiState.test {
            viewModel.scrapeMissingOnly()
            var found = false
            repeat(20) {
                val s = awaitItem()
                if (s.scrapeSource == "TheGamesDB" && s.scrapeAsset == "Box Art") {
                    found = true
                    cancelAndIgnoreRemainingEvents()
                    return@test
                }
                testDispatcher.scheduler.advanceTimeBy(50)
            }
            cancelAndIgnoreRemainingEvents()
            assertTrue("Expected scrapeSource/scrapeAsset to propagate", found)
        }
    }

    // ── Dismiss helpers ───────────────────────────────────────────────────────

    @Test
    fun `dismissSummary clears summary`() = runTest {
        val finalProgress = ScrapeProgress(1, 1, 1, 0, "")
        coEvery { artworkRepository.scrapeMissingOnly(any()) } returns finalProgress
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.scrapeMissingOnly()
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.summary.isNullOrBlank())
        viewModel.dismissSummary()
        assertNull(viewModel.uiState.value.summary)
    }

    @Test
    fun `dismissCredentialStatus clears igdb status`() = runTest {
        coEvery { igdbApi.testCredentials(any(), any()) } returns true
        viewModel = buildViewModel()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.testIgdbCredentials("id", "secret")
        testDispatcher.scheduler.advanceUntilIdle()
        assertFalse(viewModel.uiState.value.igdbCredentialStatus.isNullOrBlank())

        viewModel.dismissCredentialStatus()
        assertNull(viewModel.uiState.value.igdbCredentialStatus)
    }
}
