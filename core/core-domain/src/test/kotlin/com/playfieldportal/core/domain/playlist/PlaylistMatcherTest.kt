package com.playfieldportal.core.domain.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins how playlist entries are matched to library items: the skip rules, the path-suffix, unique
 * file-name and music-only artist+title steps, ties, and de-duplication.
 */
class PlaylistMatcherTest {

    private val audio = setOf("mp3", "flac", "m4a")
    private val video = setOf("mp4", "mkv")

    private fun track(
        id: String,
        relativePath: String?,
        name: String,
        title: String? = null,
        artist: String? = null,
        durationMs: Long? = null,
    ) = PlaylistCandidate(id, relativePath, name, title, artist, durationMs)

    private fun entry(
        location: String,
        title: String? = null,
        artist: String? = null,
        durationMs: Long? = null,
    ) = PlaylistEntry(location, title, artist, durationMs)

    private fun music(entries: List<PlaylistEntry>, library: List<PlaylistCandidate>) =
        PlaylistMatcher.match(PlaylistKind.MUSIC, entries, library, audio, video)

    private fun video(entries: List<PlaylistEntry>, library: List<PlaylistCandidate>) =
        PlaylistMatcher.match(PlaylistKind.VIDEO, entries, library, video, audio)

    private fun matchedId(report: PlaylistMatchReport, index: Int = 0): String =
        assertIs<PlaylistEntryOutcome.Matched>(report.outcomes[index]).id

    private fun assertNotFound(report: PlaylistMatchReport, ambiguous: Boolean, index: Int = 0) {
        val outcome = assertIs<PlaylistEntryOutcome.NotFound>(report.outcomes[index])
        assertEquals(ambiguous, outcome.ambiguous)
    }

    // ── Step 1: path ──

    @Test
    fun `windows absolute entry matches the folder and file candidate`() {
        val lib = listOf(
            track("a", "Fleet Foxes/Helplessness Blues", "03 Montezuma.flac"),
            track("b", "Other/Album", "03 Montezuma.flac"),
        )
        val r = music(listOf(entry("""D:\Music\Fleet Foxes\Helplessness Blues\03 Montezuma.flac""")), lib)
        assertEquals("a", matchedId(r))
    }

    @Test
    fun `relative entry matches the longer candidate key`() {
        val lib = listOf(track("a", "Fleet Foxes/Helplessness Blues", "03 Montezuma.flac"))
        val r = music(listOf(entry("Helplessness Blues/03 Montezuma.flac")), lib)
        assertEquals("a", matchedId(r))
    }

    @Test
    fun `entry folder picks between candidates sharing a file name`() {
        val lib = listOf(
            track("a", "Album A", "01.flac"),
            track("b", "Album B", "01.flac"),
        )
        val r = music(listOf(entry("/sdcard/Music/Album B/01.flac")), lib)
        assertEquals("b", matchedId(r))
    }

    @Test
    fun `longer shared suffix wins`() {
        val lib = listOf(
            track("short", "Album", "01.flac"),
            track("long", "Artist/Album", "01.flac"),
        )
        val r = music(listOf(entry("/music/Artist/Album/01.flac")), lib)
        assertEquals("long", matchedId(r))
    }

    @Test
    fun `identical keys under two roots tie as more than one match`() {
        val lib = listOf(
            track("a", "Album", "01.flac"),
            track("b", "Album", "01.flac"),
        )
        val r = music(listOf(entry("/music/Album/01.flac")), lib)
        assertNotFound(r, ambiguous = true)
        assertTrue(r.matchedIds.isEmpty())
    }

    @Test
    fun `matching is case-insensitive across the whole path`() {
        val lib = listOf(track("a", "FLEET Foxes/HELPLESSNESS Blues", "03 MONTEZUMA.FLAC"))
        val r = music(listOf(entry("""c:\music\fleet foxes\helplessness blues\03 montezuma.flac""")), lib)
        assertEquals("a", matchedId(r))
    }

