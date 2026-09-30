package com.playfieldportal.core.data.saf

import android.net.Uri
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `.nomedia` marker PFP writes into every artwork folder, so the gallery and PFP's own
 * photo/video scanners leave artwork alone, must stay invisible to everything that reads those
 * folders as artwork.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SafNoMediaMarkerTest {

    @Test
    fun `the marker is a file named nomedia, in any case`() {
        assertTrue(file(".nomedia").isNoMediaMarker())
        assertTrue(file(".NOMEDIA").isNoMediaMarker())
        assertFalse(dir(".nomedia").isNoMediaMarker(), "a folder of that name marks nothing")
        assertFalse(file("nomedia.png").isNoMediaMarker())
        assertEquals(".nomedia", NO_MEDIA_MARKER)
    }

    @Test
    fun `a listing with a marker is marked`() {
        assertTrue(listOf(file("Halo.png"), file(".nomedia")).hasNoMediaMarker())
        assertFalse(listOf(file("Halo.png"), dir(".nomedia")).hasNoMediaMarker())
    }

    @Test
    fun `dropping the marker leaves every other entry`() {
        val listing = listOf(file("Halo.png"), file(".nomedia"), dir("screenshots"))

        assertEquals(listOf("Halo.png", "screenshots"), listing.withoutNoMediaMarker().map { it.name })
    }

    @Test
    fun `a folder holding only the marker reads as empty`() {
        // What lets an emptied artwork folder still be cleaned up.
        assertTrue(listOf(file(".nomedia")).withoutNoMediaMarker().isEmpty())
    }

    private fun file(name: String) = child(name, isDirectory = false)
    private fun dir(name: String) = child(name, isDirectory = true)

    private fun child(name: String, isDirectory: Boolean) = SafChild(
        documentId = "primary:Artwork/$name",
        uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AArtwork/$name"),
        name = name,
        mime = if (isDirectory) "vnd.android.document/directory" else "application/octet-stream",
        isDirectory = isDirectory,
        lastModified = null,
        sizeBytes = 0L,
    )
}
