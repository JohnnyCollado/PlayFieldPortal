package com.playfieldportal.feature.xmb.ui.app

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.GameRepository
import com.playfieldportal.core.ui.components.PspMenuCue
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import com.playfieldportal.feature.appbar.AppCategoryRepository
import com.playfieldportal.feature.appbar.InstalledAppRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * App Detail's one Options menu and its Artwork sub-list follow the shared PSP-panel rules: clamp at
 * the ends, Back climbs from the sub-list to Options, Triangle closes from any depth, and each press
 * sounds its cue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppDetailOptionsTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var menuSound: MenuSoundPlayer
    private lateinit var gameRepository: GameRepository
    private lateinit var appCategoryRepository: AppCategoryRepository
    private lateinit var installedAppRepository: InstalledAppRepository
    private lateinit var viewModel: AppDetailViewModel

    private val app = Game(id = 3L, title = "PPSSPP Gold", platformId = "android", packageName = "org.ppsspp.ppssppgold")

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        menuSound = mockk(relaxed = true)
        gameRepository = mockk(relaxed = true)
        appCategoryRepository = mockk(relaxed = true)
        installedAppRepository = mockk(relaxed = true)
        coEvery { gameRepository.getById(3L) } returns app
        viewModel = AppDetailViewModel(
            gameRepository = gameRepository,
            collectionRepository = mockk(relaxed = true),
            steamGridDb = mockk(relaxed = true),
            sgdbKeyProvider = mockk(relaxed = true),
            artworkStore = mockk(relaxed = true),
            appCategoryRepository = appCategoryRepository,
            installedAppRepository = installedAppRepository,
            discordPresence = mockk(relaxed = true),
            menuSound = menuSound,
        )
        viewModel.loadApp(3L)
        scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val state get() = viewModel.uiState.value

    private fun press(action: GamepadAction) = viewModel.handleGamepadAction(action)

    private fun moveTo(option: AppDetailOption) {
        repeat(AppDetailOption.OPTIONS_MENU.indexOf(option)) { press(GamepadAction.NAVIGATE_DOWN) }
    }

    @Test
    fun `the options menu is Favorite, Add to Card, Artwork, Edit Title, App Info, Hide`() {
        assertEquals(
            listOf("Favorite", "Add to Card", "Artwork", "Edit Title", "App Info", "Hide Everywhere"),
            AppDetailOption.OPTIONS_MENU.map { it.label },
        )
        assertEquals(
            listOf("Change Icon", "Change Background", "Reset All Artwork"),
            AppDetailOption.ARTWORK_MENU.map { it.label },
        )
    }

    @Test
    fun `rows - Favorite is silent with its state as the value, lists carry a chevron, reset is red`() {
        val on = appDetailMenuRows(AppDetailOption.OPTIONS_MENU, isFavorite = true)
        val off = appDetailMenuRows(AppDetailOption.OPTIONS_MENU, isFavorite = false)

        assertEquals("On", on[0].value)
        assertEquals("Off", off[0].value)
        assertEquals(PspMenuCue.NONE, on[0].cue)
        assertTrue(on[1].opensMenu)
        assertTrue(on[2].opensMenu)
        assertTrue(appDetailMenuRows(AppDetailOption.ARTWORK_MENU, false).last().isDestructive)
        assertTrue(on.none { it.isDestructive })
    }

    @Test
    fun `triangle closes the options menu`() {
        viewModel.openOptions()

        press(GamepadAction.OPEN_CONTEXT_MENU)

        assertFalse(state.showOptions)
        verify(exactly = 1) { menuSound.play(MenuSound.BACK, any()) }
    }

    @Test
    fun `triangle closes the whole menu from the artwork sub-list`() {
        viewModel.openArtworkMenu()

        press(GamepadAction.OPEN_CONTEXT_MENU)

        assertFalse(state.showOptions)
        assertNull(state.menuGroup)
    }

    @Test
    fun `the artwork button opens the menu on the artwork sub-list`() {
        viewModel.openArtworkMenu()

        assertTrue(state.showOptions)
        assertEquals(AppDetailMenuGroup.ARTWORK, state.menuGroup)
        assertEquals(0, state.optionsIndex)
    }

    @Test
    fun `back from the artwork sub-list returns to options with the cursor on Artwork`() {
        viewModel.openArtworkMenu()

        press(GamepadAction.BACK)

        assertTrue(state.showOptions)
        assertNull(state.menuGroup)
        assertEquals(AppDetailOption.OPTIONS_MENU.indexOf(AppDetailOption.ARTWORK), state.optionsIndex)
        assertFalse(state.closed)
        verify(exactly = 1) { menuSound.play(MenuSound.BACK, any()) }

        press(GamepadAction.BACK)
        assertFalse(state.showOptions)
    }

    @Test
    fun `select on Artwork opens the sub-list with the select cue`() {
        viewModel.openOptions()
        moveTo(AppDetailOption.ARTWORK)

        press(GamepadAction.SELECT)

        assertTrue(state.showOptions)
        assertEquals(AppDetailMenuGroup.ARTWORK, state.menuGroup)
        assertEquals(0, state.optionsIndex)
        verify(exactly = 1) { menuSound.play(MenuSound.SELECT, any()) }
    }

    @Test
    fun `back closes the menu and stays on the page`() {
        viewModel.openOptions()

        press(GamepadAction.BACK)

        assertFalse(state.showOptions)
        assertFalse(state.closed)
    }

    @Test
    fun `the cursor clamps at both ends and only a real move scrolls`() {
        viewModel.openArtworkMenu()
        val last = AppDetailOption.ARTWORK_MENU.lastIndex

        press(GamepadAction.NAVIGATE_UP)
        assertEquals(0, state.optionsIndex)
        repeat(last + 2) { press(GamepadAction.NAVIGATE_DOWN) }

        assertEquals(last, state.optionsIndex)
        verify(exactly = last) { menuSound.play(MenuSound.SCROLL, any()) }
    }

    @Test
    fun `select on a committing row plays the confirm cue`() {
        viewModel.openOptions()
        moveTo(AppDetailOption.CHANGE_NAME)

        press(GamepadAction.SELECT)

        assertTrue(state.isEditingName)
        verify(exactly = 1) { menuSound.play(MenuSound.CONFIRM, any()) }
    }

    @Test
    fun `Favorite toggles silently and leaves the menu open`() {
        viewModel.openOptions()

        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()

        coVerify { gameRepository.setFavorite(3L, true) }
        assertTrue(state.showOptions)
        verify(exactly = 0) { menuSound.play(any(), any()) }
    }

    @Test
    fun `App Info opens the system page for the package`() {
        viewModel.openOptions()
        moveTo(AppDetailOption.APP_INFO)

        press(GamepadAction.SELECT)

        verify { installedAppRepository.openAppInfo("org.ppsspp.ppssppgold") }
        assertFalse(state.showOptions)
    }

    @Test
    fun `Hide hides the app everywhere and leaves the page`() {
        viewModel.openOptions()
        moveTo(AppDetailOption.HIDE)

        press(GamepadAction.SELECT)
        scheduler.advanceUntilIdle()

        coVerify { appCategoryRepository.setHidden("org.ppsspp.ppssppgold", true) }
        assertTrue(state.closed)
    }

    @Test
    fun `Reset All Artwork asks first`() {
        viewModel.openArtworkMenu()
        repeat(2) { press(GamepadAction.NAVIGATE_DOWN) }

        press(GamepadAction.SELECT)

        assertTrue(state.confirmReset)
        assertFalse(state.showOptions)
    }

    @Test
    fun `the footer names the focused button`() {
        fun label(focus: Int, action: GamepadAction) =
            appDetailHelperItems(mainFocus = focus).first { action in it.actions }.label
        assertEquals("Launch", label(0, GamepadAction.SELECT))
        assertEquals("Options", label(1, GamepadAction.SELECT))
        assertEquals("Artwork", label(2, GamepadAction.SELECT))
        assertEquals("Options", label(0, GamepadAction.OPEN_CONTEXT_MENU))
    }
}
