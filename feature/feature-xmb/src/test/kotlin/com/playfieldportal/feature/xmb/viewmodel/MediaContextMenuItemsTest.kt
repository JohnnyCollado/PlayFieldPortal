package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The XMB media rows' menus. A controller-opened menu never repeats the press that opened the row
 * (Play / Open / Resume / Details); a long-press one keeps exactly one such row. Ids stay put, the
 * glossary wording and Favorite On/Off are what changed.
 */
class MediaContextMenuItemsTest {

    private val repeatIds = setOf(
        "play", "open_playlist", "open_video_playlist", "video_play", "video_resume", "video_details",
        "video_lib_open", "photo_open", "photo_lib_open",
    )

    private fun List<XMBContextMenuItem>.repeats() = filter { it.id in repeatIds }

    private fun List<XMBContextMenuItem>.ids() = map { it.id }

    // ── Controller-opened menus carry no repeat row ───────────────────────────

    @Test
    fun `controller-opened menus have no Play, Open, Resume or Details row`() {
        val menus = listOf(
            musicTrackMenuItems(inPlaylist = true, byTouch = false),
            musicPlaylistMenuItems(byTouch = false),
            videoFileMenuItems(isFavorite = false, inPlaylist = true, byTouch = false),
            videoPlaylistMenuItems(byTouch = false),
            videoLibraryMenuItems(byTouch = false),
            photoFileMenuItems(byTouch = false),
            photoAlbumMenuItems(byTouch = false),
        )
        menus.forEach { assertEquals(emptyList<XMBContextMenuItem>(), it.repeats()) }
    }

    @Test
    fun `long-press menus keep exactly one repeat row, first`() {
        val menus = listOf(
            musicTrackMenuItems(inPlaylist = true, byTouch = true) to "play",
            musicPlaylistMenuItems(byTouch = true) to "open_playlist",
            videoFileMenuItems(isFavorite = true, inPlaylist = true, byTouch = true) to "video_details",
            videoPlaylistMenuItems(byTouch = true) to "open_video_playlist",
            videoLibraryMenuItems(byTouch = true) to "video_lib_open",
            photoFileMenuItems(byTouch = true) to "photo_open",
            photoAlbumMenuItems(byTouch = true) to "photo_lib_open",
        )
        menus.forEach { (items, id) ->
            assertEquals(listOf(id), items.repeats().ids())
            assertEquals(id, items.first().id)
        }
    }

    @Test
    fun `a video has one View Details row for touch, never Play, Resume or Details`() {
        val items = videoFileMenuItems(isFavorite = false, inPlaylist = false, byTouch = true)
        assertEquals("View Details", items.single { it.id == "video_details" }.label)
        assertTrue(items.none { it.id == "video_play" || it.id == "video_resume" })
        assertTrue(items.none { it.label == "Play" || it.label == "Resume" || it.label == "Details" })
    }

    // ── Wording ────────────────────────────────────────────────────────────────

    @Test
    fun `removals read Remove from Library and are destructive, ids unchanged`() {
        listOf(
            musicTrackMenuItems(false, false) to "remove_track",
            videoFileMenuItems(false, false, false) to "video_remove",
            photoFileMenuItems(false) to "photo_remove",
        ).forEach { (items, id) ->
            val row = items.single { it.id == id }
            assertEquals("Remove from Library", row.label)
            assertTrue(row.isDestructive)
            assertEquals(id, items.last().id)
        }
    }

    @Test
    fun `Remove from this Playlist is reversible, so not red`() {
        val track = musicTrackMenuItems(inPlaylist = true, byTouch = false).single { it.id == "remove_from_playlist" }
        val video = videoFileMenuItems(false, inPlaylist = true, byTouch = false).single { it.id == "video_remove_playlist" }
        listOf(track, video).forEach {
            assertEquals("Remove from this Playlist", it.label)
            assertFalse(it.isDestructive)
        }
        assertTrue(musicTrackMenuItems(false, false).none { it.id == "remove_from_playlist" })
        assertTrue(videoFileMenuItems(false, false, false).none { it.id == "video_remove_playlist" })
    }

    @Test
    fun `Favorite is one silent row with an On or Off value`() {
        val on = videoFileMenuItems(isFavorite = true, inPlaylist = false, byTouch = false).single { it.id == "video_favorite" }
        val off = videoFileMenuItems(isFavorite = false, inPlaylist = false, byTouch = false).single { it.id == "video_favorite" }
        assertEquals("Favorite", on.label)
        assertEquals("On", on.value)
        assertEquals("Off", off.value)
        assertTrue(on.silent && off.silent)
    }

    @Test
    fun `Add to Playlist opens a menu`() {
        assertTrue(musicTrackMenuItems(false, false).single { it.id == "add_to_playlist" }.opensMenu)
        val video = videoFileMenuItems(false, false, false).single { it.id == "video_add_playlist" }
        assertEquals("Add to Playlist", video.label)
        assertTrue(video.opensMenu)
    }

