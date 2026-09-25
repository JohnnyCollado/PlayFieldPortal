package com.playfieldportal.feature.artwork.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsMediaSelectionTest {

    private fun m(type: String, region: String?, url: String) = SsCachedMedia(type, region, url)

    @Test
    fun `region preference is us then wor then untagged then anything`() {
        val medias = listOf(
            m("box-2D", "jp", "jp"), m("box-2D", null, "none"),
            m("box-2D", "wor", "wor"), m("box-2D", "us", "us"),
        )
        assertEquals("us", SsMediaSelection.bestUrl(medias, "box-2D"))
        assertEquals("wor", SsMediaSelection.bestUrl(medias.filter { it.url != "us" }, "box-2D"))
        assertEquals("none", SsMediaSelection.bestUrl(medias.filter { it.url == "jp" || it.url == "none" }, "box-2D"))
    }

    @Test
    fun `urls maps every kind with the canonical fallbacks`() {
        val medias = listOf(
            m("box-3D", "us", "b3"), m("support-texture", "us", "st"),
            m("ss", "us", "shot"), m("wheel-hd", "us", "whd"), m("video", "us", "raw"),
        )
        val u = SsMediaSelection.urls(medias)
        assertEquals("b3", u.artworkUrl)       // box-2D missing → box-3D fallback
        assertNull(u.boxArtUrl)                // strict box-2D only
        assertEquals("b3", u.box3dUrl)
        assertEquals("st", u.physicalMediaUrl) // support-2D missing → texture fallback
        assertEquals("shot", u.screenshotUrl)
        assertEquals("shot", u.heroUrl)        // fanart missing → ss fallback
        assertEquals("whd", u.logoUrl)         // wheel missing → wheel-hd fallback
        assertNull(u.videoUrl)                 // normalized only
        assertEquals("raw", u.videoRawUrl)
    }

    @Test
    fun `encode decode round-trips and rejects junk`() {
        val medias = listOf(m("box-2D", "us", "http://x/1.png"), m("fanart", null, "http://x/2.jpg"))
        val decoded = SsMediaSelection.decode(SsMediaSelection.encode(medias))
        assertEquals(medias, decoded)
        assertNull(SsMediaSelection.decode("not json"))
        assertNull(SsMediaSelection.decode("[]"))   // empty list = useless cache row
    }

    @Test
    fun `infoFromCache carries urls and no text metadata`() {
        val medias = listOf(m("box-2D", "us", "box"), m("fanart", "us", "fan"))
        val info = SsMediaSelection.infoFromCache(42L, medias)
        assertEquals(42L, info.ssId)
        assertEquals("box", info.boxArtUrl)
        assertEquals("fan", info.heroUrl)
        assertNull(info.description)
        assertNull(info.title)
        assertTrue(info.medias.isNotEmpty())
    }

    // ── Region rank (C22 task T6) ─────────────────────────────────────────────

    @Test
    fun `a user region is preferred over the shipped default`() {
        val medias = listOf(m("box-2D", "us", "us"), m("box-2D", "jp", "jp"), m("box-2D", "wor", "wor"))
        assertEquals("jp", SsMediaSelection.bestUrl(medias, "box-2D", userRegion = "jp"))
        // …and with no preference the order is exactly what it was before this setting existed.
        assertEquals("us", SsMediaSelection.bestUrl(medias, "box-2D"))
    }

    @Test
    fun `a user region falls back through the default walk when it has no media`() {
        val medias = listOf(m("box-2D", "wor", "wor"), m("box-2D", "eu", "eu"))
        assertEquals("wor", SsMediaSelection.bestUrl(medias, "box-2D", userRegion = "jp"))
    }

    @Test
    fun `bestMedia reports where the winner matched`() {
        val medias = listOf(m("box-2D", "eu", "eu"))
        val pick = SsMediaSelection.bestMedia(medias, "box-2D")
        assertEquals("eu", pick?.url)
        // us(0), wor(1), null(2), eu(3)
        assertEquals(3, pick?.regionPos)
    }

    @Test
    fun `an unlisted region still wins when it is all there is, and ranks last`() {
        val medias = listOf(m("box-2D", "kr", "kr"))
        val pick = SsMediaSelection.bestMedia(medias, "box-2D")
        assertEquals("kr", pick?.url)
        assertEquals(6, pick?.regionPos)
    }

    @Test
    fun `an empty list picks nothing`() {
        assertNull(SsMediaSelection.bestMedia(emptyList(), "box-2D"))
        assertNull(SsMediaSelection.bestUrl(emptyList(), "box-2D"))
    }

    @Test
    fun `a wheel-hd from a better region beats a wheel from a worse one`() {
        // The bug this fixes: logoUrl used to be `wheel ?: wheel-hd`, so ANY wheel won outright.
        val medias = listOf(m("wheel", "jp", "wheel-jp"), m("wheel-hd", "us", "wheelhd-us"))
        assertEquals("wheelhd-us", SsMediaSelection.urls(medias).logoUrl)
    }

    @Test
    fun `a wheel at an equal or better region still wins`() {
        val medias = listOf(m("wheel", "us", "wheel-us"), m("wheel-hd", "us", "wheelhd-us"))
        assertEquals("wheel-us", SsMediaSelection.urls(medias).logoUrl)
    }

    @Test
    fun `a 3D box only stands in when there is no 2D one, regardless of region`() {
        val medias = listOf(m("box-2D", "jp", "box2d-jp"), m("box-3D", "us", "box3d-us"))
        assertEquals("box2d-jp", SsMediaSelection.urls(medias).boxArtUrl)
        assertEquals("box2d-jp", SsMediaSelection.urls(medias).artworkUrl)
    }
}
