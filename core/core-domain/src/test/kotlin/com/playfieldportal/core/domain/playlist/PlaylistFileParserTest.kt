package com.playfieldportal.core.domain.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the playlist-file reader: the M3U/M3U8 rules, the text decoding in front of them (BOM, line
 * endings, the Windows-1252 fallback for old .m3u exports) and the size limits. Locations come back
 * exactly as written — normalising them is the matcher's job, not the parser's.
 */
class PlaylistFileParserTest {

    private fun parse(name: String, text: String) =
        PlaylistFileParser.parse(name, text.toByteArray(Charsets.UTF_8))

    private fun locations(p: ParsedPlaylist) = p.entries.map { it.location }

    // ── Entries ──

    @Test
    fun `plain path lines become entries in file order, blanks and comments ignored`() {
        val p = parse("a.m3u", "# a comment\nsong1.mp3\n\n   \nsub/song2.mp3\n#another\nsong3.mp3\n")

        assertEquals(PlaylistFormat.M3U, p.format)
        assertEquals(listOf("song1.mp3", "sub/song2.mp3", "song3.mp3"), locations(p))
        assertEquals(0, p.dropped)
        assertNull(p.rejected)
    }

    @Test
    fun `EXTINF gives the next entry its duration, artist and title`() {
        val p = parse("a.m3u", "#EXTM3U\n#EXTINF:241,Fleetwood Mac - Dreams\nDreams.mp3\nplain.mp3\n")

        val first = p.entries[0]
        assertEquals("Dreams.mp3", first.location)
        assertEquals(241_000L, first.durationMs)
        assertEquals("Fleetwood Mac", first.artist)
        assertEquals("Dreams", first.title)
        // The hint applies to one entry only.
        val second = p.entries[1]
        assertNull(second.durationMs)
        assertNull(second.artist)
        assertNull(second.title)
    }

    @Test
    fun `PLAYLIST directive sets the name, and without it the name is null`() {
        assertEquals("Road Trip", parse("a.m3u", "#PLAYLIST:Road Trip\nx.mp3").name)
        assertNull(parse("a.m3u", "x.mp3").name)
    }

    @Test
    fun `locations are passed through as written`() {
        val lines = listOf(
            "D:\\Music\\a.flac",
            "/storage/emulated/0/Music/a.flac",
            "../misc/intro.mp3",
            "file:///C:/Music/My%20Song.mp3",
            "http://example.com/stream",
        )
        val p = parse("a.m3u8", lines.joinToString("\n"))

        assertEquals(lines, locations(p))
    }

    @Test
    fun `whitespace around a path is trimmed and inner spaces are kept`() {
        val p = parse("a.m3u", "   My Music/a  b.mp3 \t\n")

        assertEquals(listOf("My Music/a  b.mp3"), locations(p))
    }

    @Test
    fun `unknown directives are ignored`() {
        val p = parse(
            "a.m3u8",
            "#EXTM3U\n#EXTALB:Album\n#EXTGRP:Rock\n#EXT-X-VERSION:3\n#EXTINF:10,A - B\n#EXTALB:x\na.mp3\n",
        )

        assertEquals(listOf("a.mp3"), locations(p))
        // A directive between EXTINF and the path does not cost the entry its hints.
        assertEquals("B", p.entries[0].title)
    }

    // ── Decoding ──

    @Test
    fun `a UTF-8 BOM is stripped`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "first.mp3\nsecond.mp3".toByteArray(Charsets.UTF_8)
        val p = PlaylistFileParser.parse("a.m3u8", bytes)

