package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.MusicTrack
import com.playfieldportal.core.domain.model.Playlist
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.Video
import com.playfieldportal.core.domain.playlist.PlaylistKind
import com.playfieldportal.core.domain.repository.MusicRepository
import com.playfieldportal.core.domain.repository.VideoRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parse, match, name, write, report: with mockk repositories and no Context. */
class PlaylistImportRunnerTest {

    private val music = mockk<MusicRepository>()
    private val video = mockk<VideoRepository>()
    private val runner = PlaylistImportRunner(music, video)

    private fun track(id: String, name: String, title: String? = null, artist: String? = null) =
        MusicTrack(id = id, folderId = "f", uri = "content://x/$id", displayName = name, title = title, artist = artist)

    private fun movie(id: String, name: String) =
        Video(id = id, libraryId = "l", uri = "content://x/$id", displayName = name)

    private fun givenMusic(tracks: List<MusicTrack>, existing: List<String> = emptyList()) {
        every { music.observeAllTracks() } returns flowOf(tracks)
        every { music.observePlaylists() } returns flowOf(existing.mapIndexed { i, n -> Playlist(i + 1L, n) })
        coEvery { music.importPlaylist(any(), any()) } returns 42L
    }

    private fun m3u(name: String, vararg lines: String) =
        PickedPlaylistFile.Content(name, lines.joinToString("\n").toByteArray())

    @Test
    fun `all misses never write and list each reason`() = runTest {
        givenMusic(listOf(track("t1", "a.mp3")))
        val report = runner.run(PlaylistKind.MUSIC, listOf(m3u("Mix.m3u", "nope.mp3", "http://x/y.mp3"))).single()

        coVerify(exactly = 0) { music.importPlaylist(any(), any()) }
        assertNull(report.playlistId)
        val results = report.toResults()
        assertEquals(2, results.items.size)
        assertEquals(ResultOutcome.FAILED, results.items[0].outcome)
        assertEquals("not in your library", results.items[0].reason)
        assertEquals("nope.mp3", results.items[0].primary)
        assertEquals(ResultOutcome.SKIPPED, results.items[1].outcome)
        assertEquals("not a local file", results.items[1].reason)
        assertEquals("Nothing imported from \"Mix.m3u\"", report.title)
    }

    @Test
    fun `matches write once with clash-free name and ids in file order`() = runTest {
        givenMusic(listOf(track("t1", "a.mp3"), track("t2", "b.mp3")), existing = listOf("Mix"))
        val name = slot<String>()
        val ids = slot<List<String>>()
        coEvery { music.importPlaylist(capture(name), capture(ids)) } returns 7L

        val report = runner.run(PlaylistKind.MUSIC, listOf(m3u("Mix.m3u", "b.mp3", "a.mp3"))).single()

        coVerify(exactly = 1) { music.importPlaylist(any(), any()) }
        assertEquals("Mix (2)", name.captured)
        assertEquals(listOf("t2", "t1"), ids.captured)
        assertEquals(7L, report.playlistId)
        assertEquals("Mix (2)", report.playlistName)
        assertEquals("Imported \"Mix (2)\"", report.title)
        assertEquals(2, report.toResults().items.count { it.outcome == ResultOutcome.DONE })
    }

    @Test
    fun `music uses audio as own and video as other, and video the reverse`() = runTest {
        givenMusic(listOf(track("t1", "song.mp3")))
        val m = runner.run(PlaylistKind.MUSIC, listOf(m3u("A.m3u", "clip.mp4", "song.mp3"))).single()
        assertEquals("wrong media type", m.toResults().items.first { it.outcome == ResultOutcome.SKIPPED }.reason)
        assertEquals(42L, m.playlistId)

        every { video.observeAllVideos() } returns flowOf(listOf(movie("v1", "clip.mp4")))
        every { video.observePlaylists() } returns flowOf(emptyList())
        coEvery { video.importPlaylist(any(), any()) } returns 9L
        val v = runner.run(PlaylistKind.VIDEO, listOf(m3u("B.m3u", "song.mp3", "clip.mp4"))).single()
        assertEquals("wrong media type", v.toResults().items.first { it.outcome == ResultOutcome.SKIPPED }.reason)
        assertEquals(9L, v.playlistId)
        coVerify { video.importPlaylist("B", listOf("v1")) }
    }

    @Test
    fun `two files that resolve to the same name get a counter`() = runTest {
        givenMusic(listOf(track("t1", "a.mp3")))
        val names = mutableListOf<String>()
        coEvery { music.importPlaylist(capture(names), any()) } returns 1L
        runner.run(
            PlaylistKind.MUSIC,
            listOf(
                m3u("x.m3u", "#PLAYLIST:Road Trip", "a.mp3"),
                m3u("y.m3u", "#PLAYLIST:Road Trip", "a.mp3"),
            ),
        )
        assertEquals(listOf("Road Trip", "Road Trip (2)"), names)
    }

    @Test
    fun `an unreadable file errors and the next one still imports`() = runTest {
        givenMusic(listOf(track("t1", "a.mp3")))
        val reports = runner.run(
            PlaylistKind.MUSIC,
            listOf(PickedPlaylistFile.Unreadable("Bad.m3u", "Permission denied"), m3u("Good.m3u", "a.mp3")),
        )
        assertEquals("Couldn't import \"Bad.m3u\"", reports[0].title)
        assertEquals("Permission denied", reports[0].toResults().summary)
        assertTrue(reports[0].toResults().items.isEmpty())
        assertNull(reports[0].playlistId)
        assertNotNull(reports[1].playlistId)
    }

    @Test
    fun `a non-playlist extension is reported and nothing is written`() = runTest {
        givenMusic(listOf(track("t1", "a.mp3")))
        val report = runner.run(
            PlaylistKind.MUSIC,
            listOf(PickedPlaylistFile.Content("photo.jpg", "a.mp3".toByteArray())),
        ).single()
        coVerify(exactly = 0) { music.importPlaylist(any(), any()) }
        assertEquals("Not a playlist file", report.toResults().summary)
        assertEquals("Couldn't import \"photo.jpg\"", report.title)
    }

    @Test
    fun `results mapping uses the three labels, the reasons and artist dash title`() = runTest {
        givenMusic(listOf(track("t1", "a.mp3", title = "Hello", artist = "Adele")))
        val report = runner.run(
            PlaylistKind.MUSIC,
            listOf(m3u("L.m3u", "#EXTINF:100,Ghost - Missing", "gone.mp3", "a.mp3", "a.mp3")),
        ).single()
        val r = report.toResults()
        assertEquals("Found", r.labels.done)
        assertEquals("Not in your library", r.labels.failed)
        assertEquals("Skipped", r.labels.skipped)
        assertEquals("Ghost – Missing", r.items.first { it.outcome == ResultOutcome.FAILED }.primary)
        assertEquals("already in the playlist", r.items.first { it.outcome == ResultOutcome.SKIPPED }.reason)
        assertEquals("Hello", r.items.first { it.outcome == ResultOutcome.DONE }.primary)
        assertFalse(r.summary.isNullOrBlank())
    }
}
