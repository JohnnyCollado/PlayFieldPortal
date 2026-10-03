package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.playlist.PlaylistKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The import sheet takes input like every other sheet (the dispatcher's closing guard reads
 * `hasBlockingOverlay`), and a batch of files shows one report at a time.
 */
class PlaylistImportOverlayTest {

    private fun report(name: String) = PlaylistImportReport(
        kind = PlaylistKind.MUSIC, fileName = name, playlistName = name, playlistId = 1, outcomes = emptyList(),
    )

    @Test
    fun `an import sheet is a blocking overlay`() {
        val up = XMBUiState(showBootSequence = false, playlistImportQueue = PlaylistImportQueue(listOf(report("a"))))
        assertTrue(up.hasBlockingOverlay)
        assertFalse(XMBUiState(showBootSequence = false).hasBlockingOverlay)
    }

    @Test
    fun `the first report is current and counts as 1 of the batch`() {
        val queue = PlaylistImportQueue(listOf(report("a"), report("b"), report("c")))

        assertEquals("a", queue.current.fileName)
        assertEquals(1, queue.position)
        assertEquals(3, queue.total)
    }

    @Test
    fun `closing advances to the next report, and the last close ends the queue`() {
        val second = PlaylistImportQueue(listOf(report("a"), report("b"))).advance()!!

        assertEquals("b", second.current.fileName)
        assertEquals(2, second.position)
        assertEquals(2, second.total)
        assertNull(second.advance())
    }

    @Test
    fun `reports from a later pick join the end of the queue`() {
        val extended = PlaylistImportQueue(listOf(report("a"))).plus(listOf(report("b")))

        assertEquals(2, extended.total)
        assertEquals("a", extended.current.fileName)
        assertSame(extended, extended.plus(emptyList()))
    }
}
