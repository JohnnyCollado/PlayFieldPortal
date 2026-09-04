package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.feature.launcher.EmulatorProfileRepository
import com.playfieldportal.feature.launcher.LaunchSource
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
class EmulatorAssignmentViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val memoryCardRepository = mockk<MemoryCardRepository>(relaxed = true)
    private val platformDao = mockk<PlatformDao>(relaxed = true)
    private val gameRepository = mockk<GameRepository>(relaxed = true)
    private val profileRepository = mockk<EmulatorProfileRepository>(relaxed = true)

    private lateinit var vm: EmulatorAssignmentViewModel

    private fun profile(
        id: String,
        packageName: String,
        name: String = id,
        platforms: List<String> = listOf("psx"),
        intentType: IntentType = IntentType.ACTION_VIEW,
        coreMap: Map<String, String> = emptyMap(),
        autoSource: String? = null,
    ) = EmulatorProfile(
        id                   = id,
        name                 = name,
        packageName          = packageName,
        intentType           = intentType,
        supportedPlatformIds = platforms,
        coreMap              = coreMap,
        autoSource           = autoSource,
    )

    private val duckstation = profile(
        "duckstation", "com.github.stenzek.duckstation", name = "DuckStation", platforms = listOf("ps1")
    )
    private val retroarch = profile(
        "retroarch", "com.retroarch.aarch64", intentType = IntentType.COMPONENT,
        coreMap = mapOf("psx" to "/data/data/com.retroarch.aarch64/cores/pcsx_rearmed_libretro_android.so"),
    )
    private val snes9xEx = profile("snes9x_ex", "com.explusalpha.Snes9xPlus", platforms = listOf("snes"))

    private fun game(title: String, platformId: String, override: String? = null) = Game(
        title           = title,
        platformId      = platformId,
        romPath         = "/roms/$platformId/$title",
        emulatorPackage = override,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // Empty defaults for every observed flow: a test overrides only what it needs, and the
        // ViewModel's combine() only starts emitting once ALL four have produced a value.
        every { memoryCardRepository.observeAll() } returns flowOf(emptyList())
        every { platformDao.observeAll() } returns flowOf(emptyList())
        every { gameRepository.observeAllGames() } returns flowOf(emptyList())
        every { profileRepository.profiles } returns flowOf(emptyList())
        every { profileRepository.getInstalledProfiles() } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    // The ViewModel subscribes to its flows at construction, so it must be built AFTER the test's
    // stubs are in place (a stub changed later is invisible to the already-started collector).
    private fun createVm() {
        vm = EmulatorAssignmentViewModel(memoryCardRepository, platformDao, gameRepository, profileRepository)
    }

    private fun row(platformId: String) =
        vm.uiState.value.platforms.firstOrNull { it.platformId == platformId }

    private val psxPlatform = PlatformEntity(id = "psx", name = "PlayStation", shortName = "PS1", iconRes = null, accentColor = 0)
    private val snesPlatform = PlatformEntity(id = "snes", name = "Super Nintendo", shortName = "SNES", iconRes = null, accentColor = 0)

    // ── Row derivation ──────────────────────────────────────────────────────

    @Test
    fun `rows aggregate game count and per-game override count per platform`() = runTest(dispatcher) {
        every { memoryCardRepository.observeAll() } returns flowOf(
            listOf(
                MemoryCard(platformId = "psx", displayName = "PlayStation Memory Card", emulatorId = "duckstation"),
                MemoryCard(platformId = "snes", displayName = "SNES Memory Card", emulatorId = "snes9x_ex"),
            )
        )
        every { platformDao.observeAll() } returns flowOf(listOf(psxPlatform, snesPlatform))
        every { gameRepository.observeAllGames() } returns flowOf(
            listOf(
                game("Crash Bandicoot", "psx", override = "duckstation"),
                game("Final Fantasy VII", "psx", override = "retroarch"),
                game("Tomba", "psx"),
                game("Super Mario World", "snes"),
            )
        )
        every { profileRepository.profiles } returns flowOf(listOf(duckstation, retroarch, snes9xEx))
        every { profileRepository.getInstalledProfiles() } returns listOf(duckstation, retroarch, snes9xEx)
        createVm()
        advanceUntilIdle()

        val psx = row("psx")
        assertNotNull(psx)
        assertEquals("PlayStation", psx!!.platformName)
        assertEquals(3, psx.gameCount)
        assertEquals(2, psx.overrideCount)
        assertEquals("duckstation", psx.resolvedProfile?.id)
        assertEquals(LaunchSource.MEMORY_CARD, psx.source)
        assertFalse(psx.isAutomatic)
        assertEquals(1, row("snes")!!.gameCount)
    }

    @Test
    fun `windows and android platforms never appear`() = runTest(dispatcher) {
        every { gameRepository.observeAllGames() } returns flowOf(
            listOf(
                game("Crash Bandicoot", "psx"),
                game("Winlator Game", "windows"),
                game("Some App", "android"),
            )
        )
        every { profileRepository.getInstalledProfiles() } returns listOf(duckstation)
        createVm()
        advanceUntilIdle()

        assertEquals(listOf("psx"), vm.uiState.value.platforms.map { it.platformId })
    }

    @Test
    fun `platform default wins when no memory card choice exists`() = runTest(dispatcher) {
        every { platformDao.observeAll() } returns flowOf(
            listOf(
                psxPlatform.copy(preferredEmulatorPackage = "com.github.stenzek.duckstation")
            )
        )
        every { gameRepository.observeAllGames() } returns flowOf(listOf(game("Crash Bandicoot", "psx")))
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)
        createVm()
        advanceUntilIdle()

        val psx = row("psx")
        assertEquals("duckstation", psx!!.resolvedProfile?.id)
        assertEquals(LaunchSource.PLATFORM_DEFAULT, psx.source)
        assertEquals("DuckStation", psx.resolvedProfileName)
    }

    @Test
    fun `automatic pick is the standalone and reported as recommended`() = runTest(dispatcher) {
        every { gameRepository.observeAllGames() } returns flowOf(listOf(game("Crash Bandicoot", "psx")))
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)
        createVm()
        advanceUntilIdle()

        val psx = row("psx")
        assertEquals("duckstation", psx!!.resolvedProfile?.id)
        assertEquals(LaunchSource.CATALOG_DEFAULT, psx.source)
        assertTrue(psx.isAutomatic)
        // The standalone is the catalog recommendation even though RetroArch was listed first.
        assertEquals("duckstation", psx.candidates.first { it.isRecommended }.profile.id)
        assertTrue(psx.candidates.first { it.profile.id == "duckstation" }.isDefault)
    }

    @Test
    fun `retroarch default without a mapped core is flagged missing core`() = runTest(dispatcher) {
        val raNoCore = profile(
            "retroarch_no_core", "com.retroarch.aarch64", intentType = IntentType.COMPONENT,
            coreMap = mapOf("snes" to "/data/data/com.retroarch.aarch64/cores/snes9x_libretro_android.so"),
        )
        every { memoryCardRepository.observeAll() } returns flowOf(
            listOf(MemoryCard(platformId = "psx", displayName = "PlayStation Memory Card", emulatorId = "retroarch_no_core"))
        )
        every { gameRepository.observeAllGames() } returns flowOf(listOf(game("Crash Bandicoot", "psx")))
        every { profileRepository.getInstalledProfiles() } returns listOf(raNoCore)
        every { profileRepository.profiles } returns flowOf(listOf(raNoCore))
        createVm()
        advanceUntilIdle()

        val psx = row("psx")
        assertTrue(psx!!.isMissingCore)
        assertNull(psx.resolvedCoreName)
        assertNotNull(psx.defaultDisplayName)
    }

    @Test
    fun `rows stay empty when the library has no games`() = runTest(dispatcher) {
        createVm()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.platforms.isEmpty())
    }

    // ── Navigation + default writes ─────────────────────────────────────────

    @Test
    fun `openDetail and back are consumed internally and restore the list row`() = runTest(dispatcher) {
        every { gameRepository.observeAllGames() } returns flowOf(listOf(game("Crash Bandicoot", "psx")))
        every { profileRepository.getInstalledProfiles() } returns listOf(duckstation)
        createVm()
        advanceUntilIdle()

        vm.openDetail("psx")
        assertEquals("psx", vm.uiState.value.detailPlatformId)
        assertEquals("psx", vm.uiState.value.returnFocusKey)
        assertTrue(vm.onBack())
        assertNull(vm.uiState.value.detailPlatformId)
        assertFalse(vm.onBack())
    }

    @Test
    fun `selecting a default writes the memory card emulator`() = runTest(dispatcher) {
        every { gameRepository.observeAllGames() } returns flowOf(listOf(game("Crash Bandicoot", "psx")))
        every { profileRepository.getInstalledProfiles() } returns listOf(retroarch, duckstation)
        createVm()
        advanceUntilIdle()

        vm.selectDefault("psx", "duckstation")
        advanceUntilIdle()

        coVerify(exactly = 1) { memoryCardRepository.setEmulator("psx", "duckstation") }
        assertTrue(vm.uiState.value.message!!.contains("DuckStation"))
    }

    @Test
    fun `useAutomaticDefault clears the stored console choice`() = runTest(dispatcher) {
        every { memoryCardRepository.observeAll() } returns flowOf(
            listOf(MemoryCard(platformId = "psx", displayName = "PlayStation Memory Card", emulatorId = "duckstation"))
        )
        every { gameRepository.observeAllGames() } returns flowOf(listOf(game("Crash Bandicoot", "psx")))
        every { profileRepository.getInstalledProfiles() } returns listOf(duckstation)
        every { profileRepository.profiles } returns flowOf(listOf(duckstation))
        createVm()
        advanceUntilIdle()

        vm.useAutomaticDefault("psx")
        advanceUntilIdle()

        coVerify(exactly = 1) { memoryCardRepository.setEmulator("psx", null) }
    }

    // ── Bulk clear ──────────────────────────────────────────────────────────

    @Test
    fun `confirming the bulk clear resets only that platform's overrides`() = runTest(dispatcher) {
        every { memoryCardRepository.observeAll() } returns flowOf(
            listOf(
                MemoryCard(platformId = "psx", displayName = "PlayStation Memory Card", emulatorId = "duckstation"),
                MemoryCard(platformId = "snes", displayName = "SNES Memory Card", emulatorId = "snes9x_ex"),
            )
        )
        every { platformDao.observeAll() } returns flowOf(listOf(psxPlatform, snesPlatform))
        every { gameRepository.observeAllGames() } returns flowOf(
            listOf(
                game("Crash Bandicoot", "psx", override = "duckstation"),
                game("Tomba", "psx"),
                game("Super Mario World", "snes", override = "snes9x_ex"),
            )
        )
        every { profileRepository.getInstalledProfiles() } returns listOf(duckstation, snes9xEx)
        every { profileRepository.profiles } returns flowOf(listOf(duckstation, snes9xEx))
        createVm()
        advanceUntilIdle()

        vm.openDetail("psx")
        vm.requestClearOverrides()
        assertEquals("psx", vm.uiState.value.confirmClearPlatformId)
        vm.confirmClearOverrides()
        advanceUntilIdle()

        // Scoped to ONE platform: snes overrides stay untouched.
        coVerify(exactly = 1) { gameRepository.clearPreferredEmulatorForPlatform("psx") }
        assertNull(vm.uiState.value.confirmClearPlatformId)
        assertTrue(vm.uiState.value.message!!.contains("cleared 1 per-game override"))
        assertEquals(1, row("snes")!!.overrideCount)
    }
}