    @Test
    fun `percent-decoded variant of a plain path matches`() {
        val lib = listOf(track("a", "Fleet Foxes", "03 Montezuma.flac"))
        val r = music(listOf(entry("Fleet%20Foxes/03%20Montezuma.flac")), lib)
        assertEquals("a", matchedId(r))
    }

    // ── Step 2: file name ──

    @Test
    fun `unique file name matches wherever it is`() {
        val lib = listOf(
            track("a", "Sounds/Misc", "intro.mp3"),
            track("b", "Album", "01.flac"),
        )
        val r = music(listOf(entry("../misc/intro.mp3")), lib)
        assertEquals("a", matchedId(r))
    }

    @Test
    fun `duplicate file name with no other clue is a tie`() {
        val lib = listOf(
            track("a", "One", "intro.mp3"),
            track("b", "Two", "intro.mp3"),
        )
        val r = music(listOf(entry("../misc/intro.mp3")), lib)
        assertNotFound(r, ambiguous = true)
    }

    @Test
    fun `unknown file is plain not found`() {
        val r = music(listOf(entry("/x/nothing.mp3")), listOf(track("a", "One", "intro.mp3")))
        assertNotFound(r, ambiguous = false)
    }

    // ── Step 3: artist + title (music only) ──

    @Test
    fun `artist and title match ignoring case and extra whitespace`() {
        val lib = listOf(track("a", "Rumours", "07.mp3", title = "Dreams", artist = "Fleetwood Mac"))
        val r = music(listOf(entry("/gone/away.mp3", title = "  dreams ", artist = "FLEETWOOD   mac")), lib)
        assertEquals("a", matchedId(r))
    }

    @Test
    fun `tags are never used for video`() {
        val lib = listOf(track("a", "Films", "x.mp4", title = "Dreams", artist = "Fleetwood Mac"))
        val r = video(listOf(entry("/gone/away.mp4", title = "Dreams", artist = "Fleetwood Mac")), lib)
        assertNotFound(r, ambiguous = false)
    }

    @Test
    fun `title-only entry is not found`() {
        val lib = listOf(track("a", "Rumours", "07.mp3", title = "Dreams", artist = "Fleetwood Mac"))
        val r = music(listOf(entry("/gone/away.mp3", title = "Dreams")), lib)
        assertNotFound(r, ambiguous = false)
    }

    @Test
    fun `step 3 tie is broken by duration within two seconds`() {
        val lib = listOf(
            track("live", "Live", "a.mp3", title = "Dreams", artist = "Fleetwood Mac", durationMs = 300_000),
            track("studio", "Studio", "b.mp3", title = "Dreams", artist = "Fleetwood Mac", durationMs = 257_000),
        )
        val r = music(listOf(entry("/gone.mp3", "Dreams", "Fleetwood Mac", durationMs = 258_500)), lib)
        assertEquals("studio", matchedId(r))
    }

    @Test
    fun `step 3 tie stays a tie outside the duration window or without a duration`() {
        val lib = listOf(
            track("live", "Live", "a.mp3", title = "Dreams", artist = "Fleetwood Mac", durationMs = 300_000),
            track("studio", "Studio", "b.mp3", title = "Dreams", artist = "Fleetwood Mac", durationMs = 257_000),
        )
        val r = music(
            listOf(
                entry("/gone.mp3", "Dreams", "Fleetwood Mac", durationMs = 280_000),
                entry("/gone2.mp3", "Dreams", "Fleetwood Mac"),
            ),
            lib,
        )
        assertNotFound(r, ambiguous = true, index = 0)
        assertNotFound(r, ambiguous = true, index = 1)
    }

    @Test
    fun `step 3 resolves a file-name tie`() {
        val lib = listOf(
            track("a", "One", "intro.mp3", title = "Intro", artist = "Band"),
            track("b", "Two", "intro.mp3", title = "Intro", artist = "Other"),
        )
        val r = music(listOf(entry("/x/intro.mp3", "Intro", "Band")), lib)
        assertEquals("a", matchedId(r))
    }

    // ── Skips ──

