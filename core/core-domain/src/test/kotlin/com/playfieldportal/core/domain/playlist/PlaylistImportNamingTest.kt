package com.playfieldportal.core.domain.playlist

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins how an imported playlist is named and how a clash with an existing name is resolved. */
class PlaylistImportNamingTest {

    // ── Base name ──

    @Test
    fun `in-file name wins over the file name`() {
        assertEquals("Road Trip", PlaylistImportNaming.baseName("Road Trip", "other.m3u"))
    }

    @Test
    fun `no parsed name uses the file name without its last extension`() {
        assertEquals("Road Trip", PlaylistImportNaming.baseName(null, "Road Trip.m3u8"))
        assertEquals("My.Mix", PlaylistImportNaming.baseName(null, "My.Mix.m3u"))
    }

    @Test
    fun `blank parsed name falls back to the file name and the result is trimmed`() {
        assertEquals("Road Trip", PlaylistImportNaming.baseName("   ", "Road Trip.m3u"))
        assertEquals("Road Trip", PlaylistImportNaming.baseName("  Road Trip  ", "x.m3u"))
    }

    @Test
    fun `blank name and blank file name give the default`() {
        assertEquals("Imported Playlist", PlaylistImportNaming.baseName(" ", " "))
        assertEquals("Imported Playlist", PlaylistImportNaming.baseName(null, null))
        assertEquals("Imported Playlist", PlaylistImportNaming.baseName(null, ".m3u"))
    }

    // ── Clash ──

    @Test
    fun `free name is kept`() {
        assertEquals("Road Trip", PlaylistImportNaming.uniqueName("Road Trip", listOf("Other")))
    }

    @Test
    fun `clash gives (2) then (3)`() {
        assertEquals("Road Trip (2)", PlaylistImportNaming.uniqueName("Road Trip", listOf("Road Trip")))
        assertEquals(
            "Road Trip (3)",
            PlaylistImportNaming.uniqueName("Road Trip", listOf("Road Trip", "Road Trip (2)")),
        )
    }

    @Test
    fun `clash check is case-insensitive`() {
        assertEquals("Road Trip (2)", PlaylistImportNaming.uniqueName("Road Trip", listOf("road trip")))
        assertEquals("Road Trip (3)", PlaylistImportNaming.uniqueName("Road Trip", listOf("ROAD TRIP", "road trip (2)")))
    }

    @Test
    fun `a name already ending in a count continues from it`() {
        assertEquals("Road Trip (3)", PlaylistImportNaming.uniqueName("Road Trip (2)", listOf("Road Trip (2)")))
    }

    @Test
    fun `a counted name that is free is kept as written`() {
        assertEquals("Road Trip (2)", PlaylistImportNaming.uniqueName("Road Trip (2)", emptyList()))
    }
}
