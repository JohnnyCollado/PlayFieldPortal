package com.playfieldportal.feature.artwork.card

import android.graphics.RectF

// Scale mode applied when fitting source artwork into the artwork window.
enum class ArtworkScaleMode {
    // Fill the window completely — crops edges if aspect ratio doesn't match.
    CROP,
    // Fit entirely inside the window — letterboxes/pillarboxes if needed.
    CONTAIN,
}

/**
 * Describes how artwork is composited for one platform's frontend card.
 *
 * All dimensions are in pixels (device-independent sizing is handled at the
 * display layer by Compose).
 *
 * [cardW] / [cardH]     — final output PNG dimensions.
 * [artWindow]           — region inside the card where artwork is placed.
 * [scaleMode]           — CROP (fills window) or CONTAIN (fits inside).
 * [bgColor]             — ARGB int drawn behind artwork (transparent by default).
 * [preserveAlpha]       — keep ARGB_8888 alpha in the output (logos).
 * [logoScaleMode]       — override for logo/icon assets on this platform.
 */
data class PlatformCardTemplate(
    val platformId: String,
    val cardW: Int,
    val cardH: Int,
    val artWindow: RectF,
    val scaleMode: ArtworkScaleMode,
    val bgColor: Int = 0xFF0A0A12.toInt(),
    val preserveAlpha: Boolean = false,
    val logoScaleMode: ArtworkScaleMode = ArtworkScaleMode.CONTAIN,
)

object CardTemplateRegistry {

    // Helper so we don't repeat `RectF(0f, 0f, w.toFloat(), h.toFloat())` everywhere.
    private fun fullWindow(w: Int, h: Int) = RectF(0f, 0f, w.toFloat(), h.toFloat())

    // padding(px) shorthand — insets all four edges equally
    private fun inset(w: Int, h: Int, px: Int) =
        RectF(px.toFloat(), px.toFloat(), (w - px).toFloat(), (h - px).toFloat())

