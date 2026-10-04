package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.data.repository.MediaRootRepository
import com.playfieldportal.core.data.repository.Ps3DataLibrary
import com.playfieldportal.core.data.repository.Xbox360DataLibrary
import com.playfieldportal.core.data.repository.Xbox360Emulator
import com.playfieldportal.core.data.repository.CoreInventory
import com.playfieldportal.core.data.repository.RetroArchLink
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.data.repository.Vita3KLibrary
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import com.playfieldportal.feature.artwork.api.ArtworkImportManager
import com.playfieldportal.feature.artwork.api.IgdbApi
import com.playfieldportal.feature.artwork.api.ScreenScraperApi
import com.playfieldportal.feature.artwork.api.SgdbApiKeyProvider
import com.playfieldportal.feature.artwork.importer.DetectedImportSource
import com.playfieldportal.feature.artwork.importer.ImportPlan
import com.playfieldportal.feature.artwork.portable.PortableArtworkLibrary
import com.playfieldportal.feature.launcher.kb.EmulatorKnowledgeRefresher
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
    private val context = mockk<Context>(relaxed = true)
    private val packageManager = mockk<PackageManager>(relaxed = true)
    private val romRoots = mockk<RomRootRepository>(relaxed = true)
    private val mediaRoots = mockk<MediaRootRepository>(relaxed = true)
    private val artworkImport = mockk<ArtworkImportManager>(relaxed = true)
    private val retroArchLink = mockk<RetroArchLink>(relaxed = true)
    private val vita3KLibrary = mockk<Vita3KLibrary>(relaxed = true)
    private val refresher = mockk<EmulatorKnowledgeRefresher>(relaxed = true)
    private val sgdbKeys = mockk<SgdbApiKeyProvider>(relaxed = true)
    private val metadataKeys = mockk<MetadataApiKeyProvider>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val steamApi = mockk<SteamRemoteDataSource>()
    private val igdbApi = mockk<IgdbApi>()
    private val screenScraperApi = mockk<ScreenScraperApi>()
    private val scanRunner = mockk<com.playfieldportal.feature.settings.media.WizardMediaScanRunner>(relaxed = true)
    private val romRootScanRunner = mockk<RomRootScanRunner>(relaxed = true)
    private val tasks = mockk<com.playfieldportal.core.ui.notification.BackgroundTaskCenter>(relaxed = true)
    private val ps3DataLibrary = mockk<Ps3DataLibrary>(relaxed = true)
    private val environment = mockk<SetupEnvironment>(relaxed = true)
    private val xbox360DataLibrary = mockk<Xbox360DataLibrary>(relaxed = true)
    private lateinit var vm: InitialSetupViewModel

    private fun buildVm() = InitialSetupViewModel(
        context, romRoots, mediaRoots, artworkImport, retroArchLink, vita3KLibrary, refresher,
        sgdbKeys, metadataKeys, credentials, steamApi, igdbApi, screenScraperApi,
        scanRunner, romRootScanRunner,
        mockk(relaxed = true), // romScanner (B3 create-standard-folders)
        mockk(relaxed = true), // folderHintResolver
        mockk(relaxed = true), // memoryCardRepository
        tasks,
        ps3DataLibrary,
        xbox360DataLibrary,
        environment,
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { context.packageManager } returns packageManager
        every { romRoots.roots } returns flowOf(emptyList())
        every { mediaRoots.roots(any()) } returns flowOf(emptyList())
        every { artworkImport.folderTreeUri } returns flowOf(null)
        every { vita3KLibrary.ux0TreeUriFlow } returns flowOf(null)
        every { ps3DataLibrary.dataTreeUriFlow } returns flowOf(null)
        every { xbox360DataLibrary.treeUriFlow(any()) } returns flowOf(null)
        // Default: nothing optional installed, PFP not yet Home (tests override per case).
        every { environment.availability() } returns SetupAvailability()
        every { sgdbKeys.apiKeyFlow } returns flowOf(null)
        every { metadataKeys.tgdbKeyFlow } returns flowOf(null)
        every { metadataKeys.igdbClientIdFlow } returns flowOf(null)
        every { metadataKeys.ssUsernameFlow } returns flowOf(null)
        every { credentials.raUsernameFlow } returns flowOf(null)
        every { credentials.steamId64Flow } returns flowOf(null)
        coEvery { screenScraperApi.isEnabled() } returns true
        coEvery { retroArchLink.inventory() } returns CoreInventory.Unlinked
        vm = buildVm()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    // uiState is WhileSubscribed — tests that assert on it need an active collector.
    private fun TestScope.collectState() = launch { vm.uiState.collect {} }

    // ── Step navigation ─────────────────────────────────────────────────────────

    @Test fun `steps advance through every page, skipping conditional pages when nothing is installed`() =
        runTest(dispatcher) {
            val job = collectState()
            advanceUntilIdle()
            val expected = listOf(
                SetupStep.WELCOME, SetupStep.CONTROLLER, SetupStep.ROM_ROOTS, SetupStep.MUSIC,
                SetupStep.VIDEO, SetupStep.PHOTO, SetupStep.ARTWORK, SetupStep.SERVICES,
                SetupStep.ACHIEVEMENTS, SetupStep.HINTS, SetupStep.HOME_APP, SetupStep.FINISH,
            )
            expected.forEachIndexed { index, step ->
                assertEquals("landing on step $index", step, vm.uiState.value.step)
                assertEquals(index + 1, vm.uiState.value.stepNumber)
                if (index < expected.lastIndex) {
                    vm.nextStep()
                    advanceUntilIdle()
                }
            }
            assertEquals(SetupStep.FINISH, vm.uiState.value.step)
            job.cancel()
        }

    @Test fun `steps retreat in order and back from welcome exits`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()
        assertEquals(SetupStep.WELCOME, vm.uiState.value.step)

        vm.nextStep()
        advanceUntilIdle()
        assertEquals(SetupStep.CONTROLLER, vm.uiState.value.step)

        assertTrue(vm.previousStep())
        advanceUntilIdle()
        assertEquals(SetupStep.WELCOME, vm.uiState.value.step)

        assertFalse("back on the first page means exit", vm.previousStep())
        job.cancel()
    }

    @Test fun `every optional page is included when its app is installed`() =
        runTest(dispatcher) {
            every { environment.availability() } returns SetupAvailability(
                retroArch = true, vita3K = true, armsx3 = true, knownEmulator = true, pcLauncher = true,
            )
            vm = buildVm()

            val job = collectState()
            advanceUntilIdle()
            assertTrue(vm.uiState.value.retroArchInstalled)
            assertTrue(vm.uiState.value.vita3KInstalled)
            assertTrue(vm.uiState.value.armsx3Installed)

            listOf(
                SetupStep.CONTROLLER, SetupStep.ROM_ROOTS, SetupStep.MUSIC, SetupStep.VIDEO,
                SetupStep.PHOTO, SetupStep.ARTWORK, SetupStep.SERVICES, SetupStep.ACHIEVEMENTS,
                SetupStep.LOCAL_ACHIEVEMENTS, SetupStep.RETROARCH, SetupStep.EMULATORS, SetupStep.WINDOWS,
                SetupStep.HINTS, SetupStep.HOME_APP, SetupStep.FINISH,
            ).forEach { step ->
                vm.nextStep()
                advanceUntilIdle()
                assertEquals(step, vm.uiState.value.step)
            }
            assertEquals(16, vm.uiState.value.stepNumber)
            job.cancel()
        }

    @Test fun `Home App stays in the flow once the role is granted on its own page`() =
        runTest(dispatcher) {
            // Visibility is decided when the wizard opens: becoming Home ON the Home App page must
            // not pull the page out from under the cursor.
            val job = collectState()
            advanceUntilIdle()
            repeat(10) { vm.nextStep() }
            advanceUntilIdle()
            assertEquals(SetupStep.HOME_APP, vm.uiState.value.step)

            every { environment.availability() } returns SetupAvailability(alreadyHome = true)
            vm.nextStep()
            advanceUntilIdle()
            assertEquals(SetupStep.FINISH, vm.uiState.value.step)
            assertTrue(vm.previousStep())
            advanceUntilIdle()
            assertEquals(SetupStep.HOME_APP, vm.uiState.value.step)
            job.cancel()
        }

    @Test fun `skip advances one page without writing anything`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()
        vm.skipStep()
        advanceUntilIdle()
        assertEquals(SetupStep.CONTROLLER, vm.uiState.value.step)
        job.cancel()
    }

    @Test fun `skip does nothing on Finish`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()
        repeat(20) { vm.nextStep() }
        advanceUntilIdle()
        assertEquals(SetupStep.FINISH, vm.uiState.value.step)

        vm.skipStep()
        advanceUntilIdle()
        assertEquals(SetupStep.FINISH, vm.uiState.value.step)
        job.cancel()
    }

    @Test fun `resetWizard returns to the welcome page for the next run`() = runTest(dispatcher) {
        val job = collectState()
        vm.nextStep()
        vm.nextStep()
        advanceUntilIdle()
        assertEquals(SetupStep.ROM_ROOTS, vm.uiState.value.step)

        vm.resetWizard()
        advanceUntilIdle()
        assertEquals(SetupStep.WELCOME, vm.uiState.value.step)
        job.cancel()
    }

    // ── Multi-root folders ──────────────────────────────────────────────────────

    @Test fun `rom root pick persists the grant and kicks off the scan`() = runTest(dispatcher) {
        val uri = mockk<Uri> { every { this@mockk.toString() } returns "content://tree/primary%3ARoms" }

        vm.addRomRoot(uri)
        advanceUntilIdle()

        coVerify { romRoots.persist(uri, writable = true) }
        coVerify { romRoots.add("content://tree/primary%3ARoms") }
        io.mockk.verify { romRootScanRunner.kickoff() }
    }

    @Test fun `addMediaRoot persists, adds, and starts the per-kind scan`() = runTest(dispatcher) {
        val uri = mockk<Uri> { every { this@mockk.toString() } returns "content://tree/primary%3AMusic" }

        vm.addMediaRoot(MediaRootKind.MUSIC, uri)
        advanceUntilIdle()

        coVerify { mediaRoots.persist(uri) }
        coVerify { mediaRoots.add(MediaRootKind.MUSIC, "content://tree/primary%3AMusic") }
        io.mockk.verify { scanRunner.kickoff(MediaRootKind.MUSIC) }
    }

    @Test fun `removeMediaRoot removes and rescans to reconcile library rows`() =
        runTest(dispatcher) {
            vm.removeMediaRoot(MediaRootKind.PHOTO, "content://tree/primary%3APhotos")
            advanceUntilIdle()

            coVerify { mediaRoots.remove(MediaRootKind.PHOTO, "content://tree/primary%3APhotos") }
            io.mockk.verify { scanRunner.kickoff(MediaRootKind.PHOTO) }
        }

    @Test fun `relinkMediaRoot replaces the root uri and rescans`() = runTest(dispatcher) {
        val old = "content://tree/primary%3AMusic"
        val newUri = mockk<Uri> { every { this@mockk.toString() } returns "content://tree/1A2B-3C4D%3AMusic" }

        vm.relinkMediaRoot(MediaRootKind.MUSIC, old, newUri)
        advanceUntilIdle()

        coVerify { mediaRoots.persist(newUri) }
        coVerify { mediaRoots.replace(MediaRootKind.MUSIC, old, "content://tree/1A2B-3C4D%3AMusic") }
        io.mockk.verify { scanRunner.kickoff(MediaRootKind.MUSIC) }
    }

    // ── Artwork ─────────────────────────────────────────────────────────────────

    @Test fun `artwork folder link success surfaces sources for the import offer`() =
        runTest(dispatcher) {
            val uri = mockk<Uri>()
            val source = mockk<DetectedImportSource>()
            every { source.label } returns "16-bit Collection"
            every { source.systems } returns emptyList()
            coEvery { artworkImport.folderTreeUri } returns flowOf("content://tree/primary%3AArtwork")
            coEvery { artworkImport.linkFolder(uri) } returns ArtworkImportManager.LinkResult(
                manifest = mockk(), existingLibrary = false,
            )
            coEvery { artworkImport.detectSources() } returns listOf(source)
            // Rebuild a fresh VM so its rootLists combine subscribes to the re-stubbed folder flow
            // (the setUp VM is already collecting the old null folderTreeUri).
            vm = buildVm()
            val job = collectState()
            advanceUntilIdle()

            vm.onArtworkFolderPicked(uri)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.artworkFolderName)
            assertEquals(listOf(ArtworkSourceUi("16-bit Collection", 0)), vm.uiState.value.artworkSources)
            // The outcome goes to the tray now, not an in-screen message row.
            io.mockk.verify { tasks.report(id = "setup_artwork_link", label = any(), message = any(), severity = any(), kind = any(), action = any()) }
            job.cancel()
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

    @Test fun `importArtworkNow copies the first detected source`() = runTest(dispatcher) {
        val uri = mockk<Uri>()
        val source = mockk<DetectedImportSource>()
        val plan = mockk<ImportPlan>()
        every { source.label } returns "Xbox Library"
        every { source.systems } returns emptyList()
        every { plan.itemCount } returns 3
        every { plan.sourceLabel } returns "Xbox Library"
        coEvery { artworkImport.linkFolder(uri) } returns ArtworkImportManager.LinkResult(
            manifest = mockk(), existingLibrary = false,
        )
        coEvery { artworkImport.detectSources() } returns listOf(source)
        coEvery { artworkImport.buildPlan(source) } returns plan
        val job = collectState()

        vm.onArtworkFolderPicked(uri)
        advanceUntilIdle()
        vm.importArtworkNow()
        advanceUntilIdle()

        coVerify { artworkImport.startImport(plan, PortableArtworkLibrary.Transfer.COPY) }
        io.mockk.verify {
            tasks.report(id = "setup_artwork_import", label = match { it.contains("Importing") }, message = any(), severity = any(), kind = any(), action = any())
        }
        job.cancel()
    }

    @Test fun `importArtworkNow with no source explains instead of importing`() =
        runTest(dispatcher) {
            val job = collectState()
            advanceUntilIdle()

            vm.importArtworkNow()
            advanceUntilIdle()

            assertTrue(vm.uiState.value.message.orEmpty().contains("Nothing to import"))
            coVerify(exactly = 0) { artworkImport.startImport(any(), any()) }
            job.cancel()
        }

    // ── RetroArch ───────────────────────────────────────────────────────────────

    @Test fun `linkRetroArch saves the tree and reports installed cores`() = runTest(dispatcher) {
        coEvery { retroArchLink.inventory() } returns CoreInventory.Verified(
            setOf("snes9x_libretro_android.so", "mgba_libretro_android.so")
        )
        val uri = mockk<Uri>()
        val job = collectState()

        vm.linkRetroArch(uri)
        advanceUntilIdle()

        coVerify { retroArchLink.save(uri) }
        coVerify { refresher.run() }
        assertTrue(vm.uiState.value.retroArchLinked)
        assertEquals(2, vm.uiState.value.retroArchCoreCount)
        job.cancel()
    }

    @Test fun `unlinkRetroArch clears the link and the detected count`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        vm.unlinkRetroArch()
        advanceUntilIdle()

        coVerify { retroArchLink.clear() }
        assertFalse(vm.uiState.value.retroArchLinked)
        assertNull(vm.uiState.value.retroArchCoreCount)
        job.cancel()
    }

    // ── Vita3K data folder ─────────────────────────────────────────────────────

    @Test fun `linkVitaFolder grants the ux0 folder and reports it`() = runTest(dispatcher) {
        val uri = mockk<Uri> {
            every { this@mockk.toString() } returns "content://tree/primary%3ARoms%2Fvita%2Fux0"
        }
        val job = collectState()

        vm.linkVitaFolder(uri)
        advanceUntilIdle()

        coVerify { vita3KLibrary.setUx0Folder(uri) }
        io.mockk.verify { tasks.report(id = "setup_vita", label = any(), message = any(), severity = any(), kind = any(), action = any()) }
        job.cancel()
    }

    @Test fun `forgetVitaFolder clears the ux0 grant without touching files`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        vm.forgetVitaFolder()
        advanceUntilIdle()

        coVerify { vita3KLibrary.clear() }
        job.cancel()
    }

    // ── Trophies: PS3 (ARMSX3) data folder ─────────────────────────────────────

    @Test fun `linkPs3Folder grants through the same Ps3DataLibrary Library Manager uses`() =
        runTest(dispatcher) {
            val uri = mockk<Uri>()
            val job = collectState()

            vm.linkPs3Folder(uri)
            advanceUntilIdle()

            coVerify { ps3DataLibrary.setDataFolder(uri) }
            io.mockk.verify { tasks.report(id = "setup_ps3", label = any(), message = any(), severity = any(), kind = any(), action = any()) }
            job.cancel()
        }

    @Test fun `forgetPs3Folder releases the grant`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        vm.forgetPs3Folder()
        advanceUntilIdle()

        coVerify { ps3DataLibrary.clear() }
        job.cancel()
    }

    @Test fun `a linked PS3 data folder is mirrored as its display name`() = runTest(dispatcher) {
        every { ps3DataLibrary.dataTreeUriFlow } returns
            flowOf("content://com.android.externalstorage.documents/tree/primary%3APS3%2Fconfig%2Fdev_hdd0")
        vm = buildVm()
        val job = collectState()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.ps3FolderName)
        job.cancel()
    }

    // ── Local Achievements: Xbox 360 (X360 Mobile, XenDroid) data folders ──────────

    @Test fun `linkXbox360Folder grants each emulator's folder separately`() = runTest(dispatcher) {
        val mobile = mockk<Uri>()
        val xenDroid = mockk<Uri>()
        val job = collectState()

        vm.linkXbox360Folder(Xbox360Emulator.X360_MOBILE, mobile)
        vm.linkXbox360Folder(Xbox360Emulator.XENDROID, xenDroid)
        advanceUntilIdle()

        coVerify { xbox360DataLibrary.setFolder(Xbox360Emulator.X360_MOBILE, mobile) }
        coVerify { xbox360DataLibrary.setFolder(Xbox360Emulator.XENDROID, xenDroid) }
        job.cancel()
    }

    @Test fun `forgetXbox360Folder releases only that emulator's grant`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        vm.forgetXbox360Folder(Xbox360Emulator.XENDROID)
        advanceUntilIdle()

        coVerify { xbox360DataLibrary.clear(Xbox360Emulator.XENDROID) }
        coVerify(exactly = 0) { xbox360DataLibrary.clear(Xbox360Emulator.X360_MOBILE) }
        job.cancel()
    }

    @Test fun `linked Xbox 360 folders are mirrored per emulator`() = runTest(dispatcher) {
        every { xbox360DataLibrary.treeUriFlow(Xbox360Emulator.XENDROID) } returns
            flowOf("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata%2Fxendroid.compose")
        every { xbox360DataLibrary.treeUriFlow(Xbox360Emulator.X360_MOBILE) } returns flowOf(null)
        vm = buildVm()
        val job = collectState()
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.xenDroidFolderName)
        kotlin.test.assertNull(vm.uiState.value.x360MobileFolderName)
        job.cancel()
    }

    @Test fun `Trophies sections follow which emulator is installed`() = runTest(dispatcher) {
        every { environment.availability() } returns SetupAvailability(armsx3 = true)
        vm = buildVm()
        val job = collectState()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.armsx3Installed)
        assertFalse(vm.uiState.value.vita3KInstalled)
        job.cancel()
    }

    // ── Services (unchanged behavior) ───────────────────────────────────────────

    @Test fun `connectSteam keeps a 17-digit id without resolving`() = runTest(dispatcher) {
        vm.connectSteam("76561197960287930", "key")
        advanceUntilIdle()

        coVerify(exactly = 0) { steamApi.resolveVanity(any()) }
        coVerify { credentials.saveSteam("76561197960287930", "key") }
        coVerify { credentials.setEnabled(true) }
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

    @Test fun `navigating steps clears stale test statuses`() = runTest(dispatcher) {
        coEvery { igdbApi.testCredentials("id", "secret") } returns true
        val job = collectState()

        vm.nextStep()
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

    // ── TheGamesDB (wizard parity with Settings ▸ Artwork) ───────────────────

    @Test fun `connectTgdb stores the key through the shared provider`() = runTest(dispatcher) {
        val job = collectState()
        vm.connectTgdb("  tgdb-key-123  ")
        advanceUntilIdle()

        // Trimming belongs to the provider (saveTgdbKey trims), so the raw draft is handed over.
        coVerify(exactly = 1) { metadataKeys.saveTgdbKey("  tgdb-key-123  ") }
        // The "connected" confirmation is a tray notification now, not an in-screen row.
        io.mockk.verify { tasks.report(id = "setup_svc_tgdb", label = "TheGamesDB connected", message = any(), severity = any(), kind = any(), action = any()) }
        job.cancel()
    }

    @Test fun `a stored TheGamesDB key shows as connected`() = runTest(dispatcher) {
        every { metadataKeys.tgdbKeyFlow } returns flowOf("stored-key")
        vm = buildVm()
        val job = collectState()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.hasTgdb)
        job.cancel()
    }

    @Test fun `no TheGamesDB key means not connected`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        assertFalse(vm.uiState.value.hasTgdb)
        job.cancel()
    }

    @Test fun `blank credentials are ignored`() = runTest(dispatcher) {
        vm.connectSgdb("  ")
        vm.connectTgdb("  ")
        vm.connectIgdb("client-id", "")
        vm.connectRetroAchievements("", "key")
        vm.connectSteam("id", " ")
        advanceUntilIdle()

        coVerify(exactly = 0) { sgdbKeys.saveKey(any()) }
        coVerify(exactly = 0) { metadataKeys.saveTgdbKey(any()) }
        coVerify(exactly = 0) { metadataKeys.saveIgdbCredentials(any(), any()) }
        coVerify(exactly = 0) { credentials.saveRetroAchievements(any(), any()) }
        coVerify(exactly = 0) { credentials.saveSteam(any(), any()) }
    }
}
