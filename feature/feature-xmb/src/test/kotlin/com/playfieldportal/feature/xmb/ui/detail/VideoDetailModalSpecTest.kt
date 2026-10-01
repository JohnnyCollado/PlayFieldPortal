package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.ui.components.PfpModalSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shared modal Video Detail shows for a given UI state, checked in the order the view model
 * checks these states. A title may be cleared (back to the file name) while a playlist name never
 * can, and removing a video is destructive, so it opens on Cancel.
 */
class VideoDetailModalSpecTest {

    private val events = mutableListOf<String>()

    private val video = Video(
        id = "v1",
        libraryId = "lib",
        uri = "content://videos/v1",
        displayName = "holiday_2019.mp4",
        title = "Holiday",
        durationMs = 83_000,
        width = 1920,
        height = 1080,
        relativePath = "Movies/Family",
    )
    private val baseState = VideoDetailUiState(video = video, isLoading = false)

    private fun specFor(state: VideoDetailUiState): PfpModalSpec? = videoDetailModalSpec(
        state = state,
        onSaveTitle = { events += "title:$it" },
        onCancelTitle = { events += "cancel-title" },
        onCreatePlaylist = { events += "playlist:$it" },
        onCancelCreatePlaylist = { events += "cancel-playlist" },
        onDismissInfo = { events += "close-info" },
        onConfirmRemove = { events += "remove" },
        onCancelRemove = { events += "cancel-remove" },
        onDismissLaunchError = { events += "dismiss-error" },
    )

    @Test
    fun `nothing is shown on the plain page`() {
        assertNull(specFor(baseState))
    }

    @Test
    fun `rename title starts from the title on screen and may be cleared back to the file name`() {
        val spec = specFor(baseState.copy(isEditingTitle = true)) as PfpModalSpec.TextEntry

        assertEquals("Rename Title", spec.title)
        assertEquals("Holiday", spec.initial)
        assertTrue(spec.allowBlank)
        assertEquals("holiday_2019.mp4", spec.placeholder)

        spec.onConfirm("")
        spec.onCancel()
        assertEquals(listOf("title:", "cancel-title"), events)
    }

    @Test
    fun `a new playlist starts empty and can never be blank`() {
        val spec = specFor(baseState.copy(showPlaylistPicker = true, creatingPlaylist = true)) as PfpModalSpec.TextEntry

        assertEquals("New Playlist", spec.title)
        assertEquals("", spec.initial)
        assertEquals("Create", spec.confirmLabel)
        assertFalse(spec.allowBlank)

        spec.onConfirm("Road Trip")
        spec.onCancel()
        assertEquals(listOf("playlist:Road Trip", "cancel-playlist"), events)
    }

    @Test
    fun `information is a notice that lists what is known about the file`() {
        val spec = specFor(baseState.copy(infoVisible = true)) as PfpModalSpec.Notice

        assertEquals("Holiday", spec.title)
        assertEquals(
            listOf(
                "Duration: 1:23",
                "Resolution: 1920×1080",
                "Location: Movies/Family",
                "File: holiday_2019.mp4",
            ),
            spec.message.lines(),
        )
        assertEquals("Close", spec.buttonLabel)

        spec.onDismiss()
        assertEquals(listOf("close-info"), events)
    }

    @Test
    fun `removing a video is a destructive confirm that names the video`() {
        val spec = specFor(baseState.copy(confirmRemove = true)) as PfpModalSpec.Confirm

        assertEquals("Remove from Library", spec.title)
        assertTrue(spec.message.contains("Holiday"))
        assertEquals("Remove", spec.confirmLabel)
        assertTrue(spec.destructive)
        // A stray press must never remove anything.
        assertTrue(spec.openOnCancel)

        spec.onCancel()
        spec.onConfirm()
        assertEquals(listOf("cancel-remove", "remove"), events)
    }

    @Test
    fun `a launch failure is a notice carrying the reason`() {
        val spec = specFor(baseState.copy(launchError = "No installed app can open this file.")) as PfpModalSpec.Notice

        assertEquals("Can't play video", spec.title)
        assertEquals("No installed app can open this file.", spec.message)

        spec.onDismiss()
        assertEquals(listOf("dismiss-error"), events)
    }

    @Test
    fun `a launch failure wins over everything else, as it does for presses`() {
        val both = baseState.copy(launchError = "Player not found.", confirmRemove = true, isEditingTitle = true)

        assertEquals("Can't play video", (specFor(both) as PfpModalSpec.Notice).title)
        assertTrue(specFor(both.copy(launchError = null)) is PfpModalSpec.Confirm)
    }
}