    private val templates: Map<String, PlatformCardTemplate> = mapOf(

        // ── Sony Portable ────────────────────────────────────────────────────
        // PPSSPP-style UMD card: exactly 288×160 (16:9, 2× the 144×80 ICON0)
        "psp" to PlatformCardTemplate(
            platformId = "psp",
            cardW      = 288, cardH = 160,
            artWindow  = fullWindow(288, 160),
            scaleMode  = ArtworkScaleMode.CROP,
            bgColor    = 0xFF0A0A12.toInt(),
        ),
        "psvita" to PlatformCardTemplate(
            platformId = "psvita",
            cardW      = 288, cardH = 160,
            artWindow  = fullWindow(288, 160),
            scaleMode  = ArtworkScaleMode.CROP,
        ),

        // ── Sony Console ─────────────────────────────────────────────────────
        // PS1/PS2/PS3: vertical jewel case
        "psx" to PlatformCardTemplate(
            platformId = "psx",
            cardW      = 192, cardH = 272,
            artWindow  = inset(192, 272, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        "ps2" to PlatformCardTemplate(
            platformId = "ps2",
            cardW      = 192, cardH = 272,
            artWindow  = inset(192, 272, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        "ps3" to PlatformCardTemplate(
            platformId = "ps3",
            cardW      = 192, cardH = 272,
            artWindow  = inset(192, 272, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),

        // ── Nintendo Handheld ────────────────────────────────────────────────
        // Game Boy: vertical cartridge label — 3:4 proportion
        "gb" to PlatformCardTemplate(
            platformId = "gb",
            cardW      = 192, cardH = 256,
            artWindow  = RectF(12f, 64f, 180f, 220f),   // label region, leaving PCB border
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF5A5A5A.toInt(),             // dark grey cart body
        ),
        "gbc" to PlatformCardTemplate(
            platformId = "gbc",
            cardW      = 192, cardH = 256,
            artWindow  = RectF(12f, 64f, 180f, 220f),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF3A3A3A.toInt(),
        ),
        // GBA: wide horizontal label — 3.2:1 proportion
        "gba" to PlatformCardTemplate(
            platformId = "gba",
            cardW      = 288, cardH = 96,
            artWindow  = RectF(8f, 8f, 176f, 88f),       // left-hand label window
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF1A1A2A.toInt(),
        ),
        // NDS: vertical box
        "nds" to PlatformCardTemplate(
            platformId = "nds",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        // 3DS: same proportions as NDS
        "n3ds" to PlatformCardTemplate(
            platformId = "n3ds",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),

        // ── Nintendo Console ─────────────────────────────────────────────────
        // NES: square-ish cartridge label
        "nes" to PlatformCardTemplate(
            platformId = "nes",
            cardW      = 224, cardH = 256,
            artWindow  = RectF(10f, 56f, 214f, 210f),    // label sits below a title bar region
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF1A1A1A.toInt(),
        ),
        // SNES: vertical cartridge
        "snes" to PlatformCardTemplate(
            platformId = "snes",
            cardW      = 192, cardH = 256,
            artWindow  = RectF(10f, 48f, 182f, 220f),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF202020.toInt(),
        ),
        // N64: horizontal cartridge
        "n64" to PlatformCardTemplate(
            platformId = "n64",
            cardW      = 256, cardH = 192,
            artWindow  = inset(256, 192, 12),
            scaleMode  = ArtworkScaleMode.CROP,
            bgColor    = 0xFF1A1A1A.toInt(),
        ),
        // GameCube: square-ish case
        "gc" to PlatformCardTemplate(
            platformId = "gc",
            cardW      = 224, cardH = 224,
            artWindow  = inset(224, 224, 10),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        // Wii: standard box
        "wii" to PlatformCardTemplate(
            platformId = "wii",
            cardW      = 192, cardH = 272,
            artWindow  = inset(192, 272, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        "wiiu" to PlatformCardTemplate(
            platformId = "wiiu",
            cardW      = 192, cardH = 272,
            artWindow  = inset(192, 272, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        "switch" to PlatformCardTemplate(
            platformId = "switch",
            cardW      = 192, cardH = 272,
            artWindow  = inset(192, 272, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        "virtualboy" to PlatformCardTemplate(
            platformId = "virtualboy",
            cardW      = 224, cardH = 256,
            artWindow  = inset(224, 256, 12),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF1A0000.toInt(),
        ),

        // ── Sega ─────────────────────────────────────────────────────────────
        "megadrive" to PlatformCardTemplate(
            platformId = "megadrive",
            cardW      = 192, cardH = 272,
            artWindow  = RectF(10f, 32f, 182f, 240f),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF1A1A1A.toInt(),
        ),
        "mastersystem" to PlatformCardTemplate(
            platformId = "mastersystem",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 10),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF0A0A1A.toInt(),
        ),
        "gamegear" to PlatformCardTemplate(
            platformId = "gamegear",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 10),
            scaleMode  = ArtworkScaleMode.CONTAIN,
        ),
        "saturn" to PlatformCardTemplate(
            platformId = "saturn",
            cardW      = 192, cardH = 272,
            artWindow  = inset(192, 272, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        "dreamcast" to PlatformCardTemplate(
            platformId = "dreamcast",
            cardW      = 192, cardH = 272,
            artWindow  = inset(192, 272, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        "segacd" to PlatformCardTemplate(
            platformId = "segacd",
            cardW      = 192, cardH = 272,
            artWindow  = inset(192, 272, 8),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
        "sega32x" to PlatformCardTemplate(
            platformId = "sega32x",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 10),
            scaleMode  = ArtworkScaleMode.CONTAIN,
        ),

        // ── Atari ────────────────────────────────────────────────────────────
        "atari2600" to PlatformCardTemplate(
            platformId = "atari2600",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 12),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF1A0A00.toInt(),
        ),
        "atari5200" to PlatformCardTemplate(
            platformId = "atari5200",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 12),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF1A0A00.toInt(),
        ),
        "atari7800" to PlatformCardTemplate(
            platformId = "atari7800",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 12),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF1A0A00.toInt(),
        ),
        "atarilynx" to PlatformCardTemplate(
            platformId = "atarilynx",
            cardW      = 288, cardH = 160,
            artWindow  = inset(288, 160, 10),
            scaleMode  = ArtworkScaleMode.CONTAIN,
        ),

        // ── PC Engine / TurboGrafx ───────────────────────────────────────────
        "pcengine" to PlatformCardTemplate(
            platformId = "pcengine",
            cardW      = 192, cardH = 224,
            artWindow  = inset(192, 224, 10),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF0A0A1A.toInt(),
        ),

        // ── Neo Geo ──────────────────────────────────────────────────────────
        "neogeo" to PlatformCardTemplate(
            platformId = "neogeo",
            cardW      = 288, cardH = 192,
            artWindow  = inset(288, 192, 10),
            scaleMode  = ArtworkScaleMode.CONTAIN,
            bgColor    = 0xFF0A001A.toInt(),
        ),
        "ngp" to PlatformCardTemplate(
            platformId = "ngp",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 10),
            scaleMode  = ArtworkScaleMode.CONTAIN,
        ),

        // ── Arcade ───────────────────────────────────────────────────────────
        // MAME: wide marquee format
        "mame" to PlatformCardTemplate(
            platformId     = "mame",
            cardW          = 320, cardH = 112,
            artWindow      = inset(320, 112, 8),
            scaleMode      = ArtworkScaleMode.CONTAIN,
            bgColor        = 0xFF0A0A0A.toInt(),
            preserveAlpha  = false,
            logoScaleMode  = ArtworkScaleMode.CONTAIN,
        ),

        // ── Handheld — WonderSwan / C64 ──────────────────────────────────────
        "wonderswan" to PlatformCardTemplate(
            platformId = "wonderswan",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 10),
            scaleMode  = ArtworkScaleMode.CONTAIN,
        ),
        "wonderswancolor" to PlatformCardTemplate(
            platformId = "wonderswancolor",
            cardW      = 192, cardH = 256,
            artWindow  = inset(192, 256, 10),
            scaleMode  = ArtworkScaleMode.CONTAIN,
        ),
        "c64" to PlatformCardTemplate(
            platformId = "c64",
            cardW      = 224, cardH = 288,
            artWindow  = inset(224, 288, 12),
            scaleMode  = ArtworkScaleMode.CROP,
        ),
    )

    // Generic horizontal card used when no specific template is registered.
    private val default = PlatformCardTemplate(
        platformId = "default",
        cardW      = 288, cardH = 200,
        artWindow  = fullWindow(288, 200),
        scaleMode  = ArtworkScaleMode.CROP,
    )

    fun forPlatform(platformId: String): PlatformCardTemplate =
        templates[platformId] ?: default
}
