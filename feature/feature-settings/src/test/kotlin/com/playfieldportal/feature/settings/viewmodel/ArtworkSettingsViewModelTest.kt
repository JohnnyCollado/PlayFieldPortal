package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import com.playfieldportal.feature.artwork.api.ArtworkRepository
import com.playfieldportal.feature.artwork.api.ArtworkScrapePreferences
import com.playfieldportal.feature.artwork.api.ArtworkStatus
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScrapeOptions
import com.playfieldportal.feature.artwork.api.MetadataScrapeWorker
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
        // WorkManager.getInstance on a mock context throws → the VM's guarded scrape observer
        // becomes a no-op, which is exactly what these tests want.
        context             = mockk(relaxed = true),
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
    // Scrapes are WorkManager jobs now: the ViewModel enqueues MetadataScrapeWorker and mirrors
    // its WorkInfo into uiState. These tests mock the worker's companion to verify the enqueue
    // contract; progress/summary mirroring needs WorkManager test infra and is device-verified.

    @Test
    fun `scrapeMissingOnly enqueues the missing-mode worker`() = runTest(testDispatcher) {
        io.mockk.mockkObject(MetadataScrapeWorker.Companion)
        every { MetadataScrapeWorker.enqueue(any(), any()) } returns java.util.UUID.randomUUID()
        try {
            viewModel = activeViewModel()
            advanceUntilIdle()

            viewModel.scrapeMissingOnly()
            advanceUntilIdle()

            assertTrue(viewModel.uiState.value.isScraping)
            io.mockk.verify { MetadataScrapeWorker.enqueue(any(), MetadataScrapeWorker.MODE_MISSING) }
        } finally {
            io.mockk.unmockkObject(MetadataScrapeWorker.Companion)
        }
    }

    @Test
    fun `confirmRescrapeAll enqueues the all-mode worker`() = runTest(testDispatcher) {
        io.mockk.mockkObject(MetadataScrapeWorker.Companion)
        every { MetadataScrapeWorker.enqueue(any(), any()) } returns java.util.UUID.randomUUID()
        try {
            viewModel = activeViewModel()
            advanceUntilIdle()

            viewModel.requestRescrapeAll()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.confirmRescrapeAll)

            viewModel.confirmRescrapeAll()
            advanceUntilIdle()

            assertFalse(viewModel.uiState.value.confirmRescrapeAll)
            assertTrue(viewModel.uiState.value.isScraping)
            io.mockk.verify { MetadataScrapeWorker.enqueue(any(), MetadataScrapeWorker.MODE_ALL) }
        } finally {
            io.mockk.unmockkObject(MetadataScrapeWorker.Companion)
        }
    }

    @Test
    fun `cancelScrape delegates to the worker cancel`() = runTest(testDispatcher) {
        io.mockk.mockkObject(MetadataScrapeWorker.Companion)
        every { MetadataScrapeWorker.cancel(any()) } returns mockk(relaxed = true)
        try {
            viewModel = activeViewModel()
            advanceUntilIdle()

            viewModel.cancelScrape()
            io.mockk.verify { MetadataScrapeWorker.cancel(any()) }
        } finally {
            io.mockk.unmockkObject(MetadataScrapeWorker.Companion)
        }
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

    // ── Dismiss helpers ───────────────────────────────────────────────────────

    @Test
    fun `dismissSummary clears summary`() = runTest(testDispatcher) {
        viewModel = activeViewModel()
        advanceUntilIdle()

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
