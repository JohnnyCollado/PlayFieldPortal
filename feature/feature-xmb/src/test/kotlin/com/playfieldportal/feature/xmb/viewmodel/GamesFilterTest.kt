package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.Game
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Games Filter menu and the query that feeds it — the two pure pieces the ViewModel
 * delegates to, so the menu can never describe a state the column isn't in.
 *
 * [gamesForDisplay] is the single funnel every Games list goes through. The tests that matter most
 * here are the boring ones: a blank query must change nothing, and the match must be against the
 * *display* title, because both are the ways this silently eats someone's library.
 */
class GamesFilterTest {

    private fun game(
        id: Long,
        title: String,
        lastPlayed: Long? = null,
        override: String? = null,
        scraped: String? = null,
    ) = Game(
        id = id,
        title = title,
        platformId = "ps",
        lastPlayedAt = lastPlayed,
        userTitleOverride = override,
        scrapedTitle = scraped,
    )

    private fun gamesCategory(gaming: Boolean = false, id: String = BuiltInCategory.GAMES) =
        Category(id, "Games", "games", type = CategoryType.BUILT_IN, position = 0, isGamingCategory = gaming)

    /** Games, drilled into a Memory Card — the state where the Filter menu is live. */
    private fun gamesState(query: String = "", sort: XmbSortMode = XmbSortMode.TITLE) = XMBUiState(
        categories = listOf(gamesCategory()),
        selectedCategoryIndex = 0,
        selectedPlatformId = "ps2",
        gameQuery = query,
        gameSortMode = sort,
    )

    // ── gamesForDisplay: the funnel ─────────────────────────────────────────────

    @Test
    fun `blank query filters nothing and still sorts`() {
        val games = listOf(game(1, "Zelda"), game(2, "abe"), game(3, "Mario"))
        val out = gamesForDisplay(games, query = "", mode = XmbSortMode.TITLE)
        assertEquals(listOf("abe", "Mario", "Zelda"), out.map { it.title })
    }

    @Test
    fun `whitespace-only query filters nothing`() {
        val games = listOf(game(1, "Zelda"), game(2, "Mario"))
        val out = gamesForDisplay(games, query = "   ", mode = XmbSortMode.TITLE)
        assertEquals(2, out.size)
    }

    @Test
    fun `query matches a substring, not only a prefix`() {
        val games = listOf(
            game(1, "The Legend of Zelda"),
            game(2, "Zelda II"),
            game(3, "Gran Turismo"),
        )
        val out = gamesForDisplay(games, query = "zelda", mode = XmbSortMode.TITLE)
        assertEquals(listOf("The Legend of Zelda", "Zelda II"), out.map { it.title })
    }

    @Test
    fun `query is case-insensitive in both directions`() {
        val games = listOf(game(1, "MARIO KART"), game(2, "mario party"))
        assertEquals(2, gamesForDisplay(games, "MaRiO", XmbSortMode.TITLE).size)
    }

    @Test
    fun `query is trimmed before matching`() {
        val games = listOf(game(1, "Mario Kart"))
        assertEquals(1, gamesForDisplay(games, "  mario  ", XmbSortMode.TITLE).size)
    }

    @Test
    fun `matches the display title, not the raw scan title`() {
        // The user renamed this row. Searching for what the row SHOWS has to find it, and
        // searching for the name only the database remembers must not.
        val renamed = game(1, title = "SLUS-20946", override = "Shadow of the Colossus")
        assertEquals(1, gamesForDisplay(listOf(renamed), "colossus", XmbSortMode.TITLE).size)
        assertTrue(gamesForDisplay(listOf(renamed), "slus", XmbSortMode.TITLE).isEmpty())
    }

    @Test
    fun `a scraped title is matched when there is no user override`() {
        val scraped = game(1, title = "sotc.iso", scraped = "Shadow of the Colossus")
        assertEquals(1, gamesForDisplay(listOf(scraped), "shadow", XmbSortMode.TITLE).size)
    }

    @Test
    fun `filters first, then sorts what survived`() {
        val games = listOf(
            game(1, "Mario Kart", lastPlayed = 100),
            game(2, "Zelda", lastPlayed = 900),
            game(3, "Mario Party", lastPlayed = 500),
        )
        val out = gamesForDisplay(games, "mario", XmbSortMode.RECENT_PLAYED)
        assertEquals(listOf("Mario Party", "Mario Kart"), out.map { it.title })
    }

    @Test
    fun `a query matching nothing yields an empty list, not the unfiltered one`() {
        val games = listOf(game(1, "Mario"), game(2, "Zelda"))
        assertTrue(gamesForDisplay(games, "zzz", XmbSortMode.TITLE).isEmpty())
    }

    // ── gamesFilterRows: the menu ───────────────────────────────────────────────

