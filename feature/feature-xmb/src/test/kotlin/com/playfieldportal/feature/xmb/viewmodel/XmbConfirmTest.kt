package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words and weight of each XMB confirm. The copy carries the reassurance the old two-row menus
 * only half said: what stays safe (the file, the games), and what does not come back.
 */
class XmbConfirmTest {

    @Test
    fun `remove from library names the game, keeps the file and says a scan can bring it back`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveGame(gameId = 7, title = "Parasite Eve II"))

        assertTrue(copy.title.contains("Parasite Eve II"))
        assertTrue(copy.title.contains("Library"))
        assertTrue(copy.message.contains("not deleted"))
        assertTrue(copy.message.contains("scan adds it back"))
        assertEquals("Remove", copy.confirmLabel)
        assertTrue(copy.destructive)
    }

    @Test
    fun `a missing game is removed permanently, with no way to put it back`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveMissing(gameId = 7, title = "Ape Escape"))

        assertTrue(copy.title.contains("Permanently remove"))
        assertTrue(copy.title.contains("Ape Escape"))
        assertEquals("Remove permanently", copy.confirmLabel)
        assertTrue(copy.destructive)
    }

    @Test
    fun `deleting a custom card says its games stay in the library`() {
        val copy = xmbConfirmCopy(XmbConfirm.DeleteCard(collectionId = 3, title = "RPGs"))

        assertTrue(copy.title.contains("RPGs"))
        assertTrue(copy.message.contains("games stay in your library"))
        assertEquals("Delete Custom Card", copy.confirmLabel)
        assertTrue(copy.destructive)
    }

    @Test
    fun `leaving a category names the cards the game also leaves and is not drawn destructive`() {
        val copy = xmbConfirmCopy(
            XmbConfirm.RemoveFromCategory(
                gameId = 7, categoryId = "gaming", categoryName = "Gaming",
                title = "Ape Escape", cardNames = listOf("Favourites", "Co-op"),
            ),
        )

        assertTrue(copy.title.contains("Gaming"))
        assertTrue(copy.message.contains("Ape Escape"))
        assertTrue(copy.message.contains("Favourites, Co-op"))
        assertEquals("Remove", copy.confirmLabel)
        // Reversible-ish removal with its own prompt (AD-12): opens on Cancel, but not red.
        assertFalse(copy.destructive)
    }

    // ── The rest of the XMB deletes (task 1.2) ────────────────────────────────

    @Test
    fun `removing an Android game keeps the app installed`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveAndroidGame(gameId = 4, title = "Genshin"))

        assertTrue(copy.title.contains("Genshin"))
        assertTrue(copy.title.contains("Library"))
        assertTrue(copy.message.contains("stays installed"))
        assertEquals("Remove", copy.confirmLabel)
        assertTrue(copy.destructive)
    }

    @Test
    fun `removing a console card says its games leave but the ROM files stay on disk`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveCard(platformId = "psp", title = "PSP"))

        assertTrue(copy.title.contains("PSP"))
        assertTrue(copy.message.contains("games leave your library"))
        assertTrue(copy.message.contains("ROM files stay on disk"))
        assertEquals("Remove Card", copy.confirmLabel)
        assertTrue(copy.destructive)
    }

    @Test
    fun `removing a track, video or photo keeps the file and says a scan can bring it back`() {
        val copies = listOf(
            xmbConfirmCopy(XmbConfirm.RemoveTrack(trackId = "t1", title = "Song")),
            xmbConfirmCopy(XmbConfirm.RemoveVideo(videoId = "v1", title = "Clip")),
            xmbConfirmCopy(XmbConfirm.RemovePhoto(photoId = "p1", title = "Beach")),
        )

        copies.forEach { copy ->
            assertTrue(copy.title.contains("Library"))
            assertTrue(copy.message.contains("not deleted"))
            assertTrue(copy.message.contains("scan adds it back"))
            assertEquals("Remove", copy.confirmLabel)
            assertTrue(copy.destructive)
        }
        assertTrue(xmbConfirmCopy(XmbConfirm.RemoveTrack("t1", "Song")).title.contains("Song"))
        assertTrue(xmbConfirmCopy(XmbConfirm.RemoveVideo("v1", "Clip")).title.contains("Clip"))
        assertTrue(xmbConfirmCopy(XmbConfirm.RemovePhoto("p1", "Beach")).title.contains("Beach"))
    }

    @Test
    fun `deleting a playlist says its items stay in the library`() {
        val music = xmbConfirmCopy(XmbConfirm.DeleteMusicPlaylist(playlistId = 2, title = "Road trip"))
        val video = xmbConfirmCopy(XmbConfirm.DeleteVideoPlaylist(playlistId = 3, title = "Watch later"))

        assertTrue(music.title.contains("Road trip"))
        assertTrue(music.message.contains("tracks stay in your library"))
        assertTrue(video.title.contains("Watch later"))
        assertTrue(video.message.contains("videos stay in your library"))
        listOf(music, video).forEach {
            assertEquals("Delete Playlist", it.confirmLabel)
            assertTrue(it.destructive)
        }
    }

    @Test
    fun `clearing all notifications leaves running tasks alone`() {
        val copy = xmbConfirmCopy(XmbConfirm.ClearNotifications)

        assertTrue(copy.title.contains("notifications"))
        assertTrue(copy.message.contains("Running tasks"))
        assertEquals("Clear All", copy.confirmLabel)
        assertTrue(copy.destructive)
    }

    // ── confirmFor: no destructive row is ever one press ──────────────────────

    private val games = Category(
        BuiltInCategory.GAMES, "Games", "games",
        type = CategoryType.BUILT_IN, position = 0, isGamingCategory = true,
    )

    private fun gameItems(item: XMBItem, inMissingBucket: Boolean = false, inCollection: Boolean = false) =
        gameContextMenuItems(
            item = item,
            discCount = 0,
            inCollection = inCollection,
            currentCategory = games,
            categories = listOf(games),
            inMissingBucket = inMissingBucket,
            hideLabel = null,
        )

    /** Every destructive row of [menu] must raise a confirm; returns them for kind checks. */
    private fun assertEveryDestructiveRowConfirms(menu: XMBContextMenu): Map<String, XmbConfirm?> {
        val destructive = menu.items.filter { it.isDestructive }
        assertTrue("no destructive rows in ${menu.title}", destructive.isNotEmpty())
        return destructive.associate { row ->
            val confirm = confirmFor(menu, row.id)
            assertNotNull("${row.id} has no confirm", confirm)
            row.id to confirm
        }
    }

    @Test
    fun `every destructive row of the game menu confirms`() {
        val rom = XMBItem(id = "g1", title = "Crisis Core", gameId = 1L, platformId = "psp")
        val android = XMBItem(
            id = "a1", title = "Genshin", gameId = 2L, platformId = "android",
            packageName = "com.example.genshin", isAndroidApp = true,
        )

        val romConfirms = assertEveryDestructiveRowConfirms(
            XMBContextMenu(rom.title, gameItems(rom), gameId = 1L),
        )
        val androidConfirms = assertEveryDestructiveRowConfirms(
            XMBContextMenu(android.title, gameItems(android), gameId = 2L, packageName = android.packageName),
        )
        val missingConfirms = assertEveryDestructiveRowConfirms(
            XMBContextMenu(rom.title, gameItems(rom, inMissingBucket = true), gameId = 1L),
        )

        assertEquals(XmbConfirm.RemoveGame(1L, "Crisis Core"), romConfirms["remove_game"])
        assertEquals(XmbConfirm.RemoveAndroidGame(2L, "Genshin"), androidConfirms["remove_app"])
        assertEquals(XmbConfirm.RemoveMissing(1L, "Crisis Core"), missingConfirms["remove_missing"])
    }

    @Test
    fun `Remove Card on a console card confirms and names the card`() {
        val menu = XMBContextMenu(
            title = "PSP",
            items = platformCardMenuItems("psp", pinned = false, iconDisplayLabel = "Default"),
            platformId = "psp",
        )

        val confirms = assertEveryDestructiveRowConfirms(menu)

        assertEquals(XmbConfirm.RemoveCard("psp", "PSP"), confirms["remove"])
    }

    @Test
    fun `Delete Custom Card confirms`() {
        val menu = XMBContextMenu(
            title = "RPGs",
            items = customCardMenuItems(pinned = false, canMoveToCategory = true, sortValue = "Custom", canMove = false, byTouch = true),
            collectionRowId = 3L,
        )

        val confirms = assertEveryDestructiveRowConfirms(menu)

        assertEquals(XmbConfirm.DeleteCard(3L, "RPGs"), confirms["delete_collection"])
    }

    @Test
    fun `the media rows that remove or delete confirm`() {
        fun row(id: String) = XMBContextMenuItem(id, id, isDestructive = true)

        assertEquals(
            XmbConfirm.RemoveTrack("t1", "Song"),
            confirmFor(XMBContextMenu("Song", listOf(row("remove_track")), musicTrackId = "t1"), "remove_track"),
        )
        assertEquals(
            XmbConfirm.RemoveVideo("v1", "Clip"),
            confirmFor(XMBContextMenu("Clip", listOf(row("video_remove")), videoFileId = "v1"), "video_remove"),
        )
        assertEquals(
            XmbConfirm.RemovePhoto("p1", "Beach"),
            confirmFor(XMBContextMenu("Beach", listOf(row("photo_remove")), photoFileId = "p1"), "photo_remove"),
        )
        assertEquals(
            XmbConfirm.DeleteMusicPlaylist(2L, "Road trip"),
            confirmFor(XMBContextMenu("Road trip", listOf(row("delete_playlist")), playlistId = 2L), "delete_playlist"),
        )
        assertEquals(
            XmbConfirm.DeleteVideoPlaylist(3L, "Watch later"),
            confirmFor(
                XMBContextMenu("Watch later", listOf(row("delete_video_playlist")), videoPlaylistId = 3L),
                "delete_video_playlist",
            ),
        )
    }

    @Test
    fun `Clear All in the notification menu confirms, Mark All Read and Clear Read do not`() {
        val menu = XMBContextMenu(
            title = "Notifications",
            items = listOf(
                XMBContextMenuItem(NotificationMenuIds.MARK_ALL_READ, "Mark All Read"),
                XMBContextMenuItem(NotificationMenuIds.CLEAR_READ, "Clear Read"),
                XMBContextMenuItem(NotificationMenuIds.CLEAR_ALL, "Clear All", isDestructive = true),
            ),
            notificationListMenu = true,
        )

        assertEquals(XmbConfirm.ClearNotifications, confirmFor(menu, NotificationMenuIds.CLEAR_ALL))
        assertNull(confirmFor(menu, NotificationMenuIds.MARK_ALL_READ))
        assertNull(confirmFor(menu, NotificationMenuIds.CLEAR_READ))
    }

    @Test
    fun `an app's Remove from Category is not mistaken for Remove Card`() {
        val menu = XMBContextMenu(
            title = "Chrome",
            items = listOf(XMBContextMenuItem("remove", "Remove From Category")),
            packageName = "com.android.chrome",
            categoryContext = "apps",
        )

        assertNull(confirmFor(menu, "remove"))
    }

    @Test
    fun `uninstalling an app confirms, naming the app, and opens on a red Uninstall`() {
        val menu = XMBContextMenu(
            title = "Chrome",
            items = listOf(XMBContextMenuItem("app_uninstall", "Uninstall", isDestructive = true)),
            packageName = "com.android.chrome",
            categoryContext = "apps",
        )

        val confirm = confirmFor(menu, "app_uninstall")
        assertEquals(XmbConfirm.Uninstall("com.android.chrome", "Chrome"), confirm)
        val copy = xmbConfirmCopy(confirm!!)
        assertTrue(copy.title.contains("Chrome"))
        assertEquals("Uninstall", copy.confirmLabel)
        assertTrue(copy.destructive)
        assertNull(confirmFor(menu, "app_info"))
    }
}
