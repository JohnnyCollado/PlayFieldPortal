package com.playfieldportal.feature.xmb.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the fullscreen browser's sort hint is spelled.
 *
 * The header pill adds the "Sort: " prefix itself ([MusicBrowserState.sortPillLabel]); state carries
 * the bare mode name. The Options menu's row is "Sort" with that bare name as its value, and opens a
 * list instead of cycling.
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

    // ── The browser's list menu: "Sort" with the mode as its value ────────────

    @Test
    fun `the list menu says Sort with the mode as the value and opens a list`() {
        val items = browserListMenuItems(resumeTrack = null, sortLabel = "Title", view = MusicBrowserView.AllMusic)
        val sort = items.single()
        assertEquals("music_browser_sort", sort.id)
        assertEquals("Sort", sort.label)
        assertEquals("Title", sort.value)
        assertTrue(sort.opensMenu)
    }

    @Test
    fun `Resume names the track as its value and comes first`() {
        val items = browserListMenuItems(resumeTrack = "Song", sortLabel = "Title", view = MusicBrowserView.AllMusic)
        assertEquals(listOf("music_browser_resume", "music_browser_sort"), items.map { it.id })
        assertEquals("Resume", items[0].label)
        assertEquals("Song", items[0].value)
    }

    @Test
    fun `the Playlists view keeps Import Playlist after Resume and has no Sort`() {
        val items = browserListMenuItems(resumeTrack = "Song", sortLabel = null, view = MusicBrowserView.Playlists)
        assertEquals(listOf("music_browser_resume", BROWSER_IMPORT_MENU_ID), items.map { it.id })
    }

    @Test
    fun `an unsortable non-playlist list with nothing playing offers nothing`() {
        assertTrue(browserListMenuItems(null, null, MusicBrowserView.AllMusic).isEmpty())
    }

    @Test
    fun `the sort list checks the active mode`() {
        val items = browserSortMenuItems(listOf(XmbSortMode.TITLE, XmbSortMode.ARTIST), current = XmbSortMode.ARTIST)
        assertEquals(listOf("Title", "Artist"), items.map { it.label })
        assertEquals(listOf(false, true), items.map { it.checked })
        assertEquals(XmbSortMode.ARTIST, browserSortModeOf(items[1].id))
        assertNull(browserSortModeOf("music_browser_resume"))
    }
}