        assertEquals(listOf("first.mp3", "second.mp3"), locations(p))
        assertFalse(p.entries[0].location.startsWith("\uFEFF"))
    }

    @Test
    fun `CRLF, LF and lone CR give the same entries`() {
        val expected = listOf("a.mp3", "b.mp3", "c.mp3")
        assertEquals(expected, locations(parse("a.m3u", "a.mp3\r\nb.mp3\r\nc.mp3\r\n")))
        assertEquals(expected, locations(parse("a.m3u", "a.mp3\nb.mp3\nc.mp3\n")))
        assertEquals(expected, locations(parse("a.m3u", "a.mp3\rb.mp3\rc.mp3\r")))
    }

    @Test
    fun `an m3u with invalid UTF-8 decodes as Windows-1252, an m3u8 does not`() {
        val bytes = "caf".toByteArray(Charsets.US_ASCII) + byteArrayOf(0xE9.toByte()) +
            ".mp3".toByteArray(Charsets.US_ASCII)

        assertEquals(listOf("caf\u00E9.mp3"), locations(PlaylistFileParser.parse("old.m3u", bytes)))

        val strict = locations(PlaylistFileParser.parse("new.m3u8", bytes)).single()
        assertFalse(strict.contains('\u00E9'), strict)
    }

    @Test
    fun `a valid UTF-8 m3u stays UTF-8`() {
        assertEquals(listOf("caf\u00E9.mp3"), locations(parse("a.m3u", "caf\u00E9.mp3")))
    }

    @Test
    fun `a UTF-16 LE file with a BOM decodes`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "#PLAYLIST:Wide\r\nsong.mp3\r\n".toByteArray(Charsets.UTF_16LE)
        val p = PlaylistFileParser.parse("a.m3u8", bytes)

        assertEquals("Wide", p.name)
        assertEquals(listOf("song.mp3"), locations(p))
    }

    @Test
    fun `a UTF-16 BE file with a BOM decodes`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) +
            "song.mp3\n".toByteArray(Charsets.UTF_16BE)

        assertEquals(listOf("song.mp3"), locations(PlaylistFileParser.parse("a.m3u", bytes)))
    }

    // ── EXTINF details ──

    @Test
    fun `EXTINF attributes with a quoted comma, and an unknown duration`() {
        val p = parse(
            "a.m3u",
            "#EXTINF:-1 tvg-id=\"x\" group-title=\"a,b\",Artist - Title\nsong.mp3\n",
        )

        val e = p.entries.single()
        assertNull(e.durationMs)
        assertEquals("Artist", e.artist)
        assertEquals("Title", e.title)
    }

    @Test
    fun `an EXTINF with no path line after it is dropped`() {
        val p = parse("a.m3u", "#EXTINF:10,A - B\n#EXTINF:20,C - D\nsong.mp3\n#EXTINF:30,E - F\n")

        val e = p.entries.single()
        assertEquals("song.mp3", e.location)
        assertEquals("D", e.title)
        assertEquals(20_000L, e.durationMs)
    }

    @Test
    fun `an EXTINF title with no separator is a title only, and an en dash splits`() {
        val p = parse("a.m3u", "#EXTINF:5,Just A Title\na.mp3\n#EXTINF:5,Band \u2013 Song\nb.mp3\n")

        assertNull(p.entries[0].artist)
        assertEquals("Just A Title", p.entries[0].title)
        assertEquals("Band", p.entries[1].artist)
        assertEquals("Song", p.entries[1].title)
    }

    // ── Format detection ──

    @Test
    fun `the format comes from the extension`() {
        assertEquals(PlaylistFormat.M3U, parse("a.M3U8", "x.mp3").format)
        assertEquals(PlaylistFormat.PLS, parse("a.pls", "x.mp3").format)
        assertEquals(PlaylistFormat.XSPF, parse("a.xspf", "x.mp3").format)
    }

    @Test
    fun `an unknown extension is sniffed from the content`() {
        assertEquals(PlaylistFormat.PLS, parse("list.txt", "[playlist]\nFile1=a.mp3\n").format)
        assertEquals(PlaylistFormat.PLS, parse("list", "\n  [Playlist]\nFile1=a.mp3\n").format)
        assertEquals(PlaylistFormat.XSPF, parse("list.txt", "<?xml version=\"1.0\"?><playlist/>").format)
        assertEquals(PlaylistFormat.XSPF, parse("list.txt", "<playlist version=\"1\"></playlist>").format)
        val m3u = parse("list.txt", "a.mp3\nb.mp3\n")
        assertEquals(PlaylistFormat.M3U, m3u.format)
        assertEquals(listOf("a.mp3", "b.mp3"), locations(m3u))
    }

    @Test
    fun `sniffed PLS and XSPF content is read into entries`() {
        val pls = parse("list.txt", "[playlist]\nFile1=a.mp3\nTitle1=Band - Song\nLength1=10\n")
        assertEquals(PlaylistFormat.PLS, pls.format)
        assertEquals("a.mp3", pls.entries.single().location)
        assertEquals("Song", pls.entries.single().title)

        val xspf = parse(
            "list",
            "<?xml version=\"1.0\"?><playlist><trackList><track><location>b.mp3</location></track></trackList></playlist>",
        )
        assertEquals(PlaylistFormat.XSPF, xspf.format)
        assertEquals(listOf("b.mp3"), locations(xspf))
    }

    // ── PLS ──

    @Test
    fun `PLS entries are paired and ordered by index, keys case-insensitive`() {
        val p = parse(
            "a.pls",
            "[playlist]\nnumberofentries=2\nFILE2=two.mp3\nfile1=one.mp3\nTITLE1=Fleetwood Mac - Dreams\n" +
                "length1=241\nTitle2=Just A Title\nLength2=-1\nVersion=2\n",
        )

        assertEquals(PlaylistFormat.PLS, p.format)
        assertNull(p.name)
        assertEquals(listOf("one.mp3", "two.mp3"), locations(p))
        val one = p.entries[0]
        assertEquals("Fleetwood Mac", one.artist)
        assertEquals("Dreams", one.title)
        assertEquals(241_000L, one.durationMs)
        val two = p.entries[1]
        assertNull(two.artist)
        assertEquals("Just A Title", two.title)
        assertNull(two.durationMs)
    }

    @Test
    fun `PLS tolerates spaces around the equals sign, comments and an entry with no file`() {
        val p = parse("a.pls", "[playlist]\n; note\nFile1 = a b.mp3 \nTitle5=Orphan\nFile3=c.mp3\n")

        assertEquals(listOf("a b.mp3", "c.mp3"), locations(p))
    }

    @Test
    fun `PLS without a header still returns the entries it can read, and past the cap is counted`() {
        assertEquals(listOf("a.mp3"), locations(parse("a.pls", "File1=a.mp3\n")))

        val text = "[playlist]\n" + (1..PlaylistFileParser.MAX_ENTRIES + 3).joinToString("\n") { "File$it=t$it.mp3" }
        val p = parse("a.pls", text)
        assertEquals(PlaylistFileParser.MAX_ENTRIES, p.entries.size)
        assertEquals(3, p.dropped)
    }

    // ── XSPF ──

    private val xspfSample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!-- <title>Not This</title> -->
        <playlist version="1" xmlns="http://xspf.org/ns/0/">
          <title>Road Trip</title>
          <trackList>
            <track>
              <location>file:///music/a.mp3</location>
              <location>file:///music/a-second.mp3</location>
              <creator>Fleetwood Mac</creator>
              <title>Dreams</title>
              <duration>241000</duration>
            </track>
            <track>
              <location>/storage/emulated/0/Music/b.flac</location>
              <title>Only A Title</title>
            </track>
          </trackList>
        </playlist>
    """.trimIndent()

    @Test
    fun `XSPF playlist title is the name and a track title is not`() {
        val p = parse("a.xspf", xspfSample)

        assertEquals(PlaylistFormat.XSPF, p.format)
        assertEquals("Road Trip", p.name)
        assertEquals(2, p.entries.size)
    }

    @Test
    fun `XSPF with no playlist title has no name`() {
        val p = parse("a.xspf", "<playlist><trackList><track><title>T</title><location>a.mp3</location></track></trackList></playlist>")

        assertNull(p.name)
        assertEquals("T", p.entries.single().title)
    }

    @Test
    fun `XSPF tracks map location, creator, title and duration, first location wins`() {
        val p = parse("a.xspf", xspfSample)

        val first = p.entries[0]
        assertEquals("file:///music/a.mp3", first.location)
        assertEquals("Fleetwood Mac", first.artist)
        assertEquals("Dreams", first.title)
        assertEquals(241_000L, first.durationMs)
        val second = p.entries[1]
        assertEquals("/storage/emulated/0/Music/b.flac", second.location)
        assertNull(second.artist)
        assertEquals("Only A Title", second.title)
        assertNull(second.durationMs)
    }

    @Test
    fun `XSPF entities and CDATA are decoded`() {
        val p = parse(
            "a.xspf",
            "<playlist><trackList><track><location>a.mp3</location>" +
                "<title>&amp; &lt; &gt; &quot; &apos; &#233; &#xE9;</title></track>" +
                "<track><location><![CDATA[C:\\Music\\a&b <x>.mp3]]></location></track>" +
                "</trackList></playlist>",
        )

        assertEquals("& < > \" ' \u00E9 \u00E9", p.entries[0].title)
        assertEquals("C:\\Music\\a&b <x>.mp3", p.entries[1].location)
    }

    @Test
    fun `XSPF tolerates namespace prefixes, attributes, self-closing tags and a track without a location`() {
        val p = parse(
            "a.xspf",
            "<x:playlist xmlns:x=\"http://xspf.org/ns/0/\" version=\"1\"><x:trackList>" +
                "<x:track id=\"1\"><x:location>a.mp3</x:location><x:duration/></x:track>" +
                "<x:track><x:title>No Location</x:title></x:track>" +
                "</x:trackList></x:playlist>",
        )

        assertEquals(listOf("a.mp3"), locations(p))
        assertNull(p.entries[0].durationMs)
    }

    @Test
    fun `XSPF never resolves external entities`() {
        val p = parse(
            "a.xspf",
            "<?xml version=\"1.0\"?><!DOCTYPE playlist [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>" +
                "<playlist><trackList><track><location>a.mp3</location><title>&xxe;</title></track></trackList></playlist>",
        )

        assertEquals("a.mp3", p.entries.single().location)
        // An entity the scanner does not know is left as written, never looked up.
        assertEquals("&xxe;", p.entries.single().title)
    }

    @Test
    fun `malformed PLS and XSPF never throw and keep what was read`() {
        val truncated = parse("a.xspf", "<playlist><trackList><track><location>a.mp3</location></track><track><location>b.m")
        assertEquals(listOf("a.mp3"), locations(truncated))

        assertNotNull(parse("a.xspf", "<playlist><trackList><track><location>"))
        assertNotNull(parse("a.xspf", "<playlist><!-- unterminated"))
        assertNotNull(parse("a.xspf", "<playlist><![CDATA[ unterminated"))
        assertNotNull(parse("a.xspf", "<"))
        assertNotNull(parse("a.xspf", "<playlist><title>&#99999999999;&#xZZ;&bogus;&</title></playlist>"))
        assertNotNull(PlaylistFileParser.parse("a.xspf", ByteArray(2048) { (it * 37 + 11).toByte() }))
        assertNotNull(PlaylistFileParser.parse("a.pls", ByteArray(2048) { (it * 37 + 11).toByte() }))
        assertNotNull(parse("a.pls", "[playlist]\nFile=x\nFile99999999999999=y\nLength1=abc\n=\n"))
    }

    // ── Limits and junk ──

    @Test
    fun `a file over 4 MiB is rejected with a reason`() {
        val p = PlaylistFileParser.parse("big.m3u", ByteArray(PlaylistFileParser.MAX_BYTES + 1) { 'a'.code.toByte() })

        assertTrue(p.entries.isEmpty())
        assertTrue(p.rejected?.isNotBlank() == true)
    }

    @Test
    fun `a file of exactly 4 MiB is still read`() {
        val line = "a.mp3\n"
        val body = line.repeat(PlaylistFileParser.MAX_BYTES / line.length)
        val p = PlaylistFileParser.parse("a.m3u", body.toByteArray(Charsets.US_ASCII))

        assertNull(p.rejected)
        assertEquals(PlaylistFileParser.MAX_ENTRIES, p.entries.size)
    }

    @Test
    fun `entries past the cap are dropped and counted`() {
        val text = (1..PlaylistFileParser.MAX_ENTRIES + 5).joinToString("\n") { "t$it.mp3" }
        val p = parse("a.m3u", text)

        assertEquals(PlaylistFileParser.MAX_ENTRIES, p.entries.size)
        assertEquals(5, p.dropped)
        assertEquals("t1.mp3", p.entries.first().location)
        assertEquals("t${PlaylistFileParser.MAX_ENTRIES}.mp3", p.entries.last().location)
    }

    @Test
    fun `malformed input never throws`() {
        val junk = ByteArray(2048) { (it * 37 + 11).toByte() }
        val p = PlaylistFileParser.parse("junk.m3u", junk)
        assertNotNull(p)

        assertNotNull(PlaylistFileParser.parse("empty.m3u", ByteArray(0)))
        assertTrue(PlaylistFileParser.parse("empty.m3u", ByteArray(0)).entries.isEmpty())
        // Truncated mid-directive and a lone BOM.
        assertNotNull(parse("a.m3u", "#EXTINF:"))
        assertNotNull(parse("a.m3u", "#EXTINF"))
        assertNotNull(PlaylistFileParser.parse("a.m3u", byteArrayOf(0xEF.toByte(), 0xBB.toByte())))
        assertNotNull(PlaylistFileParser.parse("a.m3u8", byteArrayOf(0xFF.toByte())))
    }
}