    @Test
    fun `web link is skipped`() {
        val lib = listOf(track("a", "Album", "01.flac", title = "T", artist = "A"))
        val r = music(listOf(entry("http://radio.example/stream.mp3", "T", "A")), lib)
        val outcome = assertIs<PlaylistEntryOutcome.Skipped>(r.outcomes[0])
        assertEquals(PlaylistSkipReason.WEB_LINK, outcome.reason)
    }

    @Test
    fun `content uri is skipped rather than matched`() {
        val r = music(listOf(entry("content://media/external/audio/1")), emptyList())
        assertIs<PlaylistEntryOutcome.Skipped>(r.outcomes[0])
    }

    @Test
    fun `other kind and nested playlists are wrong type`() {
        val m = music(listOf(entry("/x/clip.mp4"), entry("/x/more.m3u8")), emptyList())
        assertEquals(PlaylistSkipReason.WRONG_TYPE, assertIs<PlaylistEntryOutcome.Skipped>(m.outcomes[0]).reason)
        assertEquals(PlaylistSkipReason.WRONG_TYPE, assertIs<PlaylistEntryOutcome.Skipped>(m.outcomes[1]).reason)

        val v = video(listOf(entry("/x/song.mp3")), emptyList())
        assertEquals(PlaylistSkipReason.WRONG_TYPE, assertIs<PlaylistEntryOutcome.Skipped>(v.outcomes[0]).reason)
    }

    @Test
    fun `an extension both kinds list is not wrong type`() {
        val lib = listOf(track("a", "Films", "x.mp4"))
        val r = PlaylistMatcher.match(
            PlaylistKind.MUSIC, listOf(entry("/Films/x.mp4")), lib, setOf("mp4"), setOf("mp4"),
        )
        assertEquals("a", matchedId(r))
    }

    // ── Duplicates and ordering ──

    @Test
    fun `second entry resolving to a matched item is a duplicate`() {
        val lib = listOf(track("a", "Album", "01.flac"))
        val r = music(listOf(entry("/m/Album/01.flac"), entry("""D:\Album\01.flac""")), lib)
        assertEquals("a", matchedId(r, 0))
        assertEquals(PlaylistSkipReason.DUPLICATE, assertIs<PlaylistEntryOutcome.Skipped>(r.outcomes[1]).reason)
        assertEquals(listOf("a"), r.matchedIds)
    }

    @Test
    fun `matched ids keep file order with one outcome per entry`() {
        val lib = listOf(
            track("a", "Album", "01.flac"),
            track("b", "Album", "02.flac"),
            track("c", "Album", "03.flac"),
        )
        val entries = listOf(
            entry("/m/Album/03.flac"),
            entry("http://x/y.mp3"),
            entry("/m/Album/01.flac"),
            entry("/m/Album/missing.flac"),
            entry("/m/Album/02.flac"),
        )
        val r = music(entries, lib)
        assertEquals(entries.size, r.outcomes.size)
        assertEquals(entries, r.outcomes.map { it.entry })
        assertEquals(listOf("c", "a", "b"), r.matchedIds)
    }

    // ── Root-level candidates ──

    @Test
    fun `root-level candidate is never matched by path but by name and tags`() {
        val lib = listOf(
            track("byName", null, "01.flac"),
            track("byTags", null, "07.mp3", title = "Dreams", artist = "Fleetwood Mac"),
        )
        val r = music(
            listOf(
                entry("/m/Album/01.flac"),
                entry("/m/Album/zzz.flac", "Dreams", "Fleetwood Mac"),
            ),
            lib,
        )
        assertEquals("byName", matchedId(r, 0))
        assertEquals("byTags", matchedId(r, 1))
    }

    @Test
    fun `root-level candidate is not path-matched when a name tie exists`() {
        val lib = listOf(
            track("root", null, "01.flac"),
            track("deep", "Album", "01.flac"),
        )
        // Entry `Album/01.flac` matches `deep` by path only; `root` has no folder to compare.
        val r = music(listOf(entry("Album/01.flac")), lib)
        assertEquals("deep", matchedId(r))
    }
}
