package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.feature.artwork.store.ArtworkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C16 tasks 6.2 and 6.6 — which kinds get a live crop preview, and in which chrome.
 *
 * The resolver is pure so it pins here without Compose or Robolectric; the drawing itself is
 * covered by the device check, since a bitmap blit has nothing a unit test can assert on.
 */
class StudioCropPreviewTest {

    // ── The six kinds with a slot to preview into ─────────────────────────────

    @Test
    fun `ICON0 and box art wear the framed PSP tile`() {
        assertEquals(CropPreviewChrome.PSP_TILE, cropPreviewChromeFor(ArtworkKind.ICON))
        assertEquals(CropPreviewChrome.PSP_TILE, cropPreviewChromeFor(ArtworkKind.BOX_ART))
    }

    // ICON1 is the XMB icon slot in motion, so it wears the slot's chrome; VIDEO plays in the Game
    // Details media strip, which draws no frame at all (task 6.6).
    @Test
    fun `the video kinds preview in the chrome of the slot they play in`() {
        assertEquals(CropPreviewChrome.PSP_TILE, cropPreviewChromeFor(ArtworkKind.ICON1))
        assertEquals(CropPreviewChrome.FRAMELESS, cropPreviewChromeFor(ArtworkKind.VIDEO))
    }

    // 3D boxes and physical media are transparent silhouettes, not opaque rectangles: the real
    // tile (NaturalAspectArtIcon, framed only for the box-art uri) draws no frame, so neither
    // does the preview. A frame here would invent a rectangle the tile never shows.
    @Test
    fun `3D box and physical media are frameless`() {
        assertEquals(CropPreviewChrome.FRAMELESS, cropPreviewChromeFor(ArtworkKind.BOX_3D))
        assertEquals(CropPreviewChrome.FRAMELESS, cropPreviewChromeFor(ArtworkKind.PHYSICAL_MEDIA))
    }

    // ── Everything else gets no inset ─────────────────────────────────────────

    @Test
    fun `kinds with no tile representation show no preview`() {
        val noPreview = listOf(
            ArtworkKind.HERO,
            ArtworkKind.BACKGROUND,
            ArtworkKind.LOGO,
            ArtworkKind.SCREENSHOT,
            ArtworkKind.TITLESCREEN,
            ArtworkKind.MANUAL,
        )
        noPreview.forEach { kind ->
            assertNull("$kind must not preview a tile it never renders", cropPreviewChromeFor(kind))
        }
    }

    // A new ArtworkKind must fall through to "no preview" rather than borrow a wrong treatment,
    // so the resolver is total by construction and never throws.
    @Test
    fun `resolver is total over ArtworkKind`() {
        ArtworkKind.entries.forEach { cropPreviewChromeFor(it) }
    }

    @Test
    fun `exactly six kinds preview`() {
        val previewing = ArtworkKind.entries.filter { cropPreviewChromeFor(it) != null }
        assertEquals(
            listOf(
                ArtworkKind.ICON,
                ArtworkKind.ICON1,
                ArtworkKind.PHYSICAL_MEDIA,
                ArtworkKind.BOX_ART,
                ArtworkKind.BOX_3D,
                ArtworkKind.VIDEO,
            ).sortedBy { it.ordinal },
            previewing.sortedBy { it.ordinal },
        )
    }

    // The two video kinds are exactly the two the ViewModel crops as video (isVideoKind), so the
    // playing inset can never be offered for a kind that has no clip behind it.
    @Test
    fun `both video-cropped kinds preview`() {
        listOf(ArtworkKind.ICON1, ArtworkKind.VIDEO).forEach { kind ->
            assertNotNull("$kind is cropped as video and must preview", cropPreviewChromeFor(kind))
        }
    }

    // ── Captions travel with the chrome ───────────────────────────────────────

    @Test
    fun `every previewing kind has a caption and no other kind does`() {
        ArtworkKind.entries.forEach { kind ->
            val caption = cropPreviewCaptionFor(kind)
            if (cropPreviewChromeFor(kind) == null) {
                assertNull("$kind previews nothing, so it needs no caption", caption)
            } else {
                assertNotNull("$kind previews, so it needs a caption", caption)
                assertTrue("$kind caption must not be blank", caption!!.isNotBlank())
            }
        }
    }

    @Test
    fun `captions name the tile the preview stands for`() {
        assertEquals("XMB tile", cropPreviewCaptionFor(ArtworkKind.ICON))
        assertEquals("Box Art tile", cropPreviewCaptionFor(ArtworkKind.BOX_ART))
        assertEquals("3D Box tile", cropPreviewCaptionFor(ArtworkKind.BOX_3D))
        assertEquals("Phys. Media tile", cropPreviewCaptionFor(ArtworkKind.PHYSICAL_MEDIA))
        assertEquals("XMB icon animation", cropPreviewCaptionFor(ArtworkKind.ICON1))
        assertEquals("Media strip", cropPreviewCaptionFor(ArtworkKind.VIDEO))
    }
}
