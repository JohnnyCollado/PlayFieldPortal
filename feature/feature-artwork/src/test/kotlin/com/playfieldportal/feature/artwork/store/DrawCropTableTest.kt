package com.playfieldportal.feature.artwork.store

import com.playfieldportal.core.data.database.dao.DrawCropRow
import com.playfieldportal.core.ui.motion.DrawCrop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The lookup the image loader makes for every artwork load: is this file framed at draw time,
 * and how? Keyed by the exact reference the game column and the record share, so it matches
 * whatever form a call site hands Coil.
 */
class DrawCropTableTest {

    private val uri = "content://tree/psx/icons/Crash.webp"
    private val table = DrawCropTable(
        listOf(
            DrawCropRow(documentUri = uri, cropRect = "0.1000,0.2000,0.9000,0.8000"),
            DrawCropRow(documentUri = "content://tree/psx/icons/Broken.webp", cropRect = "garbage"),
        ),
    )

    @Test
    fun `finds a framed file by its reference`() {
        assertEquals(DrawCrop(0.1f, 0.2f, 0.9f, 0.8f), table.cropFor(uri))
    }

    @Test
    fun `finds it through a file path too`() {
        val path = "/data/user/0/pfp/files/artwork/Crash.webp"
        val byPath = DrawCropTable(listOf(DrawCropRow(documentUri = path, cropRect = "0,0,0.5,0.5")))

        assertEquals(DrawCrop(0f, 0f, 0.5f, 0.5f), byPath.cropFor(java.io.File(path)))
    }

    @Test
    fun `anything else is drawn as it is`() {
        assertNull(table.cropFor("content://tree/psx/icons/Other.webp"))
        assertNull(table.cropFor(null))
        assertNull(table.cropFor(42))
    }

    @Test
    fun `a stored rect that does not parse crops nothing`() {
        assertNull(table.cropFor("content://tree/psx/icons/Broken.webp"))
    }
}
