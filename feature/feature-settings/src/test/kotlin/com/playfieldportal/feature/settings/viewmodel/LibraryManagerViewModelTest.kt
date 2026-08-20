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
import com.playfieldportal.feature.library.scanner.DiscSetReconciler
import com.playfieldportal.feature.library.scanner.ExistingRomPathResolver
import com.playfieldportal.feature.library.scanner.LibraryScanner
import com.playfieldportal.feature.library.scanner.PlatformScanOutcome
import com.playfieldportal.feature.library.scanner.RomScanner
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
    private val existingRomPathResolver = mockk<ExistingRomPathResolver>(relaxed = true)
    private val discSetReconciler = mockk<DiscSetReconciler>(relaxed = true)
    private val windowsLibrarySetup = mockk<WindowsLibrarySetup>(relaxed = true)
    private val pcGameScanner = mockk<PcGameScanner>(relaxed = true)
    private val localSteamSchemaGenerator = mockk<LocalSteamSchemaGenerator>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val vita3KLibrary = mockk<Vita3KLibrary>(relaxed = true)
    private val vitaGameScanner = mockk<VitaGameScanner>(relaxed = true)
    private val libraryScanner = mockk<LibraryScanner>(relaxed = true)

    private lateinit var vm: LibraryManagerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { romRootRepository.roots } returns flowOf(emptyList())
        every { memoryCardRepository.observeAll() } returns flowOf(emptyList())
        every { gameRepository.observeAll() } returns flowOf(emptyList())
        every { emulatorProfileRepository.profiles } returns flowOf(emptyList())
        every { vita3KLibrary.ux0TreeUriFlow } returns flowOf(null)
        vm = LibraryManagerViewModel(
            context,
            memoryCardRepository,
            romScanner,
            gameRepository,
            emulatorProfileRepository,
            romRootRepository,
            folderHintResolver,
            launcherShortcutRepository,
            existingRomPathResolver,
            discSetReconciler,
            windowsLibrarySetup,
            pcGameScanner,
            localSteamSchemaGenerator,
            credentials,
            vita3KLibrary,
            vitaGameScanner,
            libraryScanner,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // uiState is WhileSubscribed — tests that assert on it need an active collector.
    private fun TestScope.collectState() = launch { vm.uiState.collect {} }

    @Test
    fun `scanConsole delegates to LibraryScanner and clears the spinner`() = runTest(dispatcher) {
        coEvery { libraryScanner.scanPlatform("psx", false) } returns
            PlatformScanOutcome("psx", "PlayStation Memory Card", ScanStatus.COMPLETED, added = 3)

        val job = collectState()
        vm.scanConsole("psx")
        advanceUntilIdle()

        coVerify(exactly = 1) { libraryScanner.scanPlatform("psx", false) }
        assertEquals("PlayStation Memory Card: 3 new ROM(s) added", vm.uiState.value.message)
        assertTrue("psx" !in vm.uiState.value.scanningPlatformIds)
        job.cancel()
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
