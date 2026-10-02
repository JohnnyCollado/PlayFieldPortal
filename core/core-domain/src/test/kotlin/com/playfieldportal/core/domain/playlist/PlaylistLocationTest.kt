package com.playfieldportal.core.domain.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins the path clean-up that runs before an entry is compared with the library: scheme handling,
 * percent-decoding that leaves `+` alone, drives and UNC prefixes, `.`/`..`, NFC and case folding.
 */
class PlaylistLocationTest {

    private fun path(raw: String): PlaylistLocation.Path {
        val loc = PlaylistLocationNormalizer.normalize(raw)
        return assertIs<PlaylistLocation.Path>(loc, "expected a path for: $raw")
    }

    private fun segments(raw: String) = path(raw).segments

    // ── Paths ──

    @Test
    fun `windows absolute path drops the drive and lowercases`() {
        assertEquals(
            listOf("music", "fleet foxes", "helplessness blues", "03 montezuma.flac"),
            segments("""D:\Music\Fleet Foxes\Helplessness Blues\03 Montezuma.flac"""),
        )
    }

    @Test
    fun `android absolute path becomes segments`() {
        assertEquals(
            listOf("storage", "emulated", "0", "music", "a", "b.mp3"),
            segments("/storage/emulated/0/Music/A/b.mp3"),
        )
    }

    @Test
    fun `file uri is percent-decoded and a drive in it is dropped`() {
        assertEquals(
            listOf("storage", "emulated", "0", "music", "a b", "c.flac"),
            segments("file:///storage/emulated/0/Music/A%20B/c.flac"),
        )
        assertEquals(listOf("music", "x.mp3"), segments("file:///C:/Music/x.mp3"))
    }

    @Test
    fun `plus survives in raw and file forms`() {
        assertEquals(listOf("ac+dc.mp3"), segments("AC+DC.mp3"))
        assertEquals(listOf("music", "ac+dc.mp3"), segments("file:///Music/AC+DC.mp3"))
    }

    @Test
    fun `malformed escapes are kept literally without throwing`() {
        assertEquals(listOf("100%.mp3"), segments("100%.mp3"))
        assertEquals(listOf("a%zz.mp3"), segments("a%zz.mp3"))
        assertEquals(listOf("music", "100%.mp3"), segments("file:///Music/100%.mp3"))
        assertEquals(listOf("%zz.mp3"), segments("file:///%zz.mp3"))
    }

    @Test
    fun `dot segments are resolved and a leading parent reference is dropped`() {
        assertEquals(listOf("misc", "intro.mp3"), segments("../misc/intro.mp3"))
        assertEquals(listOf("a", "b.mp3"), segments("./a/b.mp3"))
        assertEquals(listOf("b", "c.mp3"), segments("""a\..\b\c.mp3"""))
    }

    // ── Classification ──

    @Test
    fun `web schemes are web links`() {
        listOf(
            "http://x.com/a.mp3",
            "https://x.com/a.mp3",
            "rtsp://host/stream",
            "mms://host/stream",
        ).forEach { assertEquals(PlaylistLocation.WebLink, PlaylistLocationNormalizer.normalize(it), it) }
    }

    @Test
    fun `a one-letter scheme is a drive not a web link`() {
        assertIs<PlaylistLocation.Path>(PlaylistLocationNormalizer.normalize("""C:\x.mp3"""))
        assertIs<PlaylistLocation.Path>(PlaylistLocationNormalizer.normalize("c:/x.mp3"))
    }

    @Test
    fun `blank or whitespace-only is empty`() {
        assertEquals(PlaylistLocation.Empty, PlaylistLocationNormalizer.normalize(""))
        assertEquals(PlaylistLocation.Empty, PlaylistLocationNormalizer.normalize("   \t "))
    }

    // ── P1 ──

    @Test
    fun `file localhost and single-slash drive forms are handled`() {
        assertEquals(listOf("music", "x.mp3"), segments("file://localhost/Music/x.mp3"))
        assertEquals(listOf("music", "x.mp3"), segments("file:/C:/Music/x.mp3"))
    }

    @Test
    fun `unc prefix drops host and share`() {
        assertEquals(listOf("music", "a.mp3"), segments("""\\server\share\Music\a.mp3"""))
    }

    @Test
    fun `nfd and nfc compare equal`() {
        assertEquals(segments("Beyonc\u00E9.mp3"), segments("Beyonce\u0301.mp3"))
    }

    @Test
    fun `a raw path with a literal escape gets both a raw and a decoded variant`() {
        val p = path("Music/A%20B/c.flac")
        assertEquals(listOf("music", "a%20b", "c.flac"), p.segments)
        assertEquals(listOf("music", "a b", "c.flac"), p.decodedSegments)
    }

    @Test
    fun `no decoded variant when decoding changes nothing`() {
        assertNull(path("Music/a.mp3").decodedSegments)
        assertNull(path("100%.mp3").decodedSegments)
        assertNull(path("file:///Music/A%20B/c.flac").decodedSegments)
    }
}
