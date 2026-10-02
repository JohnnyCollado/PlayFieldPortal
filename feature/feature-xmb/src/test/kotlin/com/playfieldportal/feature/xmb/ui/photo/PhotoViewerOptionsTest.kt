package com.playfieldportal.feature.xmb.ui.photo

import android.content.Context
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.repository.PhotoRepository
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's Options menu follows the shared PSP-panel rules: the cursor stops at the first and
 * last row instead of wrapping, Triangle and Back close it, and each press sounds its cue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotoViewerOptionsTest {

    private lateinit var menuSound: MenuSoundPlayer
    private lateinit var viewModel: PhotoViewerViewModel

    private val last get() = viewModel.uiState.value.optionsActions.lastIndex

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        menuSound = mockk(relaxed = true)
        viewModel = PhotoViewerViewModel(mockk<Context>(relaxed = true), mockk<PhotoRepository>(relaxed = true), menuSound)
        viewModel.openOptions()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val index get() = viewModel.uiState.value.optionsIndex

    @Test
    fun `up on the first row stays put and is silent`() {
        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_UP)

        assertEquals(0, index)
        verify(exactly = 0) { menuSound.play(any(), any()) }
    }

    @Test
    fun `down on the last row stays put instead of wrapping`() {
        repeat(last) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        assertEquals(last, index)

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)

        assertEquals(last, index)
        verify(exactly = last) { menuSound.play(MenuSound.SCROLL, any()) }
    }

    @Test
    fun `triangle closes the menu`() {
        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)

        assertFalse(viewModel.uiState.value.showOptions)
        verify(exactly = 1) { menuSound.play(MenuSound.BACK, any()) }
    }

    @Test
    fun `back closes the menu`() {
        viewModel.handleGamepadAction(GamepadAction.BACK)

        assertFalse(viewModel.uiState.value.showOptions)
        verify(exactly = 1) { menuSound.play(MenuSound.BACK, any()) }
    }

    @Test
    fun `select commits the focused row with the confirm cue`() {
        viewModel.handleGamepadAction(GamepadAction.SELECT)   // Rotate Left, the first row

        assertFalse(viewModel.uiState.value.showOptions)
        assertEquals(270, viewModel.uiState.value.rotationDegrees)
        verify(exactly = 1) { menuSound.play(MenuSound.CONFIRM, any()) }
    }

    private fun labels() = photoOptionRows(viewModel.uiState.value).map { it.label }

    @Test
    fun `an unzoomed photo hides Zoom Out and Reset Zoom`() {
        assertEquals(
            listOf(
                "Rotate Left", "Rotate Right", "Zoom In",
                "Set as Launcher Wallpaper", "View Information", "Show File Location", "Remove from Library",
            ),
            labels(),
        )
    }

    @Test
    fun `a zoomed photo offers Zoom Out and Reset Zoom`() {
        viewModel.onGesture(2f, 0f, 0f)

        assertEquals(
            listOf(
                "Rotate Left", "Rotate Right", "Zoom In", "Zoom Out", "Reset Zoom",
                "Set as Launcher Wallpaper", "View Information", "Show File Location", "Remove from Library",
            ),
            labels(),
        )
    }

    @Test
    fun `rows group under View and Manage and Remove is red and last`() {
        val rows = photoOptionRows(viewModel.uiState.value)

        assertEquals(listOf("View" to 0, "Manage" to 3), rows.mapIndexedNotNull { i, r -> r.header?.let { it to i } })
        assertTrue(rows.last().isDestructive)
    }

    @Test
    fun `the cursor walks the visible rows, so Zoom In then Down lands on the wallpaper row`() {
        repeat(3) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }   // Zoom In, then past it
        viewModel.handleGamepadAction(GamepadAction.SELECT)

        assertTrue(viewModel.uiState.value.wallpaperPreviewVisible)
    }
}
