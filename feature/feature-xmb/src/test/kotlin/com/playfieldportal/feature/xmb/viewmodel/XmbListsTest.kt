package com.playfieldportal.feature.xmb.viewmodel

import android.graphics.drawable.Drawable
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.ListState
import com.playfieldportal.feature.appbar.CategorizedApp
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the XMB arranges a list: which stored list the screen is showing, which sort applies to it
 * (its own, else the global one), where Custom order and pins put each row, and what a Move may
 * and may not do. All pure, so the rules can be pinned without a ViewModel.
 */
class XmbListsTest {

    private val games = Category(BuiltInCategory.GAMES, "Game", "ic_games", type = CategoryType.BUILT_IN, position = 4, isGamingCategory = true)
    private val custom = Category("custom_ff_5", "Final Fantasy", "ic_games", type = CategoryType.MANUAL, position = 9, isGamingCategory = true)
    private val appStore = Category("app_store", "App Store", "ic_appstore", type = CategoryType.BUILT_IN, position = 6)
    private val videos = Category(BuiltInCategory.VIDEO, "Video", "ic_videos", type = CategoryType.BUILT_IN, position = 3)
    private val settings = Category(BuiltInCategory.SETTINGS, "Settings", "ic_settings", type = CategoryType.BUILT_IN, position = 0)
    private val all = listOf(settings, videos, games, appStore, custom)

    private fun state(category: Category, block: XMBUiState.() -> XMBUiState = { this }) =
        XMBUiState(categories = all, selectedCategoryIndex = all.indexOf(category)).block()

    // ── Which list is on screen ───────────────────────────────────────────────

    @Test
    fun `the main game root is the games root list`() {
        val s = state(games)
        assertEquals("root:games", s.currentListKey())
        assertEquals(XmbListKind.ROOT, s.currentListKind())
    }

    @Test
    fun `a memory card, all games and favorites are each their own games list`() {
        assertEquals("card:gba", state(games) { copy(selectedPlatformId = "gba") }.currentListKey())
        assertEquals("all_games", state(games) { copy(selectedPlatformId = XMBViewModel.ALL_GAMES_PLATFORM_ID) }.currentListKey())
        assertEquals("favorites", state(games) { copy(selectedPlatformId = XMBViewModel.FAVORITES_PLATFORM_ID) }.currentListKey())
        assertEquals(XmbListKind.GAMES, state(games) { copy(selectedPlatformId = "gba") }.currentListKind())
    }

    @Test
    fun `a custom gaming category has a root and a memory card list of its own`() {
        assertEquals("root:custom_ff_5", state(custom).currentListKey())
        assertEquals(XmbListKind.ROOT, state(custom).currentListKind())
        val card = state(custom) { copy(selectedPlatformId = CATEGORY_CARD_PLATFORM_ID) }
        assertEquals("catcard:custom_ff_5", card.currentListKey())
        assertEquals(XmbListKind.GAMES, card.currentListKind())
    }

    @Test
    fun `an open custom memory card is a games list whichever category it is in`() {
        val inGaming = state(custom) { copy(selectedCollectionId = 7) }
        val inApps = state(appStore) { copy(selectedCollectionId = 8) }
        assertEquals("collection:7", inGaming.currentListKey())
        assertEquals("collection:8", inApps.currentListKey())
        assertEquals(XmbListKind.GAMES, inApps.currentListKind())
    }

    @Test
    fun `an app column's root is an apps list`() {
        assertEquals("root:app_store", state(appStore).currentListKey())
        assertEquals(XmbListKind.APPS, state(appStore).currentListKind())
    }

    @Test
    fun `a media section's apps are an apps list keyed by the section`() {
        val s = state(videos) { copy(videoNav = VideoNav.VideoApps) }
        assertEquals("apps:videos", s.currentListKey())
        assertEquals(XmbListKind.APPS, s.currentListKind())
    }

    @Test
    fun `sections that are not arrangeable have no list`() {
        assertNull(state(settings).currentListKey())
        assertNull(state(videos).currentListKey())
        assertNull(state(settings).currentListKind())
    }

    // ── Column key ────────────────────────────────────────────────────────────

