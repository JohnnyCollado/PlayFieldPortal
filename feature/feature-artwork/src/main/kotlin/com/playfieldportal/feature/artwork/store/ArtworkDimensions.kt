package com.playfieldportal.feature.artwork.store

/**
 * A default artwork canvas: a *proportion*, not an output resolution. [width] and [height] are the
 * policy's stated canvas so the numbers stay checkable against the document, and [aspectRatio] is
 * what callers actually use. [sourceAspectPreferred] marks the platforms whose real packaging
 * varies enough by region or release that this canvas is only ever a last resort.
 *
 * Nothing here resizes an image. PFP stores the original and lets Compose scale it; a canvas only
 * answers "what shape should this be when the real dimensions are unknown".
 */
data class ArtworkCanvas(
    val width: Int,
    val height: Int,
    val sourceAspectPreferred: Boolean = false,
) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}

/**
 * The single box-art dimension table, from the Artwork Dimension & Aspect Ratio Policy
 * (`docs/PFP_Artwork_Dimensions_and_Aspect_Ratio_Policy.md`). One table, one policy: the XMB
 * placeholder, the Studio and any future box-art surface all resolve through here.
 *
 * The rule, in one line: **use the artwork's real dimensions when PFP knows them, and this table
 * only when it does not.** [boxArtAspect] encodes that ordering; [boxArt] is the preset alone, for
 * the placeholder path where source dimensions are absent by definition.
 *
 * Deliberately NOT the crop registry's platform tier ([CropProfileRegistry]). That resolves
 * *platform beats source*, which is right for a fixed crop target like ICON0 and wrong for box art
 * — so box art's platform rows live here and the registry's box-art platform tier stays empty.
 */
object ArtworkDimensions {

    /**
     * A common DVD-style keep case. The policy's choice for an unrecognized console: a better guess
     * than a square, because most post-CD packaging is a tall case.
     */
    val GenericBoxArt = ArtworkCanvas(430, 600)

    /** Resolves the default box-front canvas for [platformId], falling back to [GenericBoxArt]. */
    fun boxArt(platformId: String?): ArtworkCanvas =
        BOX_ART_CANVAS[canonicalize(platformId)] ?: GenericBoxArt

    /** Whether [platformId] has a row of its own, as opposed to landing on [GenericBoxArt]. */
    fun hasBoxArtPreset(platformId: String?): Boolean =
        BOX_ART_CANVAS.containsKey(canonicalize(platformId))

    /**
     * The box-art aspect to draw at: the source's own ratio whenever [sourceWidth] and
     * [sourceHeight] are both usable, and the platform preset only when they are not.
     */
    fun boxArtAspect(platformId: String?, sourceWidth: Int?, sourceHeight: Int?): Float {
        if (sourceWidth != null && sourceHeight != null && sourceWidth > 0 && sourceHeight > 0) {
            return sourceWidth.toFloat() / sourceHeight.toFloat()
        }
        return boxArt(platformId).aspectRatio
    }

    // Ids the tree has used for these platforms, mapped onto the policy's canonical ids. The policy
    // lists canonical ids only, so dropping these in the move would silently re-shape their
    // placeholders — they are here to prevent exactly that.
    private val ALIASES = mapOf(
        "ps1" to "psx",
        "sfc" to "snes",
        "dc" to "dreamcast",
        "nx" to "switch",
        "ds" to "nds",
        "3ds" to "n3ds",
        "ngpc" to "ngp",
    )

    private fun canonicalize(platformId: String?): String? {
        val id = platformId?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return ALIASES[id] ?: id
    }

    // Canvases are the policy's own numbers, verbatim. `sourceAspectPreferred` marks its
    // "Source-Aware Platforms" list — packaging that varies by region or has no consumer box at all.
    private val BOX_ART_CANVAS = mapOf(
        // ── Sony ────────────────────────────────────────────────────────────
        "psx" to ArtworkCanvas(600, 600),               // jewel case, square
        "ps2" to ArtworkCanvas(430, 600),
        "ps3" to ArtworkCanvas(480, 600),
        "psp" to ArtworkCanvas(354, 600),               // UMD case, narrow
        "psvita" to ArtworkCanvas(468, 600),            // wider than PSP — see the policy

        // ── Nintendo ────────────────────────────────────────────────────────
        "nes" to ArtworkCanvas(430, 600),
        "snes" to ArtworkCanvas(600, 438, sourceAspectPreferred = true),   // US landscape
        "n64" to ArtworkCanvas(600, 438, sourceAspectPreferred = true),    // US landscape
        "gb" to ArtworkCanvas(600, 600),
        "gbc" to ArtworkCanvas(600, 600),
        "gba" to ArtworkCanvas(600, 600),
        "nds" to ArtworkCanvas(540, 600),
        "n3ds" to ArtworkCanvas(540, 600),
        "gc" to ArtworkCanvas(430, 600),
        "wii" to ArtworkCanvas(430, 600),
        "wiiu" to ArtworkCanvas(430, 600),
        "switch" to ArtworkCanvas(366, 600),            // narrow vertical case
        "virtualboy" to ArtworkCanvas(600, 600),

        // ── Sega ────────────────────────────────────────────────────────────
        "megadrive" to ArtworkCanvas(430, 600),
        "mastersystem" to ArtworkCanvas(430, 600),
        "gamegear" to ArtworkCanvas(424, 600),
        "saturn" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),
        "dreamcast" to ArtworkCanvas(600, 600),
        "segacd" to ArtworkCanvas(420, 600, sourceAspectPreferred = true),
        "sega32x" to ArtworkCanvas(430, 600),

        // ── Atari ───────────────────────────────────────────────────────────
        "atari2600" to ArtworkCanvas(440, 600),
        "atari5200" to ArtworkCanvas(440, 600),
        "atari7800" to ArtworkCanvas(440, 600),
        "atarilynx" to ArtworkCanvas(488, 600, sourceAspectPreferred = true),

        // ── NEC ─────────────────────────────────────────────────────────────
        "pcengine" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),

        // ── SNK ─────────────────────────────────────────────────────────────
        "neogeo" to ArtworkCanvas(480, 600),
        "ngp" to ArtworkCanvas(600, 600),

        // ── Bandai ──────────────────────────────────────────────────────────
        "wonderswan" to ArtworkCanvas(600, 600),
        "wonderswancolor" to ArtworkCanvas(600, 600),

        // ── Commodore ───────────────────────────────────────────────────────
        "c64" to ArtworkCanvas(430, 600, sourceAspectPreferred = true),

        // ── Arcade: no consumer box format at all, so the square is only a safe placeholder ──
        "mame" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),
        "cps1" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),
        "cps2" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),
        "cps3" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),

        // ── Microsoft ───────────────────────────────────────────────────────
        "xbox" to ArtworkCanvas(430, 600),
        "x360" to ArtworkCanvas(430, 600),

        // ── PC / Android: no physical packaging standard ─────────────────────
        "windows" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),
        "android" to ArtworkCanvas(600, 600, sourceAspectPreferred = true),
    )
}
