package com.playfieldportal.feature.launcher

import com.playfieldportal.core.data.repository.MemoryCardRepository
import com.playfieldportal.core.data.repository.RomRootRepository
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.domain.model.MemoryCard
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupStateProviderTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var romRootRepository: RomRootRepository
    private lateinit var memoryCardRepository: MemoryCardRepository
    private lateinit var emulatorProfileRepository: EmulatorProfileRepository
    private lateinit var provider: SetupStateProvider

    private fun profile(id: String = "duckstation") = EmulatorProfile(
        id = id,
        name = "DuckStation",
        packageName = "com.github.stenzek.duckstation",
        intentType = IntentType.ACTION_VIEW,
        supportedPlatformIds = listOf("psx"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        romRootRepository = mockk()
        memoryCardRepository = mockk()
        emulatorProfileRepository = mockk()
        // Defaults: everything missing. The provider calls .first() on these flows itself.
        every { romRootRepository.roots } returns flowOf(emptyList())
        every { memoryCardRepository.observeAll() } returns flowOf(emptyList())
        every { emulatorProfileRepository.profiles } returns flowOf(emptyList())
        provider = SetupStateProvider(romRootRepository, memoryCardRepository, emulatorProfileRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── current() — the imperative snapshot ──────────────────────────────

    @Test
    fun `empty install reports NO_ROM_ROOT first`() = runTest(testDispatcher) {
        val state = provider.current()
        assertEquals(SetupGap.NO_ROM_ROOT, state.firstGap)
        assertFalse(state.isPlayable)
        assertEquals("settings_library", state.firstGap.repairScreenId)
    }

    @Test
    fun `rom root without consoles reports NO_CONSOLES`() = runTest(testDispatcher) {
        every { romRootRepository.roots } returns flowOf(listOf("/sdcard/roms"))
        assertEquals(SetupGap.NO_CONSOLES, provider.current().firstGap)
    }

    @Test
    fun `rom root and console without emulators reports NO_EMULATORS`() = runTest(testDispatcher) {
        every { romRootRepository.roots } returns flowOf(listOf("/sdcard/roms"))
        coEvery { memoryCardRepository.getAll() } returns listOf(MemoryCard(platformId = "psx", displayName = "PlayStation"))
        assertEquals(SetupGap.NO_EMULATORS, provider.current().firstGap)
    }

    @Test
    fun `full setup reports NONE and is playable`() = runTest(testDispatcher) {
        every { romRootRepository.roots } returns flowOf(listOf("/sdcard/roms"))
        coEvery { memoryCardRepository.getAll() } returns listOf(MemoryCard(platformId = "psx", displayName = "PlayStation"))
        coEvery { emulatorProfileRepository.getInstalledProfiles() } returns listOf(profile())
        val state = provider.current()
        assertEquals(SetupGap.NONE, state.firstGap)
        assertTrue(state.isPlayable)
    }

    // Store failures degrade to "missing" rather than crashing the shell.

    @Test
    fun `store read failures degrade to missing`() = runTest(testDispatcher) {
        every { romRootRepository.roots } throws java.io.IOException("datastore gone")
        coEvery { memoryCardRepository.getAll() } throws java.io.IOException("db gone")
        coEvery { emulatorProfileRepository.getInstalledProfiles() } throws java.io.IOException("db gone")
        assertEquals(SetupGap.NO_ROM_ROOT, provider.current().firstGap)
    }

    // ── observe() — the reactive stream ──────────────────────────────────

    @Test
    fun `observe re-derives as setup progresses`() = runTest(testDispatcher) {
        // Mutable sources so the wizard-completion simulation arrives on the SAME flow instances
        // the collector subscribed to (re-stubbing the mock would swap the flows entirely).
        val romRoots = MutableStateFlow<List<String>>(emptyList())
        val cards = MutableStateFlow<List<MemoryCard>>(emptyList())
        val emulators = MutableStateFlow<List<EmulatorProfile>>(emptyList())
        every { romRootRepository.roots } returns romRoots
        every { memoryCardRepository.observeAll() } returns cards
        every { emulatorProfileRepository.profiles } returns emulators

        val states = mutableListOf<SetupState>()
        val job = launch { provider.observe().toList(states) }
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals(SetupGap.NO_ROM_ROOT, states.first().firstGap)

        // Simulate the wizard completing.
        romRoots.value = listOf("/sdcard/roms")
        cards.value = listOf(MemoryCard(platformId = "psx", displayName = "PlayStation"))
        emulators.value = listOf(profile())
        testDispatcher.scheduler.advanceUntilIdle()
        job.cancel()
        assertEquals(SetupGap.NONE, states.last().firstGap)
    }
}
