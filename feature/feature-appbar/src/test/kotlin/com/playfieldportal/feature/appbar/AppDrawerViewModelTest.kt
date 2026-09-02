package com.playfieldportal.feature.appbar

import android.graphics.drawable.Drawable
import app.cash.turbine.test
import com.playfieldportal.core.domain.model.GamepadAction
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
        viewModel = AppDrawerViewModel(
            repository,
            mockk(relaxed = true),   // menuSound
            mockk(relaxed = true),   // discordPresence
            mockk(relaxed = true),   // gameRepository
            mockk(relaxed = true),   // memoryCardRepository
        )
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

    // ── Options menu / BACK semantics ─────────────────────────────────────

    @Test
    fun `back on the open options menu closes just the menu`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        // Controller Y opens the focused app's options module (grid focus is on index 0).
        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("PPSSPP", state.menuApp?.label)
            cancelAndIgnoreRemainingEvents()
        }

        // BACK pops the menu; the drawer's grid state (which lives beside menuApp in this VM and
        // is what the shell needs to keep the drawer open) is untouched.
        viewModel.handleGamepadAction(GamepadAction.BACK)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(null, state.menuApp)
            assertEquals(null, state.confirmUninstall)
            assertEquals(0, state.selectedIndex)
            assertEquals(fakeApps().size, state.visibleApps.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `category cycling works out of an empty recently-used filter`() = runTest {
        // A user who hasn't granted usage access: no app carries a lastUsedAt, so the RECENT
        // filter is empty. L1/R1 must still cycle out of it (it previously stranded the cursor -
        // the empty-grid guard swallowed PREV/NEXT_CATEGORY along with grid navigation).
        coEvery { repository.getInstalledApps() } returns fakeApps().map { it.copy(lastUsedAt = 0L) }
        viewModel = AppDrawerViewModel(
            repository,
            mockk(relaxed = true),   // menuSound
            mockk(relaxed = true),   // discordPresence
            mockk(relaxed = true),   // gameRepository
            mockk(relaxed = true),   // memoryCardRepository
        )
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.setFilter(AppFilter.RECENT)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(AppFilter.RECENT, state.activeFilter)
            assertTrue(state.visibleApps.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }

        // L1 leaves the empty RECENT section (PREV in enum order → EMULATORS, which has apps).
        viewModel.handleGamepadAction(GamepadAction.PREV_CATEGORY)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(AppFilter.EMULATORS, state.activeFilter)
            assertEquals(2, state.visibleApps.size)
            cancelAndIgnoreRemainingEvents()
        }

        // R1 walks back into the empty RECENT section and can leave it again via L1.
        viewModel.handleGamepadAction(GamepadAction.NEXT_CATEGORY)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(AppFilter.RECENT, state.activeFilter)
            assertTrue(state.visibleApps.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.handleGamepadAction(GamepadAction.PREV_CATEGORY)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            assertEquals(AppFilter.EMULATORS, awaitItem().activeFilter)
            cancelAndIgnoreRemainingEvents()
        }

        // NEXT from EMULATORS moves into the empty RECENT section again — that's a valid move.
        viewModel.handleGamepadAction(GamepadAction.NEXT_CATEGORY)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(AppFilter.RECENT, state.activeFilter)
            assertTrue(state.visibleApps.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }

        // RECENT is the last filter — NEXT clamps (no wrap), staying on the still-empty section
        // as a harmless no-op rather than a crash, and PREV leaves it once more.
        viewModel.handleGamepadAction(GamepadAction.NEXT_CATEGORY)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            assertEquals(AppFilter.RECENT, awaitItem().activeFilter)
            cancelAndIgnoreRemainingEvents()
        }
        viewModel.handleGamepadAction(GamepadAction.PREV_CATEGORY)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            assertEquals(AppFilter.EMULATORS, awaitItem().activeFilter)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `back after opening uninstall guard rail closes the dialog not the drawer`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        testDispatcher.scheduler.advanceUntilIdle()
        // Walk to the Uninstall row and select it (fake apps have no isSystemApp flag, so the
        // Uninstall action is present for every row).
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("PPSSPP", state.confirmUninstall?.label)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.handleGamepadAction(GamepadAction.BACK)
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(null, state.confirmUninstall)
            assertEquals(null, state.menuApp)
            assertEquals(fakeApps().size, state.visibleApps.size)
            cancelAndIgnoreRemainingEvents()
        }
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
