package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [contextMenuTarget], the one resolver Triangle and long-press both dispatch on, and
 * [hasContextMenu], which is exactly "the resolver found a target". Pins that a row resolves to the
 * same kind of menu whichever way it is opened (only `byTouch` differs), that the Shiba Coins hub rows
 * and mark mode are reachable by long-press, and which rows have a menu at all.
 */
class ContextMenuPredicateTest {

    private fun state(
        categoryId: String,
        item: XMBItem,
        musicNav: MusicNav = MusicNav.Root,
        videoNav: VideoNav = VideoNav.Root,
        photoNav: PhotoNav = PhotoNav.Root,
    ) = XMBUiState(
        categories = listOf(Category(categoryId, categoryId, categoryId, type = CategoryType.BUILT_IN, position = 0)),
        selectedCategoryIndex = 0,
        currentItems = listOf(item),
        selectedItemIndex = 0,
        musicNav = musicNav,
        videoNav = videoNav,
        photoNav = photoNav,
    )

    @Test
    fun `game row has a context menu`() {
        val item = XMBItem(id = "g1", title = "Crisis Core", gameId = 1L)
        assertTrue(item.hasContextMenu(state(BuiltInCategory.GAMES, item)))
    }

    @Test
    fun `platform row has a context menu`() {
        val item = XMBItem(id = "psp", title = "PSP", platformId = "psp")
        assertTrue(item.hasContextMenu(state(BuiltInCategory.GAMES, item)))
    }

    @Test
    fun `collection row has a context menu`() {
        val item = XMBItem(id = "c1", title = "RPGs", collectionId = 1L, type = XMBItemType.COLLECTION)
        assertTrue(item.hasContextMenu(state(BuiltInCategory.GAMES, item)))
    }

    @Test
    fun `all-games folder has a context menu`() {
        val item = XMBItem(id = "all", title = "All Games", type = XMBItemType.ALL_GAMES)
        assertTrue(item.hasContextMenu(state(BuiltInCategory.GAMES, item)))
    }

    @Test
    fun `app row has a context menu`() {
        val item = XMBItem(id = "app", title = "Spotify", packageName = "com.spotify.music")
        assertTrue(item.hasContextMenu(state(BuiltInCategory.GAMES, item)))
    }

    @Test
    fun `plain standard row has no context menu`() {
        val item = XMBItem(id = "x", title = "Nothing", type = XMBItemType.STANDARD)
        assertFalse(item.hasContextMenu(state(BuiltInCategory.GAMES, item)))
    }

    @Test
    fun `music track has a context menu`() {
        val item = XMBItem(id = "tr1", title = "Track", type = XMBItemType.MUSIC_TRACK)
        assertTrue(item.hasContextMenu(state(BuiltInCategory.MUSIC, item)))
    }

    @Test
    fun `now playing row has a context menu`() {
        val item = XMBItem(id = XMBViewModel.NOW_PLAYING_ITEM_ID, title = "Now Playing")
        assertTrue(item.hasContextMenu(state(BuiltInCategory.MUSIC, item)))
    }

    @Test
    fun `video file has a context menu`() {
        val item = XMBItem(id = "vid_1", title = "Clip", type = XMBItemType.VIDEO_FILE)
        assertTrue(item.hasContextMenu(state(BuiltInCategory.VIDEO, item)))
    }

    @Test
    fun `video file with wrong id prefix has no context menu`() {
        val item = XMBItem(id = "bad_1", title = "Clip", type = XMBItemType.VIDEO_FILE)
        assertFalse(item.hasContextMenu(state(BuiltInCategory.VIDEO, item)))
    }

    @Test
    fun `photo file has a context menu`() {
        val item = XMBItem(id = "pho_1", title = "Pic", type = XMBItemType.PHOTO_FILE)
        assertTrue(item.hasContextMenu(state(BuiltInCategory.PHOTO, item)))
    }

