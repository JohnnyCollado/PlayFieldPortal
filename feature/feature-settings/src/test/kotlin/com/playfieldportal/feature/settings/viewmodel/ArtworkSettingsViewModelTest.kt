package com.playfieldportal.feature.settings.viewmodel

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
import kotlinx.coroutines.CompletableDeferred
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
    private lateinit var screenScraperApi: com.playfieldportal.feature.artwork.api.ScreenScraperApi
    private lateinit var viewModel: ArtworkSettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        sgdbKeyProvider     = mockk(relaxed = true)
        metadataKeyProvider = mockk(relaxed = true)
        artworkRepository   = mockk(relaxed = true)
        scrapePreferences   = mockk(relaxed = true)
        igdbApi             = mockk(relaxed = true)
        screenScraperApi    = mockk(relaxed = true)

        every { sgdbKeyProvider.apiKeyFlow }             returns flowOf(null)
        every { metadataKeyProvider.igdbClientIdFlow }   returns flowOf(null)
        every { metadataKeyProvider.ssUsernameFlow }     returns flowOf(null)
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
        screenScraperApi    = screenScraperApi,
        // No folder configured in tests → the grant-dead banner check is a no-op.
        artworkFolderRepository = mockk(relaxed = true) {
            coEvery { getTreeUri() } returns null
        },
    )

    // uiState is a WhileSubscribed StateFlow, so it only reflects upstream (the credential flows +
    // _extra) while something is collecting it. Build the VM with a background collector active so
    // reads of uiState.value observe real updates, matching how the UI subscribes at runtime.
    private fun TestScope.activeViewModel(): ArtworkSettingsViewModel {
        val vm = buildViewModel()
        backgroundScope.launch { vm.uiState.collect { } }
        return vm
    }

    // ── Credential state ──────────────────────────────────────────────────────

    @Test
    fun `hasApiKey is false when sgdb key flow emits null`() = runTest(testDispatcher) {
        viewModel = activeViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.hasApiKey)
    }

    @Test
    fun `hasApiKey is true when sgdb key flow emits a key`() = runTest(testDispatcher) {
        every { sgdbKeyProvider.apiKeyFlow } returns flowOf("abc123")
        viewModel = activeViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasApiKey)
    }

    @Test
    fun `hasIgdbCredentials is true when igdb client id flow is non-blank`() = runTest(testDispatcher) {
        every { metadataKeyProvider.igdbClientIdFlow } returns flowOf("my-client")
        viewModel = activeViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.hasIgdbCredentials)
    }

    // ── Scrape modes ──────────────────────────────────────────────────────────

    @Test
    fun `scrapeMissingOnly delegates to artworkRepository and shows summary`() = runTest(testDispatcher) {
        val finalProgress = ScrapeProgress(5, 5, 4, 1, "")
        coEvery { artworkRepository.scrapeMissingOnly(any()) } returns finalProgress
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.scrapeMissingOnly()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isScraping)
        assertTrue(state.summary?.contains("4 succeeded") == true)
        assertTrue(state.summary?.contains("1 failed") == true)
    }

    @Test
    fun `confirmRescrapeAll delegates to artworkRepository reScrapeAllGames`() = runTest(testDispatcher) {
        val finalProgress = ScrapeProgress(3, 3, 3, 0, "")
        coEvery { artworkRepository.reScrapeAllGames(any()) } returns finalProgress
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.requestRescrapeAll()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.confirmRescrapeAll)

        viewModel.confirmRescrapeAll()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.confirmRescrapeAll)
        assertFalse(viewModel.uiState.value.isScraping)
        coVerify { artworkRepository.reScrapeAllGames(any()) }
    }

    @Test
    fun `cancelRescrapeAll dismisses confirmation dialog`() = runTest(testDispatcher) {
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.requestRescrapeAll()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.confirmRescrapeAll)

        viewModel.cancelRescrapeAll()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.confirmRescrapeAll)
    }

    // ── Scrape preference toggles ─────────────────────────────────────────────

    @Test
    fun `setPreferSteamGridDbHeroes persists to scrapePreferences`() = runTest(testDispatcher) {
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.setPreferSteamGridDbHeroes(true)
        advanceUntilIdle()

        coVerify { scrapePreferences.setPreferSteamGridDbHeroes(true) }
    }

    @Test
    fun `setDownloadLogos updates state and persists as clear logos`() = runTest(testDispatcher) {
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.setDownloadLogos(false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.downloadLogos)
        coVerify { scrapePreferences.setDownloadClearLogos(false) }
    }

    // ── IGDB credential test ───────────────────────────────────────────────────

    @Test
    fun `testIgdbCredentials shows Valid on success`() = runTest(testDispatcher) {
        coEvery { igdbApi.testCredentials("id", "secret") } returns true
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.testIgdbCredentials("id", "secret")
        advanceUntilIdle()

        assertEquals("Valid", viewModel.uiState.value.igdbCredentialStatus)
    }

    @Test
    fun `testIgdbCredentials shows failure message on invalid credentials`() = runTest(testDispatcher) {
        coEvery { igdbApi.testCredentials("bad", "creds") } returns false
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.testIgdbCredentials("bad", "creds")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.igdbCredentialStatus?.contains("Invalid") == true)
    }

    // ── Scrape progress source/asset display ──────────────────────────────────

    @Test
    fun `scrape progress exposes source and asset from ScrapeProgress`() = runTest(testDispatcher) {
        // Hold the scrape at the in-progress point so the reported source/asset are the current state.
        // (A real scrape suspends on network I/O between a progress callback and completion; the mock
        // otherwise reports and completes in one synchronous step, which the conflated uiState would
        // collapse before any collector could observe the intermediate values.)
        val release = CompletableDeferred<Unit>()
        coEvery { artworkRepository.scrapeMissingOnly(any()) } coAnswers {
            val callback = firstArg<(ScrapeProgress) -> Unit>()
            callback(ScrapeProgress(1, 5, 0, 0, "Doom", scrapeSource = "TheGamesDB", scrapeAsset = "Box Art"))
            release.await()
            ScrapeProgress(5, 5, 4, 1, "")
        }
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.scrapeMissingOnly()
        advanceUntilIdle()

        val inProgress = viewModel.uiState.value
        assertTrue(inProgress.isScraping)
        assertEquals("TheGamesDB", inProgress.scrapeSource)
        assertEquals("Box Art", inProgress.scrapeAsset)

        release.complete(Unit)
        advanceUntilIdle()
    }

    // ── Dismiss helpers ───────────────────────────────────────────────────────

    @Test
    fun `dismissSummary clears summary`() = runTest(testDispatcher) {
        val finalProgress = ScrapeProgress(1, 1, 1, 0, "")
        coEvery { artworkRepository.scrapeMissingOnly(any()) } returns finalProgress
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.scrapeMissingOnly()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.summary.isNullOrBlank())
        viewModel.dismissSummary()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.summary)
    }

    @Test
    fun `dismissCredentialStatus clears igdb status`() = runTest(testDispatcher) {
        coEvery { igdbApi.testCredentials(any(), any()) } returns true
        viewModel = activeViewModel()
        advanceUntilIdle()

        viewModel.testIgdbCredentials("id", "secret")
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.igdbCredentialStatus.isNullOrBlank())

        viewModel.dismissCredentialStatus()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.igdbCredentialStatus)
    }
}
