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
}
