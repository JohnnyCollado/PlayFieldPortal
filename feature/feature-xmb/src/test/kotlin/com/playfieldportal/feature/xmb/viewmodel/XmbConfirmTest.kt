package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The words and weight of each XMB confirm. The copy carries the reassurance the old two-row menus
 * only half said: what stays safe (the file, the games), and what does not come back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XmbConfirmTest {

    @Test
    fun `remove from library names the game, keeps the file and says a scan can bring it back`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveGame(gameId = 7, title = UiText.raw("Parasite Eve II")))

        assertTrue(copy.title.en().contains("Parasite Eve II"))
        assertTrue(copy.title.en().contains("Library"))
        assertTrue(copy.message.en().contains("not deleted"))
        assertTrue(copy.message.en().contains("scan adds it back"))
        assertEquals("Remove", copy.confirmLabel.en())
        assertTrue(copy.destructive)
    }

    @Test
    fun `a missing game is removed permanently, with no way to put it back`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveMissing(gameId = 7, title = UiText.raw("Ape Escape")))

        assertTrue(copy.title.en().contains("Permanently remove"))
        assertTrue(copy.title.en().contains("Ape Escape"))
        assertEquals("Remove permanently", copy.confirmLabel.en())
        assertTrue(copy.destructive)
    }

    @Test
    fun `deleting a custom card says its games stay in the library`() {
        val copy = xmbConfirmCopy(XmbConfirm.DeleteCard(collectionId = 3, title = UiText.raw("RPGs")))

        assertTrue(copy.title.en().contains("RPGs"))
        assertTrue(copy.message.en().contains("games stay in your library"))
        assertEquals("Delete Custom Card", copy.confirmLabel.en())
        assertTrue(copy.destructive)
    }

    @Test
    fun `leaving a category names the cards the game also leaves and is not drawn destructive`() {
        val copy = xmbConfirmCopy(
            XmbConfirm.RemoveFromCategory(
                gameId = 7, categoryId = "gaming", categoryName = "Gaming",
                title = UiText.raw("Ape Escape"), cardNames = listOf("Favourites", "Co-op"),
            ),
        )

        assertTrue(copy.title.en().contains("Gaming"))
        assertTrue(copy.message.en().contains("Ape Escape"))
        assertTrue(copy.message.en().contains("Favourites, Co-op"))
        assertEquals("Remove", copy.confirmLabel.en())
        // Reversible-ish removal with its own prompt (AD-12): opens on Cancel, but not red.
        assertFalse(copy.destructive)
    }

    // ── The rest of the XMB deletes (task 1.2) ────────────────────────────────

    @Test
    fun `removing an Android game keeps the app installed`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveAndroidGame(gameId = 4, title = UiText.raw("Genshin")))

        assertTrue(copy.title.en().contains("Genshin"))
        assertTrue(copy.title.en().contains("Library"))
        assertTrue(copy.message.en().contains("stays installed"))
        assertEquals("Remove", copy.confirmLabel.en())
        assertTrue(copy.destructive)
    }

    // Survey U27/U28 (docs/audits/codebase-survey-2026-10-22.md): app rows and the Android card
    // were told about files and scans they do not have.
    @Test
    fun `removing an app row says the app stays installed and mentions no file or scan`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveAppRow(gameId = 7, title = UiText.raw("Chrome")))

        assertTrue(copy.message.en().contains("stays installed"))
        assertFalse(copy.message.en().contains("file"))
        assertFalse(copy.message.en().contains("scan"))
        assertEquals("Remove", copy.confirmLabel.en())
    }

    @Test
    fun `removing the Android card says its apps stay installed, not that ROM files stay`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveCard(platformId = "android", title = UiText.raw("Android")))

        assertTrue(copy.message.en().contains("apps stay installed"))
        assertFalse(copy.message.en().contains("ROM"))
        assertFalse(copy.message.en().contains("scan"))
    }

    @Test
    fun `the app row's Remove from Library confirms as an app row`() {
        val menu = XMBContextMenu(title = UiText.raw("Chrome"), items = emptyList(), gameId = 4L)
        assertEquals(XmbConfirm.RemoveAppRow(4L, UiText.raw("Chrome")), confirmFor(menu, "remove_app_row"))
    }

    @Test
    fun `removing a console card says its games leave but the ROM files stay on disk`() {
        val copy = xmbConfirmCopy(XmbConfirm.RemoveCard(platformId = "psp", title = UiText.raw("PSP")))

        assertTrue(copy.title.en().contains("PSP"))
        assertTrue(copy.message.en().contains("games leave your library"))
        assertTrue(copy.message.en().contains("ROM files stay on disk"))
        assertEquals("Remove Card", copy.confirmLabel.en())
        assertTrue(copy.destructive)
    }

    @Test
    fun `removing a track, video or photo keeps the file and says a scan can bring it back`() {
        val copies = listOf(
            xmbConfirmCopy(XmbConfirm.RemoveTrack(trackId = "t1", title = UiText.raw("Song"))),
            xmbConfirmCopy(XmbConfirm.RemoveVideo(videoId = "v1", title = UiText.raw("Clip"))),
            xmbConfirmCopy(XmbConfirm.RemovePhoto(photoId = "p1", title = UiText.raw("Beach"))),
        )

        copies.forEach { copy ->
            assertTrue(copy.title.en().contains("Library"))
            assertTrue(copy.message.en().contains("not deleted"))
            assertTrue(copy.message.en().contains("scan adds it back"))
            assertEquals("Remove", copy.confirmLabel.en())
            assertTrue(copy.destructive)
        }
        assertTrue(xmbConfirmCopy(XmbConfirm.RemoveTrack("t1", UiText.raw("Song"))).title.en().contains("Song"))
        assertTrue(xmbConfirmCopy(XmbConfirm.RemoveVideo("v1", UiText.raw("Clip"))).title.en().contains("Clip"))
        assertTrue(xmbConfirmCopy(XmbConfirm.RemovePhoto("p1", UiText.raw("Beach"))).title.en().contains("Beach"))
    }

    @Test
    fun `deleting a playlist says its items stay in the library`() {
        val music = xmbConfirmCopy(XmbConfirm.DeleteMusicPlaylist(playlistId = 2, title = UiText.raw("Road trip")))
        val video = xmbConfirmCopy(XmbConfirm.DeleteVideoPlaylist(playlistId = 3, title = UiText.raw("Watch later")))

        assertTrue(music.title.en().contains("Road trip"))
        assertTrue(music.message.en().contains("tracks stay in your library"))
        assertTrue(video.title.en().contains("Watch later"))
        assertTrue(video.message.en().contains("videos stay in your library"))
        listOf(music, video).forEach {
            assertEquals("Delete Playlist", it.confirmLabel.en())
            assertTrue(it.destructive)
        }
    }

    @Test
    fun `clearing all notifications leaves running tasks alone`() {
        val copy = xmbConfirmCopy(XmbConfirm.ClearNotifications)

        assertTrue(copy.title.en().contains("notifications"))
        assertTrue(copy.message.en().contains("Running tasks"))
        assertEquals("Clear All", copy.confirmLabel.en())
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
            packageName = "com.example.genshin", isAndroidApp = true, isRealGame = true,
        )

        val romConfirms = assertEveryDestructiveRowConfirms(
            XMBContextMenu(UiText.raw(rom.title), gameItems(rom), gameId = 1L),
        )
        val androidConfirms = assertEveryDestructiveRowConfirms(
            XMBContextMenu(UiText.raw(android.title), gameItems(android), gameId = 2L, packageName = android.packageName),
        )
        val missingConfirms = assertEveryDestructiveRowConfirms(
            XMBContextMenu(UiText.raw(rom.title), gameItems(rom, inMissingBucket = true), gameId = 1L),
        )

        assertEquals(XmbConfirm.RemoveGame(1L, UiText.raw("Crisis Core")), romConfirms["remove_game"])
        assertEquals(XmbConfirm.RemoveAndroidGame(2L, UiText.raw("Genshin")), androidConfirms["remove_app"])
        assertEquals(XmbConfirm.RemoveMissing(1L, UiText.raw("Crisis Core")), missingConfirms["remove_missing"])
    }

    @Test
    fun `Remove Card on a console card confirms and names the card`() {
        val menu = XMBContextMenu(
            title = UiText.raw("PSP"),
            items = platformCardMenuItems("psp", pinned = false, iconDisplayLabel = UiText.raw("Default")),
            platformId = "psp",
        )

        val confirms = assertEveryDestructiveRowConfirms(menu)

        assertEquals(XmbConfirm.RemoveCard("psp", UiText.raw("PSP")), confirms["remove"])
    }

    @Test
    fun `Delete Custom Card confirms`() {
        val menu = XMBContextMenu(
            title = UiText.raw("RPGs"),
            items = customCardMenuItems(pinned = false, canMoveToCategory = true, sortValue = UiText.raw("Custom"), canMove = false, byTouch = true),
            collectionRowId = 3L,
        )

        val confirms = assertEveryDestructiveRowConfirms(menu)

        assertEquals(XmbConfirm.DeleteCard(3L, UiText.raw("RPGs")), confirms["delete_collection"])
    }

    @Test
    fun `the media rows that remove or delete confirm`() {
        fun row(id: String) = XMBContextMenuItem(id, UiText.raw(id), isDestructive = true)

        assertEquals(
            XmbConfirm.RemoveTrack("t1", UiText.raw("Song")),
            confirmFor(XMBContextMenu(UiText.raw("Song"), listOf(row("remove_track")), musicTrackId = "t1"), "remove_track"),
        )
        assertEquals(
            XmbConfirm.RemoveVideo("v1", UiText.raw("Clip")),
            confirmFor(XMBContextMenu(UiText.raw("Clip"), listOf(row("video_remove")), videoFileId = "v1"), "video_remove"),
        )
        assertEquals(
            XmbConfirm.RemovePhoto("p1", UiText.raw("Beach")),
            confirmFor(XMBContextMenu(UiText.raw("Beach"), listOf(row("photo_remove")), photoFileId = "p1"), "photo_remove"),
        )
        assertEquals(
            XmbConfirm.DeleteMusicPlaylist(2L, UiText.raw("Road trip")),
            confirmFor(XMBContextMenu(UiText.raw("Road trip"), listOf(row("delete_playlist")), playlistId = 2L), "delete_playlist"),
        )
        assertEquals(
            XmbConfirm.DeleteVideoPlaylist(3L, UiText.raw("Watch later")),
            confirmFor(
                XMBContextMenu(UiText.raw("Watch later"), listOf(row("delete_video_playlist")), videoPlaylistId = 3L),
                "delete_video_playlist",
            ),
        )
    }

    @Test
    fun `Clear All in the notification menu confirms, Mark All Read and Clear Read do not`() {
        val menu = XMBContextMenu(
            title = UiText.raw("Notifications"),
            items = listOf(
                XMBContextMenuItem(NotificationMenuIds.MARK_ALL_READ, UiText.raw("Mark All Read")),
                XMBContextMenuItem(NotificationMenuIds.CLEAR_READ, UiText.raw("Clear Read")),
                XMBContextMenuItem(NotificationMenuIds.CLEAR_ALL, UiText.raw("Clear All"), isDestructive = true),
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
            title = UiText.raw("Chrome"),
            items = listOf(XMBContextMenuItem("remove", UiText.raw("Remove From Category"))),
            packageName = "com.android.chrome",
            categoryContext = "apps",
        )

        assertNull(confirmFor(menu, "remove"))
    }

    @Test
    fun `uninstalling an app confirms, naming the app, and opens on a red Uninstall`() {
        val menu = XMBContextMenu(
            title = UiText.raw("Chrome"),
            items = listOf(XMBContextMenuItem("app_uninstall", UiText.raw("Uninstall"), isDestructive = true)),
            packageName = "com.android.chrome",
            categoryContext = "apps",
        )

        val confirm = confirmFor(menu, "app_uninstall")
        assertEquals(XmbConfirm.Uninstall("com.android.chrome", UiText.raw("Chrome")), confirm)
        val copy = xmbConfirmCopy(confirm!!)
        assertTrue(copy.title.en().contains("Chrome"))
        assertEquals("Uninstall", copy.confirmLabel.en())
        assertTrue(copy.destructive)
        assertNull(confirmFor(menu, "app_info"))
    }
}
