package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [hasContextMenu] so the idle hint and the real Y/Triangle trigger stay in sync. Each
 * case mirrors a branch of `XMBViewModel.onItemLongPress` / `dispatchGamepadAction(OPEN_CONTEXT_MENU)`.
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
    fun `achievements summary row has a context menu`() {
        val item = XMBItem(id = XMBViewModel.ACH_SUMMARY_ITEM_ID, title = "Player Card")
        assertTrue(item.hasContextMenu(state(BuiltInCategory.ACHIEVEMENTS, item)))
    }
}
