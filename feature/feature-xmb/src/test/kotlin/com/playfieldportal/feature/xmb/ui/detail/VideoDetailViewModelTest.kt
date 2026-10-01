package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.domain.repository.VideoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The view model's side of Video Detail's shared modals: the modal owns the text being typed and
 * hands it over on Save, and the prompts close through the functions the modal's buttons call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoDetailViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var videoRepository: VideoRepository
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

        viewModel = VideoDetailViewModel(
            context = mockk(relaxed = true),
            videoRepository = videoRepository,
            intentResolver = mockk(relaxed = true),
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
}
