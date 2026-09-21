package com.playfieldportal.feature.xmb.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins how the fullscreen browser's sort hint is spelled.
 *
 * Both the header pill and the browser's Options row draw it, and each adds the "Sort: " prefix
 * itself. State used to carry a prefixed value too, so the active mode read "Sort: Sort: Title"
 * in the pill and "Sort: Sort: Title" in the menu. The prefix now lives in exactly one place
 * ([MusicBrowserState.sortPillLabel]); these tests keep the two halves from drifting apart again.
 */
class MusicBrowserSortLabelTest {

    private fun state(sortLabel: String?) = MusicBrowserState(
        view = MusicBrowserView.AllMusic,
        title = "All Music",
        sortLabel = sortLabel,
    )

    @Test
    fun `the stored label is the bare mode name`() {
        assertEquals("Title", state("Title").sortLabel)
    }

    @Test
    fun `the drawn label carries exactly one Sort prefix`() {
        assertEquals("Sort: Title", state("Title").sortPillLabel)
    }

    @Test
    fun `every sort mode draws with a single prefix`() {
        listOf(XmbSortMode.TITLE, XmbSortMode.ARTIST, XmbSortMode.ALBUM, XmbSortMode.DATE_ADDED).forEach { mode ->
            assertEquals("Sort: ${mode.label}", state(mode.label).sortPillLabel)
        }
    }

    @Test
    fun `an unsortable list draws no label at all`() {
        // Playlists carry a null label: the pill is hidden and the Options row omits the entry
        // rather than offering a Sort that would do nothing.
        assertNull(state(null).sortPillLabel)
    }
}