    private val music = Category(BuiltInCategory.MUSIC, "Music", "ic_music", type = CategoryType.BUILT_IN, position = 2)
    private val photos = Category(BuiltInCategory.PHOTO, "Photo", "ic_photos", type = CategoryType.BUILT_IN, position = 1)
    private val social = Category(BuiltInCategory.SOCIAL, "Social", "ic_social", type = CategoryType.BUILT_IN, position = 7)
    private val achievements = Category(BuiltInCategory.ACHIEVEMENTS, "Achievements", "ic_ach", type = CategoryType.BUILT_IN, position = 8)
    private val allWithMedia = all + listOf(music, photos, social, achievements)

    private fun keyOf(category: Category, block: XMBUiState.() -> XMBUiState = { this }) =
        XMBUiState(categories = allWithMedia, selectedCategoryIndex = allWithMedia.indexOf(category)).block().viewCursorKey()

    @Test
    fun `the column key is the category id and the view on screen`() {
        assertEquals("games/root", keyOf(games))
        assertEquals("games/plat_gba", keyOf(games) { copy(selectedPlatformId = "gba") })
        assertEquals("games/col_7", keyOf(games) { copy(selectedCollectionId = 7) })
        assertEquals("custom_ff_5/root", keyOf(custom))
    }

    @Test
    fun `a collection wins over a platform in the column key`() {
        assertEquals("games/col_7", keyOf(games) { copy(selectedPlatformId = "gba", selectedCollectionId = 7) })
    }

    @Test
    fun `each media and system nav kind has its own column key`() {
        assertEquals("music/music_root", keyOf(music))
        assertEquals("music/music_all", keyOf(music) { copy(musicNav = MusicNav.AllMusic) })
        assertEquals("music/music_playlist_3", keyOf(music) { copy(musicNav = MusicNav.Playlist(3, "Mix")) })
        assertEquals("videos/video_library_a", keyOf(videos) { copy(videoNav = VideoNav.Library("a", "Lib")) })
        assertEquals("videos/video_apps", keyOf(videos) { copy(videoNav = VideoNav.VideoApps) })
        assertEquals("photos/photo_albums", keyOf(photos) { copy(photoNav = PhotoNav.Albums) })
        assertEquals("photos/photo_library_p", keyOf(photos) { copy(photoNav = PhotoNav.Library("p", "Pics")) })
        assertEquals("social/social_friends", keyOf(social) { copy(socialNav = SocialNav.Friends) })
        assertEquals("social/social_voiceinvitefriends", keyOf(social) { copy(socialNav = SocialNav.VoiceInviteFriends) })
        assertEquals("achievements/ach_root", keyOf(achievements))
        assertEquals("settings/settings_root", keyOf(settings))
    }

    @Test
    fun `a different nav gives a different column key`() {
        assertNotEquals(keyOf(videos), keyOf(videos) { copy(videoNav = VideoNav.AllVideos) })
        assertNotEquals(keyOf(games), keyOf(games) { copy(selectedPlatformId = "gba") })
        assertNotEquals(keyOf(games), keyOf(custom))
    }

    // ── Row keys ──────────────────────────────────────────────────────────────

    @Test
    fun `every movable row has a key and the fixed rows have none`() {
        assertEquals("row:all_games", XMBItem("a", "All Games", type = XMBItemType.ALL_GAMES).rowKey())
        assertEquals("row:favorites", XMBItem("f", "Favorites", type = XMBItemType.FAVORITES).rowKey())
        assertEquals("row:missing", XMBItem("m", "Missing", type = XMBItemType.MISSING).rowKey())
        assertEquals("row:catcard", XMBItem("c", "Card", type = XMBItemType.CATEGORY_CARD).rowKey())
        assertEquals("collection:4", XMBItem("col_4", "RPG", collectionId = 4, type = XMBItemType.COLLECTION).rowKey())
        assertEquals("card:gba", XMBItem("card_gba", "GBA", platformId = "gba", type = XMBItemType.MEMORY_CARD).rowKey())
        assertEquals("game:9", XMBItem("g9", "Crisis Core", gameId = 9, platformId = "psp").rowKey())
        assertEquals("app:com.vlc", XMBItem("app_com.vlc", "VLC", packageName = "com.vlc", isAndroidApp = true).rowKey())

        assertNull(XMBItem("umd", "FF6", gameId = 3, type = XMBItemType.UMD_SLOT).rowKey())
        assertNull(XMBItem("add", "Add Games", type = XMBItemType.ADD_ACTION).rowKey())
        assertNull(XMBItem("empty", "Nothing here", type = XMBItemType.EMPTY).rowKey())
    }

