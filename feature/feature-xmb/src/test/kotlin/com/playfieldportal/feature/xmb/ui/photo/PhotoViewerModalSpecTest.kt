package com.playfieldportal.feature.xmb.ui.photo

import com.playfieldportal.core.domain.model.Photo
import com.playfieldportal.core.ui.components.PfpModalSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shared modal the photo viewer shows for a given UI state, checked in the order the view
 * model checks these states. Removing a photo is destructive, so it opens on Cancel.
 */
class PhotoViewerModalSpecTest {

    private val events = mutableListOf<String>()

    private val photo = Photo(
        id = "p1",
        libraryId = "lib",
        uri = "content://photos/p1",
        displayName = "beach.jpg",
        mimeType = "image/jpeg",
        relativePath = "Pictures/Holiday",
    )
    private val baseState = PhotoViewerUiState(photos = listOf(photo), index = 0)

    private fun specFor(state: PhotoViewerUiState): PfpModalSpec? = photoViewerModalSpec(
        state = state,
        onDismissInfo = { events += "close-info" },
        onConfirmRemove = { events += "remove" },
        onCancelRemove = { events += "cancel-remove" },
    )

    @Test
    fun `nothing is shown over the plain viewer`() {
        assertNull(specFor(baseState))
    }

    @Test
    fun `information is a notice that lists what is known about the file`() {
        val spec = specFor(baseState.copy(infoVisible = true)) as PfpModalSpec.Notice

        assertEquals("beach.jpg", spec.title)
        assertEquals(
            listOf("Type: image/jpeg", "Location: Pictures/Holiday", "File: beach.jpg"),
            spec.message.lines(),
        )
        assertEquals("Close", spec.buttonLabel)

        spec.onDismiss()
        assertEquals(listOf("close-info"), events)
    }

    @Test
    fun `removing a photo is a destructive confirm that names the photo`() {
        val spec = specFor(baseState.copy(confirmRemove = true)) as PfpModalSpec.Confirm

        assertEquals("Remove from Library", spec.title)
        assertTrue(spec.message.contains("beach.jpg"))
        assertEquals("Remove", spec.confirmLabel)
        assertTrue(spec.destructive)
        // A stray press must never remove anything.
        assertTrue(spec.openOnCancel)

        spec.onCancel()
        spec.onConfirm()
        assertEquals(listOf("cancel-remove", "remove"), events)
    }

    @Test
    fun `the removal prompt wins over information, as it does for presses`() {
        val both = baseState.copy(confirmRemove = true, infoVisible = true)

        assertTrue(specFor(both) is PfpModalSpec.Confirm)
    }
}
