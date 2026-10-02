package com.playfieldportal.feature.appbar

import android.graphics.drawable.Drawable
import app.cash.turbine.test
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.components.PfpModalNav
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import com.playfieldportal.core.ui.sound.MenuSoundSink
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
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
    private lateinit var appCategoryRepository: AppCategoryRepository
    private lateinit var viewModel: AppDrawerViewModel
    private lateinit var menuSound: MenuSoundPlayer
    private lateinit var gameRepository: GameRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true)
        coEvery { repository.getInstalledApps() } returns fakeApps()
        every { repository.hasUsageAccess() } returns true
        // The catalog subscription reloads the drawer on package events. Silent here: these tests
        // pin filtering and cursor behaviour against one fixed app list, so a second load would
        // only add noise.
        appCategoryRepository = mockk(relaxed = true)
        every { appCategoryRepository.changes() } returns emptyFlow()
        menuSound = mockk(relaxed = true)
        gameRepository = mockk(relaxed = true)
        coEvery { gameRepository.getAppEntry(any()) } returns null
        viewModel = AppDrawerViewModel(
            repository,
            appCategoryRepository,
            menuSound,
            mockk(relaxed = true),   // discordPresence
            gameRepository,
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
            appCategoryRepository,
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
        walkToLastRow()
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

    // ── Uninstall confirm through the shared modal ─────────────────────────

    private fun openUninstallConfirm() {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        testDispatcher.scheduler.advanceUntilIdle()
        walkToLastRow()
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()
    }

    // Uninstall is the menu's last row.
    private fun walkToLastRow() {
        repeat(viewModel.uiState.value.menuRows.lastIndex) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
    }

    @Test
    fun `the uninstall confirm is a destructive modal that opens on Cancel`() {
        openUninstallConfirm()

        val spec = uninstallModalSpec(
            viewModel.uiState.value,
            onConfirm = viewModel::confirmUninstall,
            onCancel = viewModel::cancelUninstall,
        ) as PfpModalSpec.Confirm

        assertTrue(spec.destructive)
        assertTrue(spec.openOnCancel)
        assertEquals("Uninstall", spec.confirmLabel)
        assertTrue(spec.title.contains("PPSSPP"))
    }

    @Test
    fun `no confirm pending means no modal`() {
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, uninstallModalSpec(viewModel.uiState.value, onConfirm = {}, onCancel = {}))
    }

    @Test
    fun `a select reaching the view model with the confirm open does not uninstall`() {
        openUninstallConfirm()

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { repository.uninstallApp(any()) }
        assertEquals("PPSSPP", viewModel.uiState.value.confirmUninstall?.label)
    }

    @Test
    fun `select on the confirm's opening focus cancels, right then select uninstalls`() {
        openUninstallConfirm()
        val spec = uninstallModalSpec(
            viewModel.uiState.value,
            onConfirm = viewModel::confirmUninstall,
            onCancel = viewModel::cancelUninstall,
        ) as PfpModalSpec.Confirm
        var focus = PfpModalNav.initialConfirmFocus(spec.openOnCancel)
        fun press(action: GamepadAction) = PfpModalNav.handle(
            action = action,
            focus = focus,
            hasField = false,
            confirmEnabled = true,
            sounds = MenuSoundSink { },
            onFocusChange = { focus = it },
            onConfirm = spec.onConfirm,
            onCancel = spec.onCancel,
        )

        press(GamepadAction.NAVIGATE_RIGHT)
        press(GamepadAction.SELECT)

        verify(exactly = 1) { repository.uninstallApp("org.ppsspp.ppsspp") }
        assertEquals(null, viewModel.uiState.value.confirmUninstall)
    }

    @Test
    fun `select on the confirm's opening focus never uninstalls`() {
        openUninstallConfirm()
        val spec = uninstallModalSpec(
            viewModel.uiState.value,
            onConfirm = viewModel::confirmUninstall,
            onCancel = viewModel::cancelUninstall,
        ) as PfpModalSpec.Confirm

        PfpModalNav.handle(
            action = GamepadAction.SELECT,
            focus = PfpModalNav.initialConfirmFocus(spec.openOnCancel),
            hasField = false,
            confirmEnabled = true,
            sounds = MenuSoundSink { },
            onFocusChange = {},
            onConfirm = spec.onConfirm,
            onCancel = spec.onCancel,
        )

        verify(exactly = 0) { repository.uninstallApp(any()) }
        assertEquals(null, viewModel.uiState.value.confirmUninstall)
    }

    // ── Options menu on the shared panel (PspMenuNav) ─────────────────────

    private fun openMenu() {
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        testDispatcher.scheduler.advanceUntilIdle()
        clearMocks(menuSound, answers = false)
    }

    @Test
    fun `down at the last menu row stays put and plays nothing`() {
        openMenu()
        val last = viewModel.uiState.value.menuRows.lastIndex
        repeat(last) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        clearMocks(menuSound, answers = false)

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)

        assertEquals(last, viewModel.uiState.value.menuIndex)
        verify(exactly = 0) { menuSound.play(any()) }
    }

    @Test
    fun `up at the first menu row stays put and plays nothing`() {
        openMenu()

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_UP)

        assertEquals(0, viewModel.uiState.value.menuIndex)
        verify(exactly = 0) { menuSound.play(any()) }
    }

    @Test
    fun `moving down the menu plays SCROLL`() {
        openMenu()

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)

        assertEquals(1, viewModel.uiState.value.menuIndex)
        verify(exactly = 1) { menuSound.play(MenuSound.SCROLL) }
    }

    @Test
    fun `selecting a menu row plays CONFIRM`() {
        openMenu()

        viewModel.handleGamepadAction(GamepadAction.SELECT)

        verify(exactly = 1) { menuSound.play(MenuSound.CONFIRM) }
    }

    @Test
    fun `back and triangle close the menu with the BACK cue`() {
        for (action in listOf(GamepadAction.BACK, GamepadAction.OPEN_CONTEXT_MENU)) {
            openMenu()

            viewModel.handleGamepadAction(action)

            assertEquals(null, viewModel.uiState.value.menuApp)
            verify(exactly = 1) { menuSound.play(MenuSound.BACK) }
        }
    }

    @Test
    fun `x still closes the menu`() {
        openMenu()

        viewModel.handleGamepadAction(GamepadAction.CHANGE_SORT)

        assertEquals(null, viewModel.uiState.value.menuApp)
    }

    // ── The drawer's rows, values and hand-offs (mockup 2) ────────────────

    private fun rowIds() = viewModel.uiState.value.menuRows.map { it.id }

    private fun focusRow(id: String) {
        val target = rowIds().indexOf(id)
        repeat(target) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        clearMocks(menuSound, answers = false)
    }

    private fun appEntry(isFavorite: Boolean, isGame: Boolean) = Game(
        title = "PPSSPP",
        platformId = if (isGame) "android" else "app_shortcut",
        packageName = "org.ppsspp.ppsspp",
        contentType = if (isGame) GameContentType.GAME else GameContentType.ANDROID_APP,
        isFavorite = isFavorite,
    )

    @Test
    fun `the menu rows are the shared app rows in the mockup order`() {
        openMenu()

        assertEquals(
            listOf("edit_app", "favorite_toggle", "add_to_collection", "mark_game", "app_info", "app_uninstall"),
            rowIds(),
        )
    }

    @Test
    fun `the menu is not published until Favorite and Mark as Game are known`() {
        val entry = CompletableDeferred<Game?>()
        coEvery { gameRepository.getAppEntry("org.ppsspp.ppsspp") } coAnswers { entry.await() }
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.menuApp)
        assertTrue(viewModel.uiState.value.menuRows.isEmpty())

        entry.complete(appEntry(isFavorite = true, isGame = true))
        testDispatcher.scheduler.advanceUntilIdle()

        val rows = viewModel.uiState.value.menuRows
        assertEquals("PPSSPP", viewModel.uiState.value.menuApp?.label)
        assertEquals("On", rows.first { it.id == "favorite_toggle" }.value)
        assertEquals("On", rows.first { it.label == "Mark as Game" }.value)
    }

    @Test
    fun `an app with no shortcut row opens with Favorite and Mark as Game Off`() {
        openMenu()

        val rows = viewModel.uiState.value.menuRows
        assertEquals("Off", rows.first { it.id == "favorite_toggle" }.value)
        assertEquals("Off", rows.first { it.id == "mark_game" }.value)
    }

    @Test
    fun `a system app menu has no Uninstall`() {
        coEvery { repository.getInstalledApps() } returns fakeApps().map { it.copy(isSystemApp = true) }
        viewModel = AppDrawerViewModel(
            repository, appCategoryRepository, menuSound, mockk(relaxed = true), gameRepository, mockk(relaxed = true),
        )
        openMenu()

        assertFalse("app_uninstall" in rowIds())
        assertEquals("app_info", rowIds().last())
    }

    @Test
    fun `closing the menu while its values resolve keeps it closed`() {
        val entry = CompletableDeferred<Game?>()
        coEvery { gameRepository.getAppEntry(any()) } coAnswers { entry.await() }
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.openAppMenuForSelected()
        testDispatcher.scheduler.advanceUntilIdle()

        viewModel.closeAppMenu()
        entry.complete(null)
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.menuApp)
    }

    @Test
    fun `Favorite plays no cue, closes the menu and hands the toggle up`() = runTest {
        coEvery { gameRepository.getAppEntry(any()) } returns appEntry(isFavorite = false, isGame = false)
        openMenu()
        focusRow("favorite_toggle")

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 0) { menuSound.play(any()) }
        assertEquals(null, viewModel.uiState.value.menuApp)
        assertEquals(AppDrawerEvent.ToggleFavorite("org.ppsspp.ppsspp", "PPSSPP"), viewModel.events.first())
    }

    @Test
    fun `Add to Card plays SELECT and hands the app up`() = runTest {
        openMenu()
        focusRow("add_to_collection")

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { menuSound.play(MenuSound.SELECT) }
        verify(exactly = 0) { menuSound.play(MenuSound.CONFIRM) }
        assertEquals(AppDrawerEvent.AddToCard("org.ppsspp.ppsspp", "PPSSPP"), viewModel.events.first())
    }

    @Test
    fun `Edit App Details plays CONFIRM and hands the package up`() = runTest {
        openMenu()

        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        verify(exactly = 1) { menuSound.play(MenuSound.CONFIRM) }
        assertEquals(AppDrawerEvent.EditDetails("org.ppsspp.ppsspp"), viewModel.events.first())
        assertEquals(null, viewModel.uiState.value.menuApp)
    }

    @Test
    fun `Mark as Game promotes the app and Unmark demotes it`() {
        openMenu()
        focusRow("mark_game")
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { gameRepository.upsert(match { it.platformId == "android" && it.contentType == GameContentType.GAME }) }

        coEvery { gameRepository.getAppEntry(any()) } returns appEntry(isFavorite = false, isGame = true)
        openMenu()
        focusRow("unmark_game")
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify { gameRepository.upsert(match { it.platformId == "app_shortcut" }) }
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
