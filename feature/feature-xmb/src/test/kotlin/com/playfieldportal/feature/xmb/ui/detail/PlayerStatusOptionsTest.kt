package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStatusOptionsTest {
    private lateinit var menuSound: MenuSoundPlayer
    private lateinit var viewModel: PlayerStatusViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        menuSound = mockk(relaxed = true)
        viewModel = PlayerStatusViewModel(mockk(relaxed = true), menuSound)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val vmState get() = viewModel.uiState.value

    private fun press(vararg actions: GamepadAction) = actions.forEach(viewModel::handleGamepadAction)

    private fun state(
        sort: PlayerStatusSort = PlayerStatusSort.NEWEST,
        provider: PlayerStatusProviderFilter = PlayerStatusProviderFilter.ALL,
        group: PlayerStatusOptionGroup? = null,
    ) = PlayerStatusUiState(
        sort = sort,
        providerFilter = provider,
        options = PlayerStatusOptionsMenu(group = group),
    )

    @Test
    fun `root options expose sort provider and sync, with values in the value field`() {
        val rows = playerStatusOptionRows(state())
        assertEquals(listOf("Sort", "Provider", "Update Installed Achievements"), rows.map { it.label })
        assertEquals(listOf("Newest", "All", null), rows.map { it.value })
        assertEquals(listOf(true, true, false), rows.map { it.opensMenu })

        val filtered = playerStatusOptionRows(state(sort = PlayerStatusSort.RAREST, provider = PlayerStatusProviderFilter.STEAM))
        assertEquals(listOf("Rarest", "Steam", null), filtered.map { it.value })
    }

    @Test
    fun `a sync in flight reads Updating`() {
        val rows = playerStatusOptionRows(state().copy(isSyncing = true))
        assertEquals("Updating…", rows.last().label)
    }

    @Test
    fun `sort and provider groups check active values`() {
        val sort = playerStatusOptionRows(state(sort = PlayerStatusSort.RAREST, group = PlayerStatusOptionGroup.SORT))
        assertEquals(listOf(false, true), sort.map { it.checked })

        val providers = playerStatusOptionRows(state(provider = PlayerStatusProviderFilter.STEAM, group = PlayerStatusOptionGroup.PROVIDER))
        assertEquals("Steam", providers.first { it.checked }.label)
        assertEquals(AchievementProvider.STEAM, PlayerStatusProviderFilter.STEAM.provider)
    }

    @Test
    fun `options replace page helper actions`() {
        assertEquals(listOf("Select", "Close"), playerStatusHelperItems(state()).map { it.label })
    }

    @Test
    fun `a focused game row offers View Game`() {
        val row = RecentRow("recent", "Coin", "Game", ShibaTier.GOLD, null, 1L, -1.0, AchievementProvider.STEAM, ShibaCoinsTarget.LibraryGame(1L))
        val state = PlayerStatusUiState(recent = listOf(row), focusedId = row.id)
        assertEquals(listOf("View Game", "Options", "Back"), playerStatusHelperItems(state).map { it.label })
    }

    @Test
    fun `Back from a list climbs to the root on the row that opened it, and a second Back closes`() {
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.NAVIGATE_DOWN, GamepadAction.SELECT)
        assertEquals(PlayerStatusOptionGroup.PROVIDER, vmState.options?.group)

        press(GamepadAction.BACK)
        assertEquals(PlayerStatusOptionsMenu(selectedIndex = 1), vmState.options)
        assertFalse(vmState.closed)

        press(GamepadAction.BACK)
        assertNull(vmState.options)
        assertFalse(vmState.closed)
    }

    @Test
    fun `Triangle closes the menu from inside a list`() {
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.SELECT, GamepadAction.OPEN_CONTEXT_MENU)

        assertNull(vmState.options)
    }

    @Test
    fun `the Options cursor clamps at both ends and sounds only when it moves`() {
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.NAVIGATE_UP)
        assertEquals(0, vmState.options?.selectedIndex)
        verify(exactly = 0) { menuSound.play(MenuSound.SCROLL, any()) }

        repeat(10) { press(GamepadAction.NAVIGATE_DOWN) }
        assertEquals(vmState.optionRows.lastIndex, vmState.options?.selectedIndex)
        verify(exactly = vmState.optionRows.lastIndex) { menuSound.play(MenuSound.SCROLL, any()) }
    }

    @Test
    fun `opening a list sounds SELECT, a choice CONFIRM, and leaving BACK`() {
        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.SELECT)
        verify(exactly = 1) { menuSound.play(MenuSound.SELECT, any()) }

        press(GamepadAction.SELECT)
        verify(exactly = 1) { menuSound.play(MenuSound.CONFIRM, any()) }

        press(GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.BACK)
        verify(exactly = 1) { menuSound.play(MenuSound.BACK, any()) }
    }
}
