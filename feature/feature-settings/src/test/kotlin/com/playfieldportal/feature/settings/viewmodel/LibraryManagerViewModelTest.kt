package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.platform.PlatformFolderHintResolver
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.data.repository.Vita3KLibrary
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSchemaGenerator
import com.playfieldportal.feature.achievements.provider.vita.VitaGameScanner
import com.playfieldportal.feature.appbar.LauncherShortcutRepository
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.LauncherChoice
import com.playfieldportal.feature.launcher.LauncherChoiceRepository
import com.playfieldportal.feature.launcher.LauncherChoices
import com.playfieldportal.feature.library.scanner.LibraryScanner
import com.playfieldportal.feature.library.scanner.PlatformScanOutcome
import com.playfieldportal.feature.library.scanner.RomScanner
import com.playfieldportal.feature.library.scanner.scanOutcomeMessage
import com.playfieldportal.feature.library.scanner.ScanStatus
import com.playfieldportal.feature.settings.pc.PcGameScanner
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryManagerViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val context = mockk<Context>(relaxed = true)
    private val memoryCardRepository = mockk<MemoryCardRepository>(relaxed = true)
    private val romScanner = mockk<RomScanner>(relaxed = true)
    private val gameRepository = mockk<GameRepository>(relaxed = true)
    private val emulatorProfileRepository = mockk<EmulatorProfileRepository>(relaxed = true)
    private val romRootRepository = mockk<RomRootRepository>(relaxed = true)
    private val folderHintResolver = mockk<PlatformFolderHintResolver>(relaxed = true)
    private val launcherShortcutRepository = mockk<LauncherShortcutRepository>(relaxed = true)
    private val windowsLibrarySetup = mockk<WindowsLibrarySetup>(relaxed = true)
    private val pcGameScanner = mockk<PcGameScanner>(relaxed = true)
    private val localSteamSchemaGenerator = mockk<LocalSteamSchemaGenerator>(relaxed = true)
    private val localSteamBatchMatcher =
        mockk<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamBatchMatcher>(relaxed = true)
    private val localSteamDiscovery =
        mockk<com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val vita3KLibrary = mockk<Vita3KLibrary>(relaxed = true)
    private val ps3DataLibrary =
        mockk<com.playfieldportal.core.data.repository.Ps3DataLibrary>(relaxed = true)
    private val vitaGameScanner = mockk<VitaGameScanner>(relaxed = true)
    private val libraryScanner = mockk<LibraryScanner>(relaxed = true)
    private val romRootScanRunner = mockk<RomRootScanRunner>(relaxed = true)
    private val pcGameExporter = mockk<com.playfieldportal.feature.settings.pc.PcGameExporter>(relaxed = true)
    private val launcherChoices = mockk<LauncherChoiceRepository>(relaxed = true)
    private val packageManager = mockk<android.content.pm.PackageManager>(relaxed = true)
    private val tasks = mockk<com.playfieldportal.core.ui.notification.BackgroundTaskCenter>(relaxed = true)

    private lateinit var vm: LibraryManagerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { romRootRepository.roots } returns flowOf(emptyList())
        every { memoryCardRepository.observeAll() } returns flowOf(emptyList())
        every { gameRepository.observeAll() } returns flowOf(emptyList())
        every { emulatorProfileRepository.profiles } returns flowOf(emptyList())
        every { vita3KLibrary.ux0TreeUriFlow } returns flowOf(null)
        every { ps3DataLibrary.dataTreeUriFlow } returns flowOf(null)
        vm = LibraryManagerViewModel(
            context,
            memoryCardRepository,
            romScanner,
            gameRepository,
            emulatorProfileRepository,
            romRootRepository,
            folderHintResolver,
            launcherShortcutRepository,
            windowsLibrarySetup,
            pcGameScanner,
            localSteamSchemaGenerator,
            localSteamBatchMatcher,
            localSteamDiscovery,
            credentials,
            vita3KLibrary,
            ps3DataLibrary,
            vitaGameScanner,
            libraryScanner,
            romRootScanRunner,
            pcGameExporter,
            tasks,
            launcherChoices,
            kotlinx.coroutines.CoroutineScope(dispatcher),
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // ── Unknown Windows Emulators (setup-wizard plan section 6) ─────────────────

    /** One installed app named like a Windows emulator, outside every catalog, signer unreadable. */
    private fun installWineFork() {
        every { context.packageManager } returns packageManager
        every { context.packageName } returns "com.playfieldportal"
        val app = mockk<android.content.pm.ApplicationInfo>(relaxed = true)
        app.packageName = "com.example.winefork"
        every { packageManager.getInstalledApplications(any<Int>()) } returns listOf(app)
        every { packageManager.getApplicationInfo(any<String>(), any<Int>()) } throws
            android.content.pm.PackageManager.NameNotFoundException()
        every { packageManager.getApplicationInfo("com.example.winefork", any<Int>()) } returns app
        every { packageManager.getApplicationLabel(app) } returns "Wine Fork"
        every { packageManager.getPackageInfo(any<String>(), any<Int>()) } throws
            android.content.pm.PackageManager.NameNotFoundException()
    }

    @Test
    fun `an unidentified Windows emulator is listed undecided`() = runTest(dispatcher) {
        installWineFork()
        coEvery { launcherChoices.snapshot() } returns LauncherChoices.EMPTY
        val job = collectState()

        vm.openImportPcGames()
        advanceUntilIdle()

        val row = vm.uiState.value.unknownLaunchers.single()
        assertEquals("Wine Fork", row.label)
        assertEquals("Choose…", row.value)
        assertEquals(0, vm.uiState.value.hiddenUnknownLaunchers.size)
        job.cancel()
    }

    @Test
    fun `confirm stores the next choice per package and signer, and the row stays this visit`() =
        runTest(dispatcher) {
            installWineFork()
            coEvery { launcherChoices.snapshot() } returns LauncherChoices.EMPTY
            val job = collectState()
            vm.openImportPcGames()
            advanceUntilIdle()

            vm.cycleUnknownLauncher("com.example.winefork")
            advanceUntilIdle()
            vm.cycleUnknownLauncher("com.example.winefork")
            advanceUntilIdle()

            coVerify { launcherChoices.set("com.example.winefork", null, LauncherChoice.NOT_A_LAUNCHER) }
            coVerify { launcherChoices.set("com.example.winefork", null, LauncherChoice.GAMEHUB) }
            assertEquals("GameHub", vm.uiState.value.unknownLaunchers.single().value)
            job.cancel()
        }

    @Test
    fun `apps marked Not a launcher hide until Show Hidden Apps`() = runTest(dispatcher) {
        installWineFork()
        coEvery { launcherChoices.snapshot() } returns
            LauncherChoices.EMPTY.with("com.example.winefork", null, LauncherChoice.NOT_A_LAUNCHER)
        val job = collectState()
        vm.openImportPcGames()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.unknownLaunchers.isEmpty())
        assertEquals(1, vm.uiState.value.hiddenUnknownLaunchers.size)

        vm.showHiddenUnknownLaunchers()
        advanceUntilIdle()

        assertEquals("Not a launcher", vm.uiState.value.unknownLaunchers.single().value)
        assertTrue(vm.uiState.value.hiddenUnknownLaunchers.isEmpty())
        job.cancel()
    }

    @Test
    fun `an app named a launcher joins PC Launchers on the next visit`() = runTest(dispatcher) {
        installWineFork()
        coEvery { launcherChoices.snapshot() } returns
            LauncherChoices.EMPTY.with("com.example.winefork", null, LauncherChoice.WINLATOR)
        val job = collectState()
        vm.openImportPcGames()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.unknownLaunchers.isEmpty())
        val row = vm.uiState.value.pcLaunchers.single { it.installed }
        assertEquals("Wine Fork", row.name)
        assertEquals("com.example.winefork", row.packageName)
        job.cancel()
    }

    // uiState is WhileSubscribed — tests that assert on it need an active collector.
    private fun TestScope.collectState() = launch { vm.uiState.collect {} }

    @Test
    fun `Windows Games back unwinds import to card detail then to list`() = runTest(dispatcher) {
        val job = collectState()

        vm.openImportPcGames()
        advanceUntilIdle()
        assertEquals(LibraryStep.IMPORT_PC, vm.uiState.value.step)
        assertTrue(vm.uiState.value.returnFocusKey != null)
        // The standalone route is expected to unwind directly to its owning card.
        vm.openCardDetail("windows")
        vm.openImportPcGames()
        assertTrue(vm.onBack())
        // uiState is stateIn(WhileSubscribed) — the sharing coroutine must run before reads.
        advanceUntilIdle()
        assertEquals(LibraryStep.CARD_DETAIL, vm.uiState.value.step)
        assertEquals("windows", vm.uiState.value.detailPlatformId)
        assertTrue(vm.onBack())
        advanceUntilIdle()
        assertEquals(LibraryStep.LIST, vm.uiState.value.step)
        // Focus returns to the Windows row it was opened from.
        assertEquals("windows", vm.uiState.value.returnFocusKey)

        job.cancel()
    }

    @Test
    fun `closing Windows Games opened from XMB resets the shared state`() = runTest(dispatcher) {
        val job = collectState()

        // Opening Windows Games from the XMB puts the activity-scoped ViewModel into the
        // Windows card detail.
        vm.openWindowsGamesRoot()
        advanceUntilIdle()
        assertEquals(LibraryStep.CARD_DETAIL, vm.uiState.value.step)
        assertEquals("windows", vm.uiState.value.detailPlatformId)
        assertTrue(vm.uiState.value.windowsGamesOpenedFromXmb)

        // Backing out closes the screen — it must not leak the detail into the next open
        // (Library Manager would otherwise pop up the Windows Memory Card detail again).
        assertFalse(vm.onBack())
        advanceUntilIdle()
        assertEquals(LibraryStep.LIST, vm.uiState.value.step)
        assertNull(vm.uiState.value.detailPlatformId)
        assertFalse(vm.uiState.value.windowsGamesOpenedFromXmb)

        job.cancel()
    }

    @Test
    fun `scanConsole delegates to LibraryScanner and clears the spinner`() = runTest(dispatcher) {
        coEvery { libraryScanner.scanPlatform("psx", false) } returns
            PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.COMPLETED, added = 3)

        val job = collectState()
        vm.scanConsole("psx")
        advanceUntilIdle()

        coVerify(exactly = 1) { libraryScanner.scanPlatform("psx", false) }
        // The outcome goes to the tray now, not an in-screen message row.
        io.mockk.verify {
            tasks.complete(
                "lm_scan_psx",
                "3 new ROM(s) added",
                com.playfieldportal.core.domain.model.NotificationAction.OpenMemoryCard("psx"),
                null,
                "PlayStation scan finished",
            )
        }
        assertNull(vm.uiState.value.message)
        assertTrue("psx" !in vm.uiState.value.scanningPlatformIds)
        job.cancel()
    }

    @Test
    fun `scanConsole reports a failed or skipped card as a failure with its code, not a success`() = runTest(dispatcher) {
        coEvery { libraryScanner.scanPlatform("psx", false) } returns
            PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.FAILED, errorMessage = "Folder not found")
        coEvery { libraryScanner.scanPlatform("snes", false) } returns
            PlatformScanOutcome("snes", "SNES Memory Card", ScanStatus.SKIPPED_NO_SOURCE)

        vm.scanConsole("psx")
        vm.scanConsole("snes")
        advanceUntilIdle()

        io.mockk.verify(exactly = 0) { tasks.complete("lm_scan_psx", any(), any(), any(), any()) }
        io.mockk.verify {
            tasks.fail("lm_scan_psx", any(), any(),
                match { (it as com.playfieldportal.core.domain.model.NotificationDetail.Notes).code == "SC-2002" },
                "PlayStation scan failed")
            tasks.fail("lm_scan_snes", any(), any(),
                match { (it as com.playfieldportal.core.domain.model.NotificationDetail.Notes).code == "SC-1001" },
                "SNES scan skipped")
        }
    }

    @Test
    fun `a scan runs on the task scope and registers itself as stoppable`() = runTest(dispatcher) {
        coEvery { libraryScanner.scanPlatform("psx", false) } returns
            PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.COMPLETED)

        vm.scanConsole("psx")
        advanceUntilIdle()

        coVerify { tasks.startStoppable("lm_scan_psx", any(), any(), any(), any(), any()) }
    }

    // ── scanOutcomeMessage mapping ────────────────────────────────────────────────

    @Test
    fun `SKIPPED_NO_SOURCE without an error uses the configured-folder message`() {
        val outcome = PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.SKIPPED_NO_SOURCE)
        assertEquals(
            "PlayStation Memory Card: ROM folder not configured.",
            scanOutcomeMessage(outcome, removeMissing = false),
        )
    }

    @Test
    fun `SKIPPED_NO_SOURCE with an error surfaces the error message`() {
        val outcome = PlatformScanOutcome(
            "psx", "PlayStation Memory Card", ScanStatus.SKIPPED_NO_SOURCE,
            errorMessage = "Memory Card not found.",
        )
        assertEquals(
            "PlayStation Memory Card: Memory Card not found.",
            scanOutcomeMessage(outcome, removeMissing = false),
        )
    }

    @Test
    fun `SKIPPED_BUSY reports an in-progress scan`() {
        val outcome = PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.SKIPPED_BUSY)
        assertEquals(
            "PlayStation Memory Card: scan already in progress.",
            scanOutcomeMessage(outcome, removeMissing = false),
        )
    }

    @Test
    fun `FAILED with an error surfaces it`() {
        val outcome = PlatformScanOutcome(
            "psx", "PlayStation Memory Card", ScanStatus.FAILED,
            errorMessage = "disk full",
        )
        assertEquals(
            "PlayStation Memory Card: disk full",
            scanOutcomeMessage(outcome, removeMissing = false),
        )
    }

    @Test
    fun `FAILED without an error falls back to a generic message`() {
        val outcome = PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.FAILED)
        assertEquals(
            "PlayStation Memory Card: scan failed.",
            scanOutcomeMessage(outcome, removeMissing = false),
        )
    }

    @Test
    fun `COMPLETED without removeMissing reports the added count only`() {
        val outcome = PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.COMPLETED, added = 3)
        assertEquals(
            "PlayStation Memory Card: 3 new ROM(s) added",
            scanOutcomeMessage(outcome, removeMissing = false),
        )
    }

    @Test
    fun `COMPLETED with zero added reports no new ROMs`() {
        val outcome = PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.COMPLETED)
        assertEquals(
            "PlayStation Memory Card: no new ROMs",
            scanOutcomeMessage(outcome, removeMissing = false),
        )
    }

    @Test
    fun `COMPLETED with removeMissing and no missing reports none missing`() {
        val outcome = PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.COMPLETED)
        assertEquals(
            "PlayStation Memory Card: no new ROMs, none missing",
            scanOutcomeMessage(outcome, removeMissing = true),
        )
    }

    @Test
    fun `COMPLETED with removeMissing and missing reports marked missing`() {
        val outcome = PlatformScanOutcome(
            "psx", "PlayStation Memory Card", ScanStatus.COMPLETED,
            added = 1, markedMissing = 2,
        )
        assertEquals(
            "PlayStation Memory Card: 1 new ROM(s) added, 2 marked missing",
            scanOutcomeMessage(outcome, removeMissing = true),
        )
    }

    @Test
    fun `COMPLETED with an error message appends it in parentheses`() {
        val outcome = PlatformScanOutcome(
            "psx", "PlayStation Memory Card", ScanStatus.COMPLETED,
            errorMessage = "one source failed",
        )
        assertEquals(
            "PlayStation Memory Card: no new ROMs (one source failed)",
            scanOutcomeMessage(outcome, removeMissing = false),
        )
    }
}