    @Test
    fun `music track in the wrong category has no context menu`() {
        val item = XMBItem(id = "tr1", title = "Track", type = XMBItemType.MUSIC_TRACK)
        assertFalse(item.hasContextMenu(state(BuiltInCategory.GAMES, item)))
    }

    @Test
    fun `the Music memory card has a context menu`() {
        val item = XMBItem(id = XMBViewModel.ALL_MUSIC_ITEM_ID, title = "Music", type = XMBItemType.MEMORY_CARD)
        assertTrue(item.hasContextMenu(state(BuiltInCategory.MUSIC, item)))
    }

    @Test
    fun `the Videos memory card has a context menu`() {
        val item = XMBItem(id = XMBViewModel.ALL_VIDEOS_ITEM_ID, title = "Videos", type = XMBItemType.MEMORY_CARD)
        assertTrue(item.hasContextMenu(state(BuiltInCategory.VIDEO, item)))
    }

    @Test
    fun `the Photos memory card has a context menu`() {
        val item = XMBItem(id = XMBViewModel.ALL_PHOTOS_ITEM_ID, title = "Photos", type = XMBItemType.MEMORY_CARD)
        assertTrue(item.hasContextMenu(state(BuiltInCategory.PHOTO, item)))
    }

    @Test
    fun `a section memory card in the wrong category has no context menu`() {
        val item = XMBItem(id = XMBViewModel.ALL_PHOTOS_ITEM_ID, title = "Photos", type = XMBItemType.MEMORY_CARD)
        assertFalse(item.hasContextMenu(state(BuiltInCategory.VIDEO, item)))
    }

    @Test
    fun `achievements summary row has a context menu`() {
        val item = XMBItem(id = XMBViewModel.ACH_SUMMARY_ITEM_ID, title = "Player Card")
        assertTrue(item.hasContextMenu(state(BuiltInCategory.ACHIEVEMENTS, item)))
    }

    private fun assertSameKind(kind: ContextMenuKind?, item: XMBItem, state: XMBUiState) {
        val byPad = contextMenuTarget(item, state, byTouch = false)
        val byTouch = contextMenuTarget(item, state, byTouch = true)
        assertEquals(kind, byPad?.kind)
        assertEquals(kind, byTouch?.kind)
        assertEquals(false, byPad?.byTouch ?: false)
        assertEquals(kind != null, byTouch?.byTouch ?: false)
        assertEquals(kind != null, item.hasContextMenu(state))
    }

    @Test
    fun `every covered row resolves to the same menu by pad and by touch`() {
        val cases = listOf(
            Triple(BuiltInCategory.GAMES, XMBItem(id = "g1", title = "G", gameId = 1L), ContextMenuKind.GAME),
            Triple(BuiltInCategory.GAMES, XMBItem(id = "psp", title = "PSP", platformId = "psp"), ContextMenuKind.PLATFORM),
            Triple(
                BuiltInCategory.GAMES,
                XMBItem(id = "c1", title = "C", collectionId = 1L, type = XMBItemType.COLLECTION),
                ContextMenuKind.COLLECTION,
            ),
            Triple(BuiltInCategory.GAMES, XMBItem(id = "all", title = "All", type = XMBItemType.ALL_GAMES), ContextMenuKind.ALL_GAMES),
            Triple(BuiltInCategory.GAMES, XMBItem(id = "cc", title = "Card", type = XMBItemType.CATEGORY_CARD), ContextMenuKind.ROOT_ROW),
            Triple(BuiltInCategory.GAMES, XMBItem(id = "fav", title = "Fav", type = XMBItemType.FAVORITES), ContextMenuKind.ROOT_ROW),
            Triple(BuiltInCategory.GAMES, XMBItem(id = "soc", title = "Soc", type = XMBItemType.SOCIAL_ACCOUNT), ContextMenuKind.SOCIAL_ACCOUNT),
            Triple(BuiltInCategory.GAMES, XMBItem(id = "app", title = "App", packageName = "a.b"), ContextMenuKind.APP),
            Triple(BuiltInCategory.GAMES, XMBItem(id = "x", title = "Nothing", type = XMBItemType.STANDARD), null),
            Triple(BuiltInCategory.MUSIC, XMBItem(id = "tr1", title = "T", type = XMBItemType.MUSIC_TRACK), ContextMenuKind.MUSIC),
            Triple(BuiltInCategory.VIDEO, XMBItem(id = "vid_1", title = "V", type = XMBItemType.VIDEO_FILE), ContextMenuKind.VIDEO),
            Triple(BuiltInCategory.PHOTO, XMBItem(id = "pho_1", title = "P", type = XMBItemType.PHOTO_FILE), ContextMenuKind.PHOTO),
        )
        for ((category, item, kind) in cases) assertSameKind(kind, item, state(category, item))
    }