    // ── Sort tiers ────────────────────────────────────────────────────────────

    @Test
    fun `a games list with no sort of its own follows the global sort`() {
        val s = state(games) { copy(selectedPlatformId = "gba", gameSortMode = XmbSortMode.RECENT_PLAYED) }
        assertEquals(XmbSortMode.RECENT_PLAYED, s.activeGameSort)
        assertEquals("Global: Recently Played", s.sortValueLabel("card:gba", XmbListKind.GAMES))
    }

    @Test
    fun `a list's own sort wins and only on that list`() {
        val overrides = mapOf("card:gba" to XmbSortMode.CUSTOM)
        val gba = state(games) { copy(selectedPlatformId = "gba", listSortOverrides = overrides) }
        val nds = state(games) { copy(selectedPlatformId = "nds", listSortOverrides = overrides) }
        assertEquals(XmbSortMode.CUSTOM, gba.activeGameSort)
        assertEquals("Custom", gba.sortValueLabel("card:gba", XmbListKind.GAMES))
        assertEquals(XmbSortMode.TITLE, nds.activeGameSort)
    }

    @Test
    fun `an app list follows the global app sort, named for apps`() {
        val s = state(appStore) { copy(appSortMode = XmbSortMode.RECENT_PLAYED) }
        assertEquals(XmbSortMode.RECENT_PLAYED, s.activeSortFor("root:app_store", XmbListKind.APPS))
        assertEquals("Global: Recently Used", s.sortValueLabel("root:app_store", XmbListKind.APPS))
    }

    @Test
    fun `an open app card follows the global app sort, not the games one`() {
        val s = state(appStore) {
            copy(
                collections = listOf(GameCollection(id = 8, name = "Tools", categoryId = "app_store")),
                selectedCollectionId = 8,
                gameSortMode = XmbSortMode.TITLE,
                appSortMode = XmbSortMode.RECENT_PLAYED,
            )
        }
        assertEquals(XmbSortMode.RECENT_PLAYED, s.activeGameSort)
        assertEquals(
            "Global: Recently Used",
            s.sortValueLabel("collection:8", s.openListSortKind),
        )
    }

    @Test
    fun `an open gaming card still follows the global games sort`() {
        val s = state(custom) {
            copy(
                collections = listOf(GameCollection(id = 7, name = "RPG", categoryId = "custom_ff_5")),
                selectedCollectionId = 7,
                gameSortMode = XmbSortMode.RECENT_PLAYED,
                appSortMode = XmbSortMode.TITLE,
            )
        }
        assertEquals(XmbSortMode.RECENT_PLAYED, s.activeGameSort)
    }

    @Test
    fun `a gaming root is either in its default order or custom`() {
        assertEquals("Default", state(games).sortValueLabel("root:games", XmbListKind.ROOT))
        val customised = state(games) { copy(listSortOverrides = mapOf("root:games" to XmbSortMode.CUSTOM)) }
        assertEquals("Custom", customised.sortValueLabel("root:games", XmbListKind.ROOT))
        assertTrue(customised.isCustomSorted("root:games", XmbListKind.ROOT))
        assertFalse(state(games).isCustomSorted("root:games", XmbListKind.ROOT))
    }

    // ── Sort picker rows ──────────────────────────────────────────────────────

    @Test
    fun `a games list's sort picker offers the global setting, the three sorts and custom`() {
        val rows = listSortMenuItems(XmbListKind.GAMES, global = XmbSortMode.TITLE, override = null)
        assertEquals(
            listOf("Use Global Setting (Title)", "Title", "Recently Played", "Date Added", "Custom"),
            rows.map { it.label },
        )
        assertEquals(listOf("lsort_default"), rows.filter { it.checked }.map { it.id })
    }

