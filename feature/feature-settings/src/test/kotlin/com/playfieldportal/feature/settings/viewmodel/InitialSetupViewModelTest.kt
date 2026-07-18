package com.playfieldportal.feature.settings.viewmodel

import android.net.Uri
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InitialSetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val romRoots = mockk<RomRootRepository>(relaxed = true)
    private val mediaRoots = mockk<MediaRootRepository>(relaxed = true)
    private val artworkImport = mockk<ArtworkImportManager>(relaxed = true)
    private val sgdbKeys = mockk<SgdbApiKeyProvider>(relaxed = true)
    private val metadataKeys = mockk<MetadataApiKeyProvider>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val steamApi = mockk<SteamRemoteDataSource>()
    private val igdbApi = mockk<IgdbApi>()
    private val screenScraperApi = mockk<ScreenScraperApi>()
    private lateinit var vm: InitialSetupViewModel

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { romRoots.roots } returns flowOf(emptyList())
        every { mediaRoots.observe(any()) } returns flowOf(null)
        every { artworkImport.folderTreeUri } returns flowOf(null)
        every { sgdbKeys.apiKeyFlow } returns flowOf(null)
        every { metadataKeys.igdbClientIdFlow } returns flowOf(null)
        every { metadataKeys.ssUsernameFlow } returns flowOf(null)
        every { credentials.raUsernameFlow } returns flowOf(null)
        every { credentials.steamId64Flow } returns flowOf(null)
        every { screenScraperApi.isEnabled } returns true
        vm = InitialSetupViewModel(
            romRoots, mediaRoots, artworkImport, sgdbKeys, metadataKeys,
            credentials, steamApi, igdbApi, screenScraperApi,
        )
    }

    @After fun tearDown() = Dispatchers.resetMain()

    // uiState is WhileSubscribed — tests that assert on it need an active collector.
    private fun TestScope.collectState() = launch { vm.uiState.collect {} }

    @Test fun `steps advance and retreat in order, back from welcome exits`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()
        assertEquals(SetupStep.WELCOME, vm.uiState.value.step)

        vm.nextStep()
        advanceUntilIdle()
        assertEquals(SetupStep.FOLDERS, vm.uiState.value.step)

        assertTrue(vm.previousStep())
        advanceUntilIdle()
        assertEquals(SetupStep.WELCOME, vm.uiState.value.step)

        assertFalse("back on the first page means exit", vm.previousStep())
        job.cancel()
    }

    @Test fun `resetWizard returns to the welcome page for the next run`() = runTest(dispatcher) {
        val job = collectState()
        vm.nextStep()
        vm.nextStep()
        advanceUntilIdle()
        assertEquals(SetupStep.SERVICES, vm.uiState.value.step)

        vm.resetWizard()
        advanceUntilIdle()
        assertEquals(SetupStep.WELCOME, vm.uiState.value.step)
        job.cancel()
    }

    @Test fun `rom root pick persists the grant then stores the root`() = runTest(dispatcher) {
        val uri = mockk<Uri> { every { this@mockk.toString() } returns "content://tree/primary%3ARoms" }

        vm.onRomRootPicked(uri)
        advanceUntilIdle()

        // Read+write, matching Library Manager's add-root path (ES-DE folder setup needs it).
        coVerify { romRoots.persist(uri, writable = true) }
        coVerify { romRoots.add("content://tree/primary%3ARoms") }
    }

    @Test fun `media root pick persists and sets the chosen kind`() = runTest(dispatcher) {
        val uri = mockk<Uri> { every { this@mockk.toString() } returns "content://tree/primary%3AMusic" }

        vm.onMediaRootPicked(MediaRootKind.MUSIC, uri)
        advanceUntilIdle()

        coVerify { mediaRoots.persist(uri) }
        coVerify { mediaRoots.set(MediaRootKind.MUSIC, "content://tree/primary%3AMusic") }
    }

    @Test fun `artwork folder link failure surfaces a message`() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        coEvery { artworkImport.linkFolder(uri) } returns null
        val job = collectState()

        vm.onArtworkFolderPicked(uri)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.message)
        job.cancel()
    }

    @Test fun `connectSteam keeps a 17-digit id without resolving and enables tracking`() =
        runTest(dispatcher) {
            vm.connectSteam("76561197960287930", "key")
            advanceUntilIdle()

            coVerify(exactly = 0) { steamApi.resolveVanity(any()) }
            coVerify { credentials.saveSteam("76561197960287930", "key") }
            coVerify { credentials.setEnabled(true) }
        }

    @Test fun `connectSteam resolves a vanity name to a SteamID64`() = runTest(dispatcher) {
        coEvery { steamApi.resolveVanity("gaben") } returns "76561197960287930"

        vm.connectSteam("gaben", "key")
        advanceUntilIdle()

        coVerify { credentials.saveSteam("76561197960287930", "key") }
    }

    @Test fun `connectRetroAchievements saves and enables tracking`() = runTest(dispatcher) {
        vm.connectRetroAchievements("player", "api-key")
        advanceUntilIdle()

        coVerify { credentials.saveRetroAchievements("player", "api-key") }
        coVerify { credentials.setEnabled(true) }
    }

    @Test fun `testIgdbCredentials reports valid and invalid`() = runTest(dispatcher) {
        coEvery { igdbApi.testCredentials("id", "secret") } returns true
        val job = collectState()

        vm.testIgdbCredentials("id", "secret")
        advanceUntilIdle()
        assertEquals("Valid", vm.uiState.value.igdbStatus)

        coEvery { igdbApi.testCredentials("id", "wrong") } returns false
        vm.testIgdbCredentials("id", "wrong")
        advanceUntilIdle()
        assertEquals("Invalid — check Client ID and Secret", vm.uiState.value.igdbStatus)
        job.cancel()
    }

    @Test fun `testSsCredentials reports the account limits when valid`() = runTest(dispatcher) {
        coEvery { screenScraperApi.fetchUserInfo("user", "pass") } returns
            com.playfieldportal.feature.artwork.api.SsUser(maxThreads = "2", maxRequestsPerDay = "20000")
        val job = collectState()

        vm.testSsCredentials("user", "pass")
        advanceUntilIdle()

        assertEquals("Valid — 2 threads, 20000 requests/day", vm.uiState.value.ssStatus)
        job.cancel()
    }

    @Test fun `navigating steps clears stale test statuses`() = runTest(dispatcher) {
        coEvery { igdbApi.testCredentials("id", "secret") } returns true
        val job = collectState()

        vm.nextStep()
        vm.nextStep()
        vm.testIgdbCredentials("id", "secret")
        advanceUntilIdle()
        assertEquals("Valid", vm.uiState.value.igdbStatus)

        vm.nextStep()
        advanceUntilIdle()
        assertNull("leaving the page must drop the orphaned status", vm.uiState.value.igdbStatus)
        job.cancel()
    }

    @Test fun `connectIgdb saves the Twitch credential pair`() = runTest(dispatcher) {
        vm.connectIgdb("client-id", "client-secret")
        advanceUntilIdle()

        coVerify { metadataKeys.saveIgdbCredentials("client-id", "client-secret") }
    }

    @Test fun `blank credentials are ignored`() = runTest(dispatcher) {
        vm.connectSgdb("  ")
        vm.connectIgdb("client-id", "")
        vm.connectScreenScraper("user", "")
        vm.connectRetroAchievements("", "key")
        vm.connectSteam("id", " ")
        advanceUntilIdle()

        coVerify(exactly = 0) { sgdbKeys.saveKey(any()) }
        coVerify(exactly = 0) { metadataKeys.saveIgdbCredentials(any(), any()) }
        coVerify(exactly = 0) { metadataKeys.saveSsCredentials(any(), any()) }
        coVerify(exactly = 0) { credentials.saveRetroAchievements(any(), any()) }
        coVerify(exactly = 0) { credentials.saveSteam(any(), any()) }
    }
}