    @Test
    fun `the Shiba Coins hub rows resolve for long-press as well as Triangle`() {
        for (id in listOf(XMBViewModel.ACH_ALL_ITEM_ID, XMBViewModel.ACH_SUMMARY_ITEM_ID, XMBViewModel.ACH_UNTRACKED_ITEM_ID)) {
            val item = XMBItem(id = id, title = id)
            assertSameKind(ContextMenuKind.ACHIEVEMENTS, item, state(BuiltInCategory.ACHIEVEMENTS, item))
        }
    }

    @Test
    fun `an achievements row that is not a hub row has no menu`() {
        val item = XMBItem(id = "ach_other", title = "Other")
        assertSameKind(null, item, state(BuiltInCategory.ACHIEVEMENTS, item))
    }

    @Test
    fun `mark mode resolves to the marked-games picker for any row, and for no row`() {
        val game = XMBItem(id = "g1", title = "G", gameId = 1L)
        val plain = XMBItem(id = "x", title = "Nothing", type = XMBItemType.STANDARD)
        for (item in listOf(game, plain)) {
            val marking = state(BuiltInCategory.GAMES, item).copy(markMode = true)
            assertSameKind(ContextMenuKind.MARK_MODE, item, marking)
        }
        val marking = state(BuiltInCategory.GAMES, game).copy(markMode = true)
        assertEquals(ContextMenuKind.MARK_MODE, contextMenuTarget(null, marking, byTouch = true)?.kind)
    }

    @Test
    fun `no focused row and no mark mode resolves to nothing`() {
        val item = XMBItem(id = "g1", title = "G", gameId = 1L)
        assertNull(contextMenuTarget(null, state(BuiltInCategory.GAMES, item), byTouch = true))
    }

    @Test
    fun `a long-press on a category icon opens that category's menu`() {
        val item = XMBItem(id = "g1", title = "G", gameId = 1L)
        val state = state(BuiltInCategory.GAMES, item).copy(showBootSequence = false)
        val menu = categoryLongPressMenu(state, index = 0)
        assertEquals(BuiltInCategory.GAMES, menu?.categoryMenuId)
        assertNull(categoryLongPressMenu(state, index = 5))
    }

    @Test
    fun `Triangle on the bar still acts on the row, never on the category`() {
        val item = XMBItem(id = "g1", title = "G", gameId = 1L)
        val state = state(BuiltInCategory.GAMES, item).copy(showBootSequence = false)
        assertEquals(ContextMenuKind.GAME, contextMenuTarget(item, state, byTouch = false)?.kind)
        assertNull(contextMenuTarget(null, state, byTouch = false))
    }

    @Test
    fun `no category menu opens while something is already over the bar or a category is being moved`() {
        val item = XMBItem(id = "g1", title = "G", gameId = 1L)
        val state = state(BuiltInCategory.GAMES, item).copy(showBootSequence = false)
        assertNull(categoryLongPressMenu(state.copy(activeSettingsScreen = "settings_display"), index = 0))
        assertNull(categoryLongPressMenu(state.copy(categoryMoveSession = CategoryMoveSession(state.categories, 0)), index = 0))
    }
}