    @Test
    fun `the sort picker checks the list's own sort when it has one`() {
        val rows = listSortMenuItems(XmbListKind.GAMES, global = XmbSortMode.TITLE, override = XmbSortMode.CUSTOM)
        assertEquals(listOf("lsort_CUSTOM"), rows.filter { it.checked }.map { it.id })
    }

    @Test
    fun `an app list's sort picker names its sorts for apps`() {
        val rows = listSortMenuItems(XmbListKind.APPS, global = XmbSortMode.TITLE, override = null)
        assertEquals(
            listOf("Use Global Setting (A–Z)", "A–Z", "Recently Used", "Date Added", "Custom"),
            rows.map { it.label },
        )
    }

    @Test
    fun `a root's sort picker offers only its default order and custom`() {
        val rows = listSortMenuItems(XmbListKind.ROOT, global = XmbSortMode.TITLE, override = null)
        assertEquals(listOf("Default Order", "Custom"), rows.map { it.label })
        assertEquals(listOf("lsort_default"), rows.filter { it.checked }.map { it.id })
    }

    @Test
    fun `the global sort picker never offers custom`() {
        val rows = globalSortMenuItems(XmbListKind.GAMES, current = XmbSortMode.DATE_ADDED)
        assertEquals(listOf("Title", "Recently Played", "Date Added"), rows.map { it.label })
        assertEquals(listOf("gsort_DATE_ADDED"), rows.filter { it.checked }.map { it.id })
    }

    // ── Games in a list ───────────────────────────────────────────────────────

    private fun game(id: Long, title: String, lastPlayedAt: Long? = null) =
        Game(id = id, title = title, platformId = "psx", romPath = "/r/$id", lastPlayedAt = lastPlayedAt)

    private val library = listOf(game(1, "Chrono Cross"), game(2, "Alundra"), game(3, "Bust-A-Move"))

    @Test
    fun `custom sort with nothing stored yet starts in title order`() {
        val shown = gamesForDisplay(library, "", XmbSortMode.CUSTOM, ListState.EMPTY)
        assertEquals(listOf(2L, 3L, 1L), shown.map { it.id })
    }

    @Test
    fun `custom sort applies the stored order`() {
        val stored = ListState(positions = mapOf("game:1" to 0, "game:3" to 1, "game:2" to 2))
        assertEquals(listOf(1L, 3L, 2L), gamesForDisplay(library, "", XmbSortMode.CUSTOM, stored).map { it.id })
    }

