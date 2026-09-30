package com.playfieldportal.feature.artwork.video

import com.playfieldportal.feature.artwork.store.ArtworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading Steam's HLS trailer playlists (the shapes are copied from a live trailer, 2026-09-29)
 * and deciding what each Studio tab downloads from them.
 */
class HlsPlaylistTest {

    private val masterUrl = "https://video.akamai.steamstatic.com/store_trailers/524220/210992/beb9/1750543704/" +
        "hls_264_master.m3u8?t=1551200572"

    private val master = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",NAME="Default",AUTOSELECT=YES,DEFAULT=YES,URI="hls_264_4_audio.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=5800000,CODECS="avc1.640029,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=30,AUDIO="audio"
        hls_264_0_video.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2600000,CODECS="avc1.640029,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=30,AUDIO="audio"
        hls_264_1_video.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1400000,CODECS="avc1.640029,mp4a.40.2",RESOLUTION=854x480,FRAME-RATE=30,AUDIO="audio"
        hls_264_2_video.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1000000,CODECS="avc1.640029,mp4a.40.2",RESOLUTION=640x360,FRAME-RATE=30,AUDIO="audio"
        hls_264_3_video.m3u8
    """.trimIndent()

    private val media = """
        #EXTM3U
        #EXT-X-VERSION:7
        #EXT-X-TARGETDURATION:3
        #EXT-X-MEDIA-SEQUENCE:0
        #EXT-X-MAP:URI="dash_h264/init-stream1.m4s"
        #EXTINF:3,
        dash_h264/chunk-stream1-00001.m4s
        #EXTINF:3,
        dash_h264/chunk-stream1-00002.m4s
        #EXTINF:2.5,
        dash_h264/chunk-stream1-00003.m4s
        #EXT-X-ENDLIST
    """.trimIndent()

    // ── Master playlist ───────────────────────────────────────────────────────

    @Test
    fun `the master lists every rendition with its height, and the audio track`() {
        val parsed = HlsMaster.parse(master)

        assertEquals(listOf(1080, 720, 480, 360), parsed.variants.map { it.height })
        assertEquals("hls_264_1_video.m3u8", parsed.variants[1].uri)
        assertEquals("hls_264_4_audio.m3u8", parsed.audioUri)
    }

    @Test
    fun `the tallest rendition within the cap is picked`() {
        val parsed = HlsMaster.parse(master)

        assertEquals(720, parsed.pick(maxHeight = 720)?.height)
        assertEquals(360, parsed.pick(maxHeight = 360)?.height)
        assertEquals(1080, parsed.pick(maxHeight = 4000)?.height)
    }

    @Test
    fun `a cap below every rendition takes the smallest rather than nothing`() {
        assertEquals(360, HlsMaster.parse(master).pick(maxHeight = 100)?.height)
    }

    @Test
    fun `a rendition with no resolution is ranked by bandwidth`() {
        val bare = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=900\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000\nhigh.m3u8"

        assertEquals("high.m3u8", HlsMaster.parse(bare).pick(maxHeight = 720)?.uri)
    }

    @Test
    fun `an empty master picks nothing`() {
        assertNull(HlsMaster.parse("#EXTM3U").pick(maxHeight = 720))
    }

    // ── Media playlist ────────────────────────────────────────────────────────

    @Test
    fun `a media playlist gives its init segment and its chunks in order`() {
        val parsed = HlsMedia.parse(media)

        assertEquals("dash_h264/init-stream1.m4s", parsed.initUri)
        assertEquals(
            listOf("dash_h264/chunk-stream1-00001.m4s", "dash_h264/chunk-stream1-00002.m4s", "dash_h264/chunk-stream1-00003.m4s"),
            parsed.segments.map { it.uri },
        )
        assertEquals(8.5, parsed.segments.sumOf { it.seconds }, 0.001)
    }

    @Test
    fun `a time limit keeps whole chunks until it is reached`() {
        val parsed = HlsMedia.parse(media)

        assertEquals(2, parsed.within(maxSeconds = 6.0).segments.size)
        // A chunk that crosses the limit is kept: a snap a little long beats one cut short.
        assertEquals(2, parsed.within(maxSeconds = 4.0).segments.size)
        assertEquals(3, parsed.within(maxSeconds = null).segments.size)
    }

    // ── URLs ──────────────────────────────────────────────────────────────────

    @Test
    fun `playlist entries resolve against the playlist's folder, without its query`() {
        assertEquals(
            "https://video.akamai.steamstatic.com/store_trailers/524220/210992/beb9/1750543704/hls_264_1_video.m3u8",
            HlsUrls.resolve(masterUrl, "hls_264_1_video.m3u8"),
        )
        assertEquals(
            "https://video.akamai.steamstatic.com/store_trailers/524220/210992/beb9/1750543704/dash_h264/init-stream1.m4s",
            HlsUrls.resolve(HlsUrls.resolve(masterUrl, "hls_264_1_video.m3u8"), "dash_h264/init-stream1.m4s"),
        )
        assertEquals("https://other/abs.m4s", HlsUrls.resolve(masterUrl, "https://other/abs.m4s"))
    }

    @Test
    fun `only an m3u8 address is an HLS stream`() {
        assertTrue(HlsUrls.isHls(masterUrl))
        assertFalse(HlsUrls.isHls("https://video.akamai.steamstatic.com/store_trailers/1/microtrailer.mp4"))
        assertFalse(HlsUrls.isHls("https://example.com/m3u8/cover.jpg"))
    }

    // ── What each tab downloads ───────────────────────────────────────────────

    @Test
    fun `the Video tab takes the whole trailer at 720p with its sound`() {
        assertEquals(HlsTrailerPlan(maxHeight = 720, withAudio = true, maxSeconds = null), HlsTrailerPlan.forKind(ArtworkKind.VIDEO))
    }

    @Test
    fun `ICON1 takes a silent first minute from the smallest rendition, like any other snap`() {
        assertEquals(HlsTrailerPlan(maxHeight = 360, withAudio = false, maxSeconds = 60.0), HlsTrailerPlan.forKind(ArtworkKind.ICON1))
    }

    @Test
    fun `no still-image tab downloads a stream`() {
        assertNull(HlsTrailerPlan.forKind(ArtworkKind.BOX_ART))
        assertNull(HlsTrailerPlan.forKind(ArtworkKind.SCREENSHOT))
    }
}
