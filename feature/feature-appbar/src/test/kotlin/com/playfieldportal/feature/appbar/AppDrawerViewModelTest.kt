package com.playfieldportal.feature.appbar

import android.graphics.drawable.Drawable
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class AppDrawerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: InstalledAppRepository
    private lateinit var viewModel: AppDrawerViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        coEvery { repository.getInstalledApps() } returns fakeApps()
        every { repository.hasUsageAccess() } returns true
        viewModel = AppDrawerViewModel(repository, mockk(relaxed = true), mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Filter logic ──────────────────────────────────────────────────────

    @Test
    fun `initial state has ALL filter and no search query`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(AppFilter.ALL, state.activeFilter)
            assertTrue(state.searchQuery.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `ALL filter shows all apps`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(fakeApps().size, state.visibleApps.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `EMULATORS filter shows only emulator-tagged apps`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setFilter(AppFilter.EMULATORS)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.visibleApps.all { it.isEmulator })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `GAMES filter shows only game-tagged apps`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setFilter(AppFilter.GAMES)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.visibleApps.all { it.isGame })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `RECENT filter shows timestamped apps newest first`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setFilter(AppFilter.RECENT)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(listOf("Minecraft", "Browser"), state.visibleApps.map { it.label })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `usage access state reflects repository result`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.hasUsageAccess)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Search logic ──────────────────────────────────────────────────────

    @Test
    fun `search query filters by app label case-insensitively`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setSearchQuery("PPSSPP")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.visibleApps.all { it.label.contains("PPSSPP", ignoreCase = true) })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `clearing search query restores full list`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setSearchQuery("PPSSPP")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setSearchQuery("")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(fakeApps().size, state.visibleApps.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `search with no matches results in empty visible list`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setSearchQuery("zzzznotfound")
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.visibleApps.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Selection ─────────────────────────────────────────────────────────

    @Test
    fun `onAppSelected updates selectedIndex in state`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onAppSelected(3)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(3, state.selectedIndex)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `openUsageAccessSettings delegates to repository`() = runTest {
        viewModel.openUsageAccessSettings()
        verify { repository.openUsageAccessSettings() }
    }

    // ── isLoading ────────────────────────────────────────────────────────

    @Test
    fun `isLoading is false after initial load completes`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private val fakeDrawable: Drawable = mockk(relaxed = true)

    private fun fakeApps() = listOf(
        InstalledApp(packageName = "org.ppsspp.ppsspp",           label = "PPSSPP",    icon = fakeDrawable, isEmulator = true,  isGame = false),
        InstalledApp(packageName = "com.retroarch",                label = "RetroArch", icon = fakeDrawable, isEmulator = true,  isGame = false),
        InstalledApp(packageName = "com.mojang.minecraftpe",       label = "Minecraft", icon = fakeDrawable, isEmulator = false, isGame = true, lastUsedAt = 2_000L),
        InstalledApp(packageName = "com.playfieldportal.launcher", label = "PFP",       icon = fakeDrawable, isEmulator = false, isGame = false),
        InstalledApp(packageName = "com.example.browser",          label = "Browser",   icon = fakeDrawable, isEmulator = false, isGame = false, lastUsedAt = 1_000L),
    )
}