    @Test
    fun `title sort ignores a stored custom order so switching back restores it untouched`() {
        val stored = ListState(positions = mapOf("game:1" to 0, "game:3" to 1, "game:2" to 2))
        assertEquals(listOf(2L, 3L, 1L), gamesForDisplay(library, "", XmbSortMode.TITLE, stored).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), gamesForDisplay(library, "", XmbSortMode.CUSTOM, stored).map { it.id })
    }

    @Test
    fun `pinned games come first under every sort`() {
        val pinned = ListState(pinned = setOf("game:1"))
        assertEquals(listOf(1L, 2L, 3L), gamesForDisplay(library, "", XmbSortMode.TITLE, pinned).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), gamesForDisplay(library, "", XmbSortMode.DATE_ADDED, pinned).map { it.id })
    }

    @Test
    fun `date added inside a card is when the game was added there, newest first`() {
        // Alundra (2) joined the card last, Chrono Cross (1) first, Bust-A-Move (3) in between.
        val addedToCard = mapOf(1L to 100L, 3L to 200L, 2L to 300L)
        val shown = gamesForDisplay(library, "", XmbSortMode.DATE_ADDED, ListState.EMPTY, addedToCard)
        assertEquals(listOf(2L, 3L, 1L), shown.map { it.id })
    }

    @Test
    fun `date added with no per-list times falls back to when the game joined the library`() {
        assertEquals(listOf(3L, 2L, 1L), gamesForDisplay(library, "", XmbSortMode.DATE_ADDED).map { it.id })
    }

    @Test
    fun `a game with no recorded time in the card sorts after the ones that have one`() {
        val shown = gamesForDisplay(library, "", XmbSortMode.DATE_ADDED, ListState.EMPTY, mapOf(1L to 500L))
        assertEquals(listOf(1L, 3L, 2L), shown.map { it.id })
    }

    // ── Apps in a list ────────────────────────────────────────────────────────

    private val icon: Drawable = mockk(relaxed = true)

    private fun app(pkg: String, label: String, pinned: Boolean = false, lastUsedAt: Long = 0, installedAt: Long = 0) =
        CategorizedApp(pkg, label, icon, pinned, isEmulator = false, lastUsedAt = lastUsedAt, installedAt = installedAt)

    private val stores = listOf(
        app("com.b", "Beta Store", lastUsedAt = 900, installedAt = 10),
        app("com.a", "Alpha Store", lastUsedAt = 100, installedAt = 30),
        app("com.c", "Gamma Store", lastUsedAt = 500, installedAt = 20),
    )

    @Test
    fun `apps sort by name, by last use and by install date`() {
        assertEquals(listOf("com.a", "com.b", "com.c"), stores.appSorted(XmbSortMode.TITLE, ListState.EMPTY).map { it.packageName })
        assertEquals(listOf("com.b", "com.c", "com.a"), stores.appSorted(XmbSortMode.RECENT_PLAYED, ListState.EMPTY).map { it.packageName })
        assertEquals(listOf("com.a", "com.c", "com.b"), stores.appSorted(XmbSortMode.DATE_ADDED, ListState.EMPTY).map { it.packageName })
    }

    @Test
    fun `apps never used keep their name order under recently used`() {
        val unused = listOf(app("com.b", "Beta"), app("com.a", "Alpha"))
        assertEquals(listOf("com.a", "com.b"), unused.appSorted(XmbSortMode.RECENT_PLAYED, ListState.EMPTY).map { it.packageName })
    }

    @Test
    fun `a pinned app stays on top under every app sort`() {
        val withPin = stores.map { if (it.packageName == "com.c") it.copy(pinned = true) else it }
        assertEquals("com.c", withPin.appSorted(XmbSortMode.TITLE, ListState.EMPTY).first().packageName)
        assertEquals("com.c", withPin.appSorted(XmbSortMode.RECENT_PLAYED, ListState.EMPTY).first().packageName)
    }

    @Test
    fun `an app moved into one of its column's custom cards leaves the column's root`() {
        val loose = stores.notInCards(cardedPackages = setOf("com.b"))
        assertEquals(listOf("com.b"), stores.map { it.packageName } - loose.map { it.packageName }.toSet())
        assertEquals(stores.size - 1, loose.size)
    }

    @Test
    fun `with no carded apps every app stays loose`() {
        assertEquals(stores, stores.notInCards(cardedPackages = emptySet()))
    }

    // ── Roots ─────────────────────────────────────────────────────────────────

    private val umd = XMBItem("umd_slot", "FF6", gameId = 3, type = XMBItemType.UMD_SLOT)
    private val catCard = XMBItem("category_card", "Final Fantasy Memory Card", type = XMBItemType.CATEGORY_CARD)
    private val tactics = XMBItem("col_4", "Tactics", collectionId = 4, type = XMBItemType.COLLECTION, pinned = true)
    private val mainline = XMBItem("col_5", "Mainline", collectionId = 5, type = XMBItemType.COLLECTION)
    private val addGames = XMBItem("add_games", "Add Games", type = XMBItemType.ADD_ACTION)

    @Test
    fun `a custom category root is the umd slot, its memory card, its custom cards, then add games`() {
        val root = assembleRoot(umd, listOf(catCard, tactics, mainline), listOf(addGames), ListState.EMPTY, custom = false)
        assertEquals(listOf("umd_slot", "category_card", "col_4", "col_5", "add_games"), root.map { it.id })
    }

    @Test
    fun `a root holds no game rows other than the umd slot`() {
        val root = assembleRoot(umd, listOf(catCard, tactics, mainline), listOf(addGames), ListState.EMPTY, custom = false)
        assertEquals(listOf("umd_slot"), root.filter { it.gameId != null }.map { it.id })
    }

    @Test
    fun `a root with nothing inserted has no umd row`() {
        val root = assembleRoot(null, listOf(catCard), listOf(addGames), ListState.EMPTY, custom = false)
        assertEquals(listOf("category_card", "add_games"), root.map { it.id })
    }

    @Test
    fun `a custom root applies the stored order between the umd slot and the add row`() {
        val stored = ListState(positions = mapOf("collection:5" to 0, "row:catcard" to 1))
        val root = assembleRoot(umd, listOf(catCard, mainline), listOf(addGames), stored, custom = true)
        assertEquals(listOf("umd_slot", "col_5", "category_card", "add_games"), root.map { it.id })
    }

    @Test
    fun `a custom root keeps pinned rows above the rest`() {
        val stored = ListState(positions = mapOf("collection:5" to 0, "row:catcard" to 1, "collection:4" to 2))
        val root = assembleRoot(null, listOf(catCard, tactics, mainline), emptyList(), stored, custom = true)
        assertEquals(listOf("col_4", "col_5", "category_card"), root.map { it.id })
    }

    @Test
    fun `a stored order is ignored while the root is in its default order`() {
        val stored = ListState(positions = mapOf("collection:5" to 0, "row:catcard" to 1))
        val root = assembleRoot(null, listOf(catCard, mainline), emptyList(), stored, custom = false)
        assertEquals(listOf("category_card", "col_5"), root.map { it.id })
    }

    // ── Move ──────────────────────────────────────────────────────────────────

    private val allGames = XMBItem("all", "All Games", type = XMBItemType.ALL_GAMES)
    private val gba = XMBItem("card_gba", "GBA", platformId = "gba", type = XMBItemType.MEMORY_CARD)

    @Test
    fun `a move swaps the row with its neighbour and the cursor follows`() {
        val moved = moveRow(listOf(allGames, mainline, gba), index = 2, delta = -1)
        assertEquals(listOf("all", "card_gba", "col_5") to 1, moved?.first?.map { it.id } to moved?.second)
    }

    @Test
    fun `a move never crosses the umd slot or an add row`() {
        val rows = listOf(umd, allGames, addGames)
        assertNull(moveRow(rows, index = 1, delta = -1))
        assertNull(moveRow(rows, index = 1, delta = +1))
    }

    @Test
    fun `a move stays inside its pinned or unpinned group`() {
        val rows = listOf(tactics, mainline, gba)   // tactics is pinned
        assertNull(moveRow(rows, index = 1, delta = -1))
        assertNull(moveRow(rows, index = 0, delta = +1))
        assertEquals(listOf("col_4", "card_gba", "col_5"), moveRow(rows, index = 1, delta = +1)?.first?.map { it.id })
    }

    @Test
    fun `a move at either end of the list does nothing`() {
        val rows = listOf(allGames, gba)
        assertNull(moveRow(rows, index = 0, delta = -1))
        assertNull(moveRow(rows, index = 1, delta = +1))
    }

    @Test
    fun `the order a move saves is every movable row in its new place`() {
        val rows = listOf(umd, gba, allGames, mainline, addGames)
        assertEquals(listOf("card:gba", "row:all_games", "collection:5"), rows.orderKeys())
    }

    // ── Arrange rows in a menu ────────────────────────────────────────────────

    @Test
    fun `pin to top reads on or off and move is offered only when the list is custom sorted`() {
        val off = arrangeMenuItems(pinned = false, canMove = false)
        assertEquals(listOf("pin_top" to "Off"), off.map { it.id to it.value })
        assertEquals("Pin to Top", off.single().label)

        val on = arrangeMenuItems(pinned = true, canMove = true)
        assertEquals(listOf("unpin_top" to "On", "move_row" to null), on.map { it.id to it.value })
    }

    @Test
    fun `a row that cannot be pinned still offers move`() {
        assertEquals(listOf("move_row"), arrangeMenuItems(pinned = null, canMove = true).map { it.id })
        assertTrue(arrangeMenuItems(pinned = null, canMove = false).isEmpty())
    }
}
