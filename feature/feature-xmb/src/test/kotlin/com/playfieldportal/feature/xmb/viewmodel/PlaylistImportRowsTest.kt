package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.Playlist
import com.playfieldportal.core.domain.model.VideoPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the Import Playlist rows and the browser menu entry that open the playlist file picker. */
class PlaylistImportRowsTest {

    private val music = playlistRootItems(listOf(Playlist(1, "Road Trip"), Playlist(2, "Gym")))
    private val video = videoPlaylistItems(listOf(VideoPlaylist(1, "Movies")))

    @Test
    fun `music playlist rows end with Create Playlist then Import Playlist`() {
        assertEquals(listOf("pl_1", "pl_2", "create_playlist", "import_playlist"), music.map { it.id })
        val import = music.last()
        assertEquals("Import Playlist", import.title)
        assertEquals("From an .m3u, .m3u8, .pls or .xspf file", import.subtitle)
        assertEquals(XMBItemType.ADD_ACTION, import.type)
    }

    @Test
    fun `import row is offered with no playlists at all`() {
        assertEquals(listOf("create_playlist", "import_playlist"), playlistRootItems(emptyList()).map { it.id })
        assertEquals(listOf("create_video_playlist", "import_video_playlist"), videoPlaylistItems(emptyList()).map { it.id })
    }

    @Test
    fun `video playlist rows end with Create Playlist then their own Import row`() {
        assertEquals(listOf("vpl_1", "create_video_playlist", "import_video_playlist"), video.map { it.id })
        val import = video.last()
        assertEquals("Import Playlist", import.title)
        assertEquals("From an .m3u, .m3u8, .pls or .xspf file", import.subtitle)
        assertEquals(XMBItemType.ADD_ACTION, import.type)
        assertNotEquals(music.last().id, import.id)
    }

    @Test
    fun `browser list menu offers import in the Playlists view only`() {
        assertEquals(
            listOf("music_browser_import"),
            browserImportMenuItems(MusicBrowserView.Playlists).map { it.id },
        )
        assertTrue(browserImportMenuItems(MusicBrowserView.AllMusic).isEmpty())
        assertTrue(browserImportMenuItems(MusicBrowserView.Playlist(1, "Road Trip")).isEmpty())
        assertTrue(browserImportMenuItems(null).isEmpty())
    }
}