    @Test
    fun `root names each list with its current choice`() {
        val rows = gamesFilterRows(gamesState(sort = XmbSortMode.RECENT_PLAYED), group = null)
        assertEquals(listOf(GAMES_FILTER_SEARCH_ID, GAMES_FILTER_SORT_ID), rows.map { it.id })
        assertEquals("Search  None", rows[0].label)
        assertEquals("Sort  Recently Played", rows[1].label)
    }

    @Test
    fun `root shows the active term and offers Clear Search only then`() {
        val rows = gamesFilterRows(gamesState(query = "zel"), group = null)
        assertEquals(listOf(GAMES_FILTER_SEARCH_ID, GAMES_FILTER_SORT_ID, GAMES_FILTER_CLEAR_ID), rows.map { it.id })
        assertEquals("Search  \"zel\"", rows[0].label)
    }

    @Test
    fun `a whitespace-only query does not earn a Clear Search row`() {
        val rows = gamesFilterRows(gamesState(query = "   "), group = null)
        assertFalse(rows.any { it.id == GAMES_FILTER_CLEAR_ID })
    }

    @Test
    fun `sort group lists every game mode and checks the active one`() {
        val rows = gamesFilterRows(gamesState(sort = XmbSortMode.DATE_ADDED), GamesFilterGroup.SORT)
        assertEquals(listOf("Title", "Recently Played", "Date Added"), rows.map { it.label })
        assertEquals(listOf(false, false, true), rows.map { it.checked })
    }

    @Test
    fun `sort group ids round-trip back to their mode`() {
        val rows = gamesFilterRows(gamesState(), GamesFilterGroup.SORT)
        val modes = rows.map { row ->
            XmbSortMode.entries.first { it.name == row.id.removePrefix(GAMES_FILTER_SORT_PREFIX) }
        }
        assertEquals(listOf(XmbSortMode.TITLE, XmbSortMode.RECENT_PLAYED, XmbSortMode.DATE_ADDED), modes)
    }

    @Test
    fun `the checkmark marks the active mode, and never more than one row`() {
        val gameModes = listOf(XmbSortMode.TITLE, XmbSortMode.RECENT_PLAYED, XmbSortMode.DATE_ADDED)
        XmbSortMode.entries.forEach { mode ->
            val rows = gamesFilterRows(gamesState(sort = mode), GamesFilterGroup.SORT)
            val checked = rows.filter { it.checked }
            if (mode in gameModes) {
                assertEquals("mode=$mode", listOf(mode.label), checked.map { it.label })
            } else {
                // A music-only mode (Artist / Album) is not in the games cycle. The menu must not
                // invent a checkmark for a mode this list can never be in.
                assertTrue("mode=$mode", checked.isEmpty())
            }
        }
    }

    // ── The hint-pill label ─────────────────────────────────────────────────────

    @Test
    fun `games calls the Square action Filter`() {
        assertEquals("Filter", gamesState().sortActionLabel)
    }

    @Test
    fun `a custom gaming category calls it Filter too`() {
        val s = XMBUiState(
            categories = listOf(gamesCategory(gaming = true, id = "retro")),
            selectedCategoryIndex = 0,
        )
        assertEquals("Filter", s.sortActionLabel)
    }

    @Test
    fun `music still calls it Sort`() {
        val s = XMBUiState(
            categories = listOf(
                Category(BuiltInCategory.MUSIC, "Music", "music", type = CategoryType.BUILT_IN, position = 0),
            ),
            selectedCategoryIndex = 0,
            musicNav = MusicNav.AllMusic,
        )
        assertEquals("Sort", s.sortActionLabel)
        assertEquals(MUSIC_SORTS_FOR_TEST, s.activeSortModes())
    }

    @Test
    fun `video still calls it Sort`() {
        val s = XMBUiState(
            categories = listOf(
                Category(BuiltInCategory.VIDEO, "Video", "video", type = CategoryType.BUILT_IN, position = 0),
            ),
            selectedCategoryIndex = 0,
            videoNav = VideoNav.AllVideos,
        )
        assertEquals("Sort", s.sortActionLabel)
    }

    @Test
    fun `an unsortable list has no sort modes at all`() {
        val s = XMBUiState(
            categories = listOf(gamesCategory()),
            selectedCategoryIndex = 0,
        ) // Games root: memory cards, not a game list
        assertNull(s.activeSortModes())
    }

    private companion object {
        // The music cycle, as activeSortModes returns it. Declared here rather than reaching for
        // the private file-level list in XMBViewModel.kt.
        val MUSIC_SORTS_FOR_TEST = listOf(
            XmbSortMode.TITLE, XmbSortMode.ARTIST, XmbSortMode.ALBUM, XmbSortMode.DATE_ADDED,
        )
    }
}
