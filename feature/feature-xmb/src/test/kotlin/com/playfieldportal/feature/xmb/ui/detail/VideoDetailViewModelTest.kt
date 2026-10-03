package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.domain.repository.VideoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

/**
 * The view model's side of Video Detail's shared modals: the modal owns the text being typed and
 * hands it over on Save, and the prompts close through the functions the modal's buttons call.
 * Its Options menu and playlist picker follow the shared PSP-panel rules: the cursor clamps instead
 * of wrapping, Triangle and Back close, and each press sounds its cue.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var videoRepository: VideoRepository
    private lateinit var menuSound: MenuSoundPlayer
    private lateinit var viewModel: VideoDetailViewModel

    private val video = Video(id = "v1", libraryId = "lib", uri = "content://videos/v1", displayName = "holiday_2019.mp4")

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        videoRepository = mockk(relaxed = true)
        coEvery { videoRepository.getVideo("v1") } returns video
        coEvery { videoRepository.getVideosForLibrary("lib") } returns listOf(video)
        // Explicit: a relaxed mock's Flow never emits, and the playlist picker reads the first value.
        every { videoRepository.observePlaylists() } returns flowOf(emptyList())
        coEvery { videoRepository.createPlaylist(any()) } returns 9L

        menuSound = mockk(relaxed = true)
        viewModel = VideoDetailViewModel(
            context = mockk(relaxed = true),
            videoRepository = videoRepository,
            intentResolver = mockk(relaxed = true),
            menuSound = menuSound,
        )
        viewModel.loadVideo("v1")
        testDispatcher.scheduler.advanceUntilIdle()
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `saveTitle writes the typed title and closes the modal`() = runTest {
        viewModel.startEditTitle()
        viewModel.saveTitle("Holiday")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { videoRepository.setCustomTitle("v1", "Holiday") }
        assertFalse(viewModel.uiState.value.isEditingTitle)
    }

    @Test
    fun `saveTitle with a blank title goes back to the file name`() = runTest {
        viewModel.startEditTitle()
        viewModel.saveTitle("  ")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { videoRepository.setCustomTitle("v1", null) }
        assertFalse(viewModel.uiState.value.isEditingTitle)
    }

    @Test
    fun `confirmCreatePlaylist creates the playlist with this video in it`() = runTest {
        viewModel.openPlaylistPicker()
        testDispatcher.scheduler.advanceUntilIdle()
        viewModel.onPlaylistRowClick(0)   // no playlists yet, so row 0 is "Create New Playlist"
        assertTrue(viewModel.uiState.value.creatingPlaylist)

        viewModel.confirmCreatePlaylist("Road Trip")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify { videoRepository.createPlaylist("Road Trip") }
        coVerify { videoRepository.addVideoToPlaylist(9L, "v1") }
        assertFalse(viewModel.uiState.value.creatingPlaylist)
    }

    @Test
    fun `cancelRemove closes the prompt without removing the video`() = runTest {
        viewModel.activate(VideoDetailAction.REMOVE)
        assertTrue(viewModel.uiState.value.confirmRemove)

        viewModel.cancelRemove()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { videoRepository.removeVideo(any()) }
        assertFalse(viewModel.uiState.value.confirmRemove)
    }

    @Test
    fun `a Confirm that reaches the page while the removal prompt is up never removes the video`() = runTest {
        viewModel.activate(VideoDetailAction.REMOVE)

        // The modal opens on Cancel; a press that raced it onto the screen must not skip that.
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 0) { videoRepository.removeVideo(any()) }
        assertTrue(viewModel.uiState.value.confirmRemove)
    }

    @Test
    fun `closeInfo closes the information notice`() = runTest {
        viewModel.activate(VideoDetailAction.INFO)
        assertTrue(viewModel.uiState.value.infoVisible)

        viewModel.closeInfo()

        assertFalse(viewModel.uiState.value.infoVisible)
    }

    // ── Options menu (PSP panel rules) ──────────────────────────────────────────

    private val optionsIndex get() = viewModel.uiState.value.optionsIndex
    private val lastOption get() = viewModel.uiState.value.optionsActions.lastIndex

    @Test
    fun `opening the options menu plays the select cue`() {
        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)

        assertTrue(viewModel.uiState.value.showOptions)
        verify(exactly = 1) { menuSound.play(MenuSound.SELECT, any()) }
    }

    @Test
    fun `up on the first option stays put and is silent`() {
        viewModel.openOptions()
        io.mockk.clearMocks(menuSound, answers = false)

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_UP)

        assertEquals(0, optionsIndex)
        verify(exactly = 0) { menuSound.play(any(), any()) }
    }

    @Test
    fun `down on the last option stays put instead of wrapping`() {
        viewModel.openOptions()
        val last = lastOption
        repeat(last) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        assertEquals(last, optionsIndex)

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)

        assertEquals(last, optionsIndex)
        verify(exactly = last) { menuSound.play(MenuSound.SCROLL, any()) }
    }

    @Test
    fun `triangle closes the options menu`() {
        viewModel.openOptions()
        io.mockk.clearMocks(menuSound, answers = false)

        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)

        assertFalse(viewModel.uiState.value.showOptions)
        verify(exactly = 1) { menuSound.play(MenuSound.BACK, any()) }
    }

    @Test
    fun `back closes the options menu`() {
        viewModel.openOptions()

        viewModel.handleGamepadAction(GamepadAction.BACK)

        assertFalse(viewModel.uiState.value.showOptions)
    }

    @Test
    fun `committing a row plays confirm, opening the picker plays select, favorite is silent`() {
        viewModel.openOptions()
        io.mockk.clearMocks(menuSound, answers = false)
        viewModel.handleGamepadAction(GamepadAction.SELECT)   // Favorite: first row, silent
        verify(exactly = 0) { menuSound.play(any(), any()) }
        viewModel.openOptions()
        io.mockk.clearMocks(menuSound, answers = false)
        repeat(viewModel.uiState.value.optionsActions.indexOf(VideoDetailAction.THUMBNAIL)) {
            viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN)
        }
        io.mockk.clearMocks(menuSound, answers = false)
        viewModel.handleGamepadAction(GamepadAction.SELECT)   // Change Thumbnail commits
        verify(exactly = 1) { menuSound.play(MenuSound.CONFIRM, any()) }

        val favorite = viewModel.uiState.value.optionsActions.indexOf(VideoDetailAction.FAVORITE)
        viewModel.openOptions()
        io.mockk.clearMocks(menuSound, answers = false)
        repeat(favorite) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        io.mockk.clearMocks(menuSound, answers = false)
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        verify(exactly = 0) { menuSound.play(any(), any()) }

        viewModel.openOptions()
        val playlist = viewModel.uiState.value.optionsActions.indexOf(VideoDetailAction.PLAYLIST)
        repeat(playlist) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        io.mockk.clearMocks(menuSound, answers = false)
        viewModel.handleGamepadAction(GamepadAction.SELECT)
        verify(exactly = 1) { menuSound.play(MenuSound.SELECT, any()) }
    }

    // ── Mockup 3 rows ───────────────────────────────────────────────────────────

    private fun rows(video: Video = this.video) =
        videoOptionRows(viewModel.uiState.value.copy(video = video))

    @Test
    fun `the options menu has no play rows, with or without a resume point`() {
        val resumable = video.copy(resumePositionMs = 90_000)
        val labels = rows(resumable).map { it.label }
        assertTrue(labels.none { it == "Play" || it == "Resume" || it == "Start from Beginning" })
        assertTrue(videoOptionRows(viewModel.uiState.value.copy(video = resumable)).size == rows().size)
    }

    @Test
    fun `rows and headers follow mockup 3`() {
        val rows = rows()
        assertEquals(
            listOf("Favorite", "Add to Playlist", "Edit Title", "Change Thumbnail", "View Information", "Show File Location", "Remove from Library"),
            rows.map { it.label },
        )
        assertEquals(
            listOf("Library" to 0, "Customize" to 2, "Manage" to 4),
            rows.mapIndexedNotNull { i, r -> r.header?.let { it to i } },
        )
    }

    @Test
    fun `favorite keeps one label and states its value, silently`() {
        val off = rows().first()
        assertEquals("Favorite", off.label)
        assertEquals("Off", off.value)
        assertTrue(off.silent)
        assertEquals("On", rows(video.copy(isFavorite = true)).first().value)
    }

    @Test
    fun `Add to Playlist opens a menu and Remove from Library is red and last`() {
        val rows = rows()
        assertTrue(rows.first { it.label == "Add to Playlist" }.opensMenu)
        assertTrue(rows.last().isDestructive)
        assertEquals(1, rows.count { it.isDestructive })
    }

    // ── Playlist picker ─────────────────────────────────────────────────────────

    private fun openPicker() {
        every { videoRepository.observePlaylists() } returns flowOf(
            listOf(playlist(1L, "A"), playlist(2L, "B")),
        )
        coEvery { videoRepository.getPlaylistIdsForVideo("v1") } returns listOf(2L)
        viewModel.openPlaylistPicker()
        testDispatcher.scheduler.advanceUntilIdle()
        io.mockk.clearMocks(menuSound, answers = false)
    }

    private fun playlist(id: Long, name: String) =
        com.playfieldportal.core.domain.model.VideoPlaylist(id = id, name = name)

    @Test
    fun `the picker keeps membership checkmarks`() {
        openPicker()

        assertEquals(listOf(false, true), viewModel.uiState.value.playlistOptions.map { it.checked })
    }

    @Test
    fun `the picker clamps at both ends`() {
        openPicker()   // two playlists plus Create New Playlist

        viewModel.handleGamepadAction(GamepadAction.NAVIGATE_UP)
        assertEquals(0, viewModel.uiState.value.playlistPickerIndex)

        repeat(5) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        assertEquals(2, viewModel.uiState.value.playlistPickerIndex)
        verify(exactly = 2) { menuSound.play(MenuSound.SCROLL, any()) }
    }

    @Test
    fun `triangle and back close the picker`() {
        openPicker()
        viewModel.handleGamepadAction(GamepadAction.OPEN_CONTEXT_MENU)
        assertFalse(viewModel.uiState.value.showPlaylistPicker)

        openPicker()
        viewModel.handleGamepadAction(GamepadAction.BACK)
        assertFalse(viewModel.uiState.value.showPlaylistPicker)
    }

    @Test
    fun `toggling a playlist confirms and Create New Playlist selects`() {
        openPicker()
        viewModel.handleGamepadAction(GamepadAction.SELECT)   // playlist A
        testDispatcher.scheduler.advanceUntilIdle()
        verify(exactly = 1) { menuSound.play(MenuSound.CONFIRM, any()) }
        coVerify { videoRepository.toggleVideoInPlaylist(1L, "v1") }
        assertTrue(viewModel.uiState.value.showPlaylistPicker)

        repeat(2) { viewModel.handleGamepadAction(GamepadAction.NAVIGATE_DOWN) }
        io.mockk.clearMocks(menuSound, answers = false)
        viewModel.handleGamepadAction(GamepadAction.SELECT)   // Create New Playlist
        verify(exactly = 1) { menuSound.play(MenuSound.SELECT, any()) }
        assertTrue(viewModel.uiState.value.creatingPlaylist)
    }
}
