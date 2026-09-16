package com.playfieldportal.feature.artwork.store

import com.playfieldportal.core.domain.model.GameRegion

/**
 * A resolved crop target: [key] is the stable, human-readable, parseable identifier the resolution
 * tier landed on ("ICON:psx:NTSC_U", "ICON:psx", "ICON", "original"); [aspect] is the target
 * width/height ratio, or **null** for Original Image — frame at the source's own ratio. Null rather
 * than a sentinel float so "no target" cannot be arithmetic'd by accident.
 */
data class CropProfile(val key: String, val aspect: Float?)

/**
 * Resolves a crop target for an artwork kind, with two more-specific overrides layered on top:
 * per-platform and per-platform-and-region. Resolution order (AD-14): kind -> platform ->
 * game region -> kind default -> Original Image. Artwork region (a ScreenScraper media region,
 * distinct from the game's own region) is deliberately absent from this order.
 *
 * Built from a plain `Map<String, Float>` keyed the same way [resolve] spells its keys, so the platform and region
 * tiers can be proven against an injected test table without shipping a guessed ratio to prove
 * them. [Default] carries the shipped table: kind defaults only.
 */
class CropProfileRegistry(private val entries: Map<String, Float>) {

    /** Resolves the crop target for [kind] on the game identified by [platformId] / [region]. */
    fun resolve(kind: ArtworkKind, platformId: String?, region: GameRegion?): CropProfile {
        val kindKey = kind.name
        val platformKey = platformId?.let { "$kindKey:$it" }
        val regionKey = if (platformId != null && region != null) "$kindKey:$platformId:${region.name}" else null

        regionKey?.let { key -> entries[key]?.let { return CropProfile(key, it) } }
        platformKey?.let { key -> entries[key]?.let { return CropProfile(key, it) } }
        entries[kindKey]?.let { return CropProfile(kindKey, it) }
        return CropProfile(ORIGINAL_KEY, null)
    }

    companion object {
        /** The key Original Image resolves to when no tier has an entry for the kind. */
        const val ORIGINAL_KEY = "original"

        /** The shipped table: kind defaults only. No platform or region rows ship in 6.1 — those
         *  tiers are built and tested, but every real row is a future data edit, not a code change. */
        val Default = CropProfileRegistry(
            mapOf(
                ArtworkKind.ICON.name to 144f / 80f,     // XMB tile container
                ArtworkKind.ICON1.name to 144f / 80f,
                ArtworkKind.HERO.name to 920f / 430f,
                ArtworkKind.BACKGROUND.name to 16f / 9f,
            )
        )
    }
}
