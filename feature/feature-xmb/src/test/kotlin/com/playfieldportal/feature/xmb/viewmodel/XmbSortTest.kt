package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class XmbSortTest {

    private fun game(id: Long, title: String, lastPlayed: Long? = null) =
        Game(id = id, title = title, platformId = "ps", lastPlayedAt = lastPlayed)

    private fun track(
        id: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        trackNo: Int? = null,
        modified: Long? = null,
    ) = MusicTrack(
        id = id, folderId = "f", uri = "content://$id", displayName = id,
        title = title, artist = artist, album = album, trackNumber = trackNo, lastModified = modified,
    )

    // ── Games ───────────────────────────────────────────────────────────────────

    @Test
    fun `game TITLE sorts case-insensitively by display title`() {
        val games = listOf(game(1, "Zelda"), game(2, "abe"), game(3, "Mario"))
        val sorted = games.gameSorted(XmbSortMode.TITLE).map { it.title }
        assertEquals(listOf("abe", "Mario", "Zelda"), sorted)
    }

    @Test
    fun `game RECENT_PLAYED sorts most-recent first, never-played last`() {
        val games = listOf(
            game(1, "A", lastPlayed = 100),
            game(2, "B", lastPlayed = null),
            game(3, "C", lastPlayed = 300),
        )
        val sorted = games.gameSorted(XmbSortMode.RECENT_PLAYED).map { it.title }
        assertEquals(listOf("C", "A", "B"), sorted)
    }

    @Test
    fun `game DATE_ADDED sorts newest id first`() {
        val games = listOf(game(1, "A"), game(5, "B"), game(3, "C"))
        val sorted = games.gameSorted(XmbSortMode.DATE_ADDED).map { it.title }
        assertEquals(listOf("B", "C", "A"), sorted)
    }

    @Test
    fun `game ignores music-only modes and falls back to title`() {
        val games = listOf(game(1, "Beta"), game(2, "Alpha"))
        assertEquals(listOf("Alpha", "Beta"), games.gameSorted(XmbSortMode.ARTIST).map { it.title })
    }

    // ── Music ───────────────────────────────────────────────────────────────────

    @Test
    fun `track TITLE uses real title then file name`() {
        val tracks = listOf(track("z.mp3", title = "Apple"), track("a.mp3", title = null))
        // "Apple" vs displayName "a.mp3" -> "Apple" > "a.mp3" alphabetically? compare lowercase.
        val sorted = tracks.trackSorted(XmbSortMode.TITLE).map { it.displayTitle }
        assertEquals(listOf("a.mp3", "Apple"), sorted)
    }

    @Test
    fun `track ARTIST groups by artist then album then title, nulls last`() {
        val tracks = listOf(
            track("1", title = "Song B", artist = "Beatles", album = "Help"),
            track("2", title = "Song A", artist = "Beatles", album = "Abbey Road"),
            track("3", title = "Solo", artist = null),
            track("4", title = "Song", artist = "ABBA"),
        )
        val sorted = tracks.trackSorted(XmbSortMode.ARTIST).map { it.id }
        // ABBA, then Beatles(Abbey Road before Help), then null-artist last.
        assertEquals(listOf("4", "2", "1", "3"), sorted)
    }

    @Test
    fun `track ALBUM sorts by album then track number`() {
        val tracks = listOf(
            track("1", title = "Third",  album = "X", trackNo = 3),
            track("2", title = "First",  album = "X", trackNo = 1),
            track("3", title = "Second", album = "X", trackNo = 2),
        )
        val sorted = tracks.trackSorted(XmbSortMode.ALBUM).map { it.id }
        assertEquals(listOf("2", "3", "1"), sorted)
    }

    @Test
    fun `track DATE_ADDED sorts newest lastModified first`() {
        val tracks = listOf(
            track("old", modified = 100),
            track("new", modified = 900),
            track("mid", modified = 500),
        )
        val sorted = tracks.trackSorted(XmbSortMode.DATE_ADDED).map { it.id }
        assertEquals(listOf("new", "mid", "old"), sorted)
    }
}
