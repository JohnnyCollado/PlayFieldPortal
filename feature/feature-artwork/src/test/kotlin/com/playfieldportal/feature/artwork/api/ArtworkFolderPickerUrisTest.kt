package com.playfieldportal.feature.artwork.api

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ArtworkFolderPickerUrisTest {

    @Test
    fun `device root is the primary volume root document`() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3A",
            ArtworkFolderPickerUris.deviceRoot().toString(),
        )
    }

    @Test
    fun `relink opens on the stored tree root document`() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AArtwork")
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3AArtwork",
            ArtworkFolderPickerUris.forRelink(tree).toString(),
        )
    }

    @Test
    fun `relink keeps a non storage provider authority`() {
        val tree = Uri.parse("content://some.provider/tree/v%3Aroot")
        assertEquals(
            "content://some.provider/document/v%3Aroot",
            ArtworkFolderPickerUris.forRelink(tree).toString(),
        )
    }

    @Test
    fun `relink falls back to the device root for a non tree uri`() {
        assertEquals(
            ArtworkFolderPickerUris.deviceRoot(),
            ArtworkFolderPickerUris.forRelink(Uri.parse("content://x/y")),
        )
    }
}