    @Test
    fun `playlist rows keep Rename and Delete, Delete is destructive`() {
        listOf(
            musicPlaylistMenuItems(false) to "delete_playlist",
            videoPlaylistMenuItems(false) to "delete_video_playlist",
        ).forEach { (items, deleteId) ->
            assertEquals("Rename Playlist", items.single { it.label == "Rename Playlist" }.label)
            val delete = items.single { it.id == deleteId }
            assertEquals("Delete Playlist", delete.label)
            assertTrue(delete.isDestructive)
            assertEquals(deleteId, items.last().id)
        }
        assertEquals("Add Tracks", musicPlaylistMenuItems(false).first().label)
    }

    @Test
    fun `both music menus end Stop and Close`() {
        val nowPlaying = nowPlayingMenuItems(isPlaying = true)
        val options = musicPlayerOptionsMenuItems()
        listOf(nowPlaying, options).forEach {
            val close = it.single { row -> row.id == "music_close" }
            assertEquals("Stop and Close", close.label)
            assertEquals("music_close", it.last().id)
        }
        assertEquals("Pause", nowPlaying.first().label)
        assertEquals("Resume", nowPlayingMenuItems(isPlaying = false).first().label)
    }

    @Test
    fun `Now Playing offers the player's Visualizer row, between playback and Stop and Close`() {
        listOf(true, false).forEach { playing ->
            val items = nowPlayingMenuItems(isPlaying = playing)
            assertEquals(listOf("music_playpause", "music_visualizer", "music_close"), items.ids())
            assertEquals("Visualizer", items[1].label)
        }
        // Same id and wording as the player's own options, so one handler serves both.
        assertEquals(
            musicPlayerOptionsMenuItems().single { it.id == "music_visualizer" },
            nowPlayingMenuItems(true).single { it.id == "music_visualizer" },
        )
    }

    @Test
    fun `a music track has View Information just above Remove from Library, touch or not`() {
        listOf(true, false).forEach { touch ->
            listOf(true, false).forEach { inPlaylist ->
                val items = musicTrackMenuItems(inPlaylist = inPlaylist, byTouch = touch)
                val info = items.single { it.id == "track_info" }
                assertEquals("View Information", info.label)
                assertFalse(info.isDestructive)
                assertEquals("remove_track", items.last().id)
                assertEquals(items.size - 2, items.indexOf(info))
            }
        }
    }

    @Test
    fun `track information lists what is known, one fact per line, and leaves out the rest`() {
        val full = MusicTrack(
            id = "t", folderId = "f", uri = "u", displayName = "song.mp3", title = "Song", artist = "Band",
            album = "Album", durationMs = 125_000, mimeType = "audio/mpeg", sizeBytes = 5 * 1024 * 1024L,
            relativePath = "Music/Band",
        )
        assertEquals(
            listOf(
                "Artist: Band", "Album: Album", "Duration: 2:05", "Type: audio/mpeg",
                "Size: 5.0 MB", "Location: Music/Band", "File: song.mp3",
            ),
            musicTrackInfoLines(full),
        )
        assertEquals(listOf("File: a.flac"), musicTrackInfoLines(MusicTrack("t", "f", "u", "a.flac")))
    }

    @Test
    fun `media cards scan and manage, with no Open row`() {
        val items = mediaCardMenuItems("Videos")
        assertEquals(listOf("media_card_scan", "media_card_manage"), items.ids())
        assertEquals("Scan Videos", items.first().label)
        assertNull(items.firstOrNull { it.label == "Open" })
    }

    @Test
    fun `libraries and albums scan and manage`() {
        assertEquals("Scan Library", videoLibraryMenuItems(false).single { it.id == "video_lib_scan" }.label)
        assertEquals("Scan Album", photoAlbumMenuItems(false).single { it.id == "photo_lib_scan" }.label)
        assertTrue(videoLibraryMenuItems(false).any { it.id == "video_lib_manage" })
        assertTrue(photoAlbumMenuItems(false).any { it.id == "photo_lib_manage" })
    }

    @Test
    fun `every destructive media row raises a confirm`() {
        fun menu(items: List<XMBContextMenuItem>, build: (XMBContextMenu) -> XMBContextMenu = { it }) =
            build(XMBContextMenu("T", items))
        val cases = listOf(
            menu(musicTrackMenuItems(true, true)) { it.copy(musicTrackId = "t") },
            menu(musicPlaylistMenuItems(true)) { it.copy(playlistId = 1L) },
            menu(videoFileMenuItems(false, true, true)) { it.copy(videoFileId = "v") },
            menu(videoPlaylistMenuItems(true)) { it.copy(videoPlaylistId = 1L) },
            menu(photoFileMenuItems(true)) { it.copy(photoFileId = "p") },
        )
        cases.forEach { m ->
            m.items.filter { it.isDestructive }.forEach { assertTrue("${it.id} needs a confirm", confirmFor(m, it.id) != null) }
        }
    }
}
