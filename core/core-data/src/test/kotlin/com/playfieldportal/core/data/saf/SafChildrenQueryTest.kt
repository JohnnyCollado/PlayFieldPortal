package com.playfieldportal.core.data.saf

import android.content.ContentResolver
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import io.mockk.every
import io.mockk.mockk
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A listing that could not be read is not an empty folder. Right after a reboot the SD card's
 * provider throws (or hands back no cursor) until the volume mounts; a scan that read that as
 * "no files here" marked the folder's games missing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SafChildrenQueryTest {

    private val tree = Uri.parse("content://com.android.externalstorage.documents/tree/1234-5678%3APSX")
    private val resolver = mockk<ContentResolver>()

    private fun cursor(vararg names: String) = MatrixCursor(
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        ),
    ).apply { names.forEach { addRow(arrayOf<Any?>("1234-5678:PSX/$it", it, "application/octet-stream", 0L, 0L)) } }

    @Test
    fun `a throwing provider is an unreadable listing`() {
        every { resolver.query(any<Uri>(), any<Array<String>>(), any<String>(), any<Array<String>>(), any<String>()) } throws
            FileNotFoundException("volume not mounted")
        assertNull(resolver.querySafChildrenOrNull(tree, "1234-5678:PSX"))
        assertEquals(emptyList(), resolver.querySafChildren(tree, "1234-5678:PSX"))
    }

    @Test
    fun `no cursor is an unreadable listing`() {
        every { resolver.query(any<Uri>(), any<Array<String>>(), any<String>(), any<Array<String>>(), any<String>()) } returns null
        assertNull(resolver.querySafChildrenOrNull(tree, "1234-5678:PSX"))
    }

    @Test
    fun `an empty cursor is an empty folder`() {
        every { resolver.query(any<Uri>(), any<Array<String>>(), any<String>(), any<Array<String>>(), any<String>()) } returns cursor()
        assertEquals(emptyList(), resolver.querySafChildrenOrNull(tree, "1234-5678:PSX"))
    }

    @Test
    fun `a readable listing returns its children`() {
        every { resolver.query(any<Uri>(), any<Array<String>>(), any<String>(), any<Array<String>>(), any<String>()) } returns
            cursor("Dino Crisis.cue", "Dino Crisis.bin")
        assertEquals(
            listOf("Dino Crisis.cue", "Dino Crisis.bin"),
            resolver.querySafChildrenOrNull(tree, "1234-5678:PSX")?.map { it.name },
        )
    }
}
