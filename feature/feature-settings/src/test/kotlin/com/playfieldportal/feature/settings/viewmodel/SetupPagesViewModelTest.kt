package com.playfieldportal.feature.settings.viewmodel

import android.content.Intent
import android.net.Uri
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.data.repository.WindowsLibrarySetup
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.AutoCoreMemory
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.InstalledPcLauncher
import com.playfieldportal.feature.launcher.PcLauncherType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * The device-preference pages of Initial Setup: Emulators, Windows Games, Hints & Touch and Home
 * App. Each writes through the same store its Settings screen uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupPagesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val environment = mockk<SetupEnvironment>(relaxed = true)
    private val hintPrefs = mockk<InterfaceHintPrefs>(relaxed = true)
    private val memoryCards = mockk<MemoryCardRepository>(relaxed = true)
    private val platformDao = mockk<PlatformDao>(relaxed = true)
    private val games = mockk<GameRepository>(relaxed = true)
    private val profiles = mockk<EmulatorProfileRepository>(relaxed = true)
    private val autoCoreMemory = mockk<AutoCoreMemory>(relaxed = true)
    private val romRoots = mockk<RomRootRepository>(relaxed = true)
    private val windowsLibrary = mockk<WindowsLibrarySetup>(relaxed = true)
    private val hints = MutableStateFlow(InterfaceHints())
    private lateinit var vm: SetupPagesViewModel

    private fun profile(id: String, pkg: String, name: String, platforms: List<String>, coreMap: Map<String, String> = emptyMap()) =
        EmulatorProfile(
            id = id, name = name, packageName = pkg, intentType = IntentType.ACTION_VIEW,
            supportedPlatformIds = platforms, coreMap = coreMap,
        )

    private val duckstation = profile("duckstation", "com.github.stenzek.duckstation", "DuckStation", listOf("psx"))
    private val epsxe = profile("epsxe", "com.epsxe.ePSXe", "ePSXe", listOf("psx"))
    private val ppsspp = profile("ppsspp", "org.ppsspp.ppssppgold", "PPSSPP", listOf("psp"))
    private val snesCore = profile(
        "ra_snes9x", "com.retroarch.aarch64", "RetroArch (Snes9x)", listOf("snes"),
        coreMap = mapOf("snes" to "/cores/snes9x_libretro_android.so"),
    )

    private fun card(platformId: String, name: String, emulatorId: String? = null, treeUri: String? = null) =
        MemoryCard(platformId = platformId, displayName = name, emulatorId = emulatorId, treeUri = treeUri)

    private fun game(platformId: String) = Game(title = "Game $platformId", platformId = platformId, romPath = "/roms/$platformId/g")

    private fun buildVm() = SetupPagesViewModel(
        environment, hintPrefs, memoryCards, platformDao, games, profiles, autoCoreMemory, romRoots, windowsLibrary,
    )

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { hintPrefs.hints } returns hints
        every { memoryCards.observeAll() } returns flowOf(
            listOf(card("psx", "PlayStation Memory Card"), card("psp", "PSP Memory Card"), card("snes", "SNES Memory Card")),
        )
        every { platformDao.observeAll() } returns flowOf(
            listOf(
                PlatformEntity(id = "psx", name = "PlayStation", shortName = "PS1", iconRes = null, accentColor = 0L),
                PlatformEntity(id = "psp", name = "PlayStation Portable", shortName = "PSP", iconRes = null, accentColor = 0L),
                PlatformEntity(id = "snes", name = "Super Nintendo", shortName = "SNES", iconRes = null, accentColor = 0L),
            ),
        )
        every { games.observeAllGames() } returns flowOf(listOf(game("psx"), game("psp"), game("snes")))
        val installed = listOf(duckstation, epsxe, ppsspp, snesCore)
        every { profiles.profiles } returns flowOf(installed)
        every { profiles.getInstalledProfiles() } returns installed
        coEvery { autoCoreMemory.rememberedIds() } returns emptyMap()
        every { environment.appLabel(any()) } answers {
            when (firstArg<String>()) {
                "com.github.stenzek.duckstation" -> "DuckStation"
                "com.epsxe.ePSXe" -> "ePSXe"
                "org.ppsspp.ppssppgold" -> "PPSSPP Gold"
                "com.retroarch.aarch64" -> "RetroArch"
                else -> null
            }
        }
        vm = buildVm()
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collectState() = launch { vm.uiState.collect {} }

    // ── Emulators (T7) ──────────────────────────────────────────────────────────

    @Test fun `one row per console with a known emulator installed, named by Android label`() =
        runTest(dispatcher) {
            val job = collectState()
            advanceUntilIdle()

            val rows = vm.uiState.value.emulatorRows
            assertEquals(listOf("PlayStation", "PlayStation Portable"), rows.map { it.consoleName })
            // The label the app shows in Android, not the profile's name or package id.
            assertEquals("PPSSPP Gold", rows.single { it.platformId == "psp" }.emulatorLabel)
            assertEquals(2, rows.single { it.platformId == "psx" }.candidateIds.size)
            job.cancel()
        }

    @Test fun `a console only RetroArch can run gets no row`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.emulatorRows.none { it.platformId == "snes" })
        job.cancel()
    }

    @Test fun `confirm cycles to the next installed candidate through the Settings assignment`() =
        runTest(dispatcher) {
            val job = collectState()
            advanceUntilIdle()
            val current = vm.uiState.value.emulatorRows.single { it.platformId == "psx" }.emulatorLabel

            vm.cycleEmulator("psx")
            advanceUntilIdle()

            val next = if (current == "DuckStation") "epsxe" else "duckstation"
            coVerify { memoryCards.setEmulator("psx", next) }
            job.cancel()
        }

    @Test fun `cycling wraps from the last candidate to the first`() = runTest(dispatcher) {
        every { memoryCards.observeAll() } returns flowOf(
            listOf(card("psx", "PlayStation Memory Card", emulatorId = "epsxe"), card("psp", "PSP Memory Card")),
        )
        every { profiles.profiles } returns flowOf(listOf(duckstation, epsxe, ppsspp))
        every { profiles.getInstalledProfiles() } returns listOf(duckstation, epsxe, ppsspp)
        vm = buildVm()
        val job = collectState()
        advanceUntilIdle()
        val row = vm.uiState.value.emulatorRows.single { it.platformId == "psx" }
        // The stored card choice is the current one, wherever it sits in launch preference.
        assertEquals("epsxe", row.candidateIds[row.currentIndex])

        vm.cycleEmulator("psx")
        advanceUntilIdle()

        val expected = row.candidateIds[(row.currentIndex + 1) % row.candidateIds.size]
        coVerify { memoryCards.setEmulator("psx", expected) }
        job.cancel()
    }

    @Test fun `a console with one candidate does not rewrite its assignment`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        vm.cycleEmulator("psp")
        advanceUntilIdle()

        coVerify(exactly = 0) { memoryCards.setEmulator("psp", any()) }
        job.cancel()
    }

    // ── Windows Games (T8) ──────────────────────────────────────────────────────

    @Test fun `Detected lists every verified launcher by its label`() = runTest(dispatcher) {
        every { environment.pcLaunchers() } returns listOf(
            InstalledPcLauncher("app.gamenative", PcLauncherType.GAMENATIVE, "GameNative"),
            InstalledPcLauncher("com.winlator.cmod", PcLauncherType.WINLATOR, "Winlator Cmod"),
            InstalledPcLauncher("com.winlator.vanilla", PcLauncherType.WINLATOR, "Winlator Ludashi"),
        )
        vm = buildVm()
        val job = collectState()
        advanceUntilIdle()

        assertEquals("GameNative · Winlator Cmod · Winlator Ludashi", vm.uiState.value.detectedLaunchers)
        job.cancel()
    }

    @Test fun `setting the Windows Games folder grants it, points the library at it and clears the XMB prompt`() =
        runTest(dispatcher) {
            val uri = mockk<Uri> { every { this@mockk.toString() } returns "content://tree/primary%3APCGames" }
            val job = collectState()

            vm.linkWindowsFolder(uri)
            advanceUntilIdle()

            verify { romRoots.persist(uri, writable = true) }
            coVerify { windowsLibrary.usePickedFolder("content://tree/primary%3APCGames") }
            coVerify { windowsLibrary.clearSetupPrompt() }
            job.cancel()
        }

    @Test fun `a picked Windows folder shows on the page`() = runTest(dispatcher) {
        every { memoryCards.observeAll() } returns flowOf(
            listOf(card("windows", "Windows Memory Card", treeUri = "content://tree/primary%3APCGames")),
        )
        vm = buildVm()
        val job = collectState()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.windowsFolderName != null)
        job.cancel()
    }

    @Test fun `no picked Windows folder reads as unset`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        assertNull(vm.uiState.value.windowsFolderName)
        job.cancel()
    }

    // ── Hints & Touch (T9) ──────────────────────────────────────────────────────

    @Test fun `hints prefs are mirrored from the shared Display store`() = runTest(dispatcher) {
        hints.value = InterfaceHints(enabled = false, delaySeconds = 4f, touchButton = TouchNavButtonMode.ALWAYS_HIDE)
        val job = collectState()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertFalse(state.hints.enabled)
        assertEquals(4f, state.hints.delaySeconds)
        assertEquals(TouchNavButtonMode.ALWAYS_HIDE, state.hints.touchButton)
        job.cancel()
    }

    @Test fun `Button Hints toggles the Display pref`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        vm.toggleHints()
        advanceUntilIdle()

        coVerify { hintPrefs.setEnabled(false) }
        job.cancel()
    }

    @Test fun `Hint Delay cycles whole seconds 1 to 5 and wraps`() = runTest(dispatcher) {
        val job = collectState()
        hints.value = InterfaceHints(delaySeconds = 2.5f)
        advanceUntilIdle()
        vm.cycleHintDelay()
        advanceUntilIdle()
        coVerify { hintPrefs.setDelaySeconds(3f) }

        hints.value = InterfaceHints(delaySeconds = 5f)
        advanceUntilIdle()
        vm.cycleHintDelay()
        advanceUntilIdle()
        coVerify { hintPrefs.setDelaySeconds(1f) }
        job.cancel()
    }

    @Test fun `Touch Button cycles Auto, Always Show, Always Hide`() = runTest(dispatcher) {
        val job = collectState()
        advanceUntilIdle()

        vm.cycleTouchButton()
        advanceUntilIdle()
        coVerify { hintPrefs.setTouchButton(TouchNavButtonMode.ALWAYS_SHOW) }

        hints.value = InterfaceHints(touchButton = TouchNavButtonMode.ALWAYS_HIDE)
        advanceUntilIdle()
        vm.cycleTouchButton()
        advanceUntilIdle()
        coVerify { hintPrefs.setTouchButton(TouchNavButtonMode.AUTO) }
        job.cancel()
    }

    // ── Home App (T9) ───────────────────────────────────────────────────────────

    @Test fun `Home status is re-read when the page resumes`() = runTest(dispatcher) {
        every { environment.isHomeApp() } returns false
        vm = buildVm()
        val job = collectState()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.isHomeApp)

        every { environment.isHomeApp() } returns true
        vm.refreshHomeApp()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isHomeApp)
        job.cancel()
    }

    @Test fun `Set as Home App uses the Library Manager role request`() {
        val intent = mockk<Intent>()
        every { environment.homeRoleIntent() } returns intent

        assertEquals(intent, vm.homeRoleIntent())
    }
}
