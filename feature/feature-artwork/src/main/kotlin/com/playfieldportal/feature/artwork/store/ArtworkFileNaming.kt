package com.playfieldportal.feature.artwork.store

import java.util.Locale

/**
 * Pure filename rules for the internal artwork layout — kept free of Android types so the
 * naming and prune-selection logic is unit-testable on the JVM.
 *
 * Layout (unchanged from pre-seam builds, so existing installs keep their files):
 *   artwork/{gameId}/icon.jpg | hero.jpg | background.jpg | logo.png     ← scraper (fixed)
 *   artwork/{gameId}/{kind}_{timestamp}.{ext}                            ← user picks (versioned)
 */
object ArtworkFileNaming {

    /** The well-known filename the scraper overwrites for [kind]. */
    fun fixedName(kind: ArtworkKind): String = when (kind) {
        ArtworkKind.ICON       -> "icon.jpg"
        ArtworkKind.HERO       -> "hero.jpg"
        ArtworkKind.BACKGROUND -> "background.jpg"
        ArtworkKind.LOGO       -> "logo.png"
        ArtworkKind.MANUAL     -> "manual.pdf"
        ArtworkKind.VIDEO      -> "video.mp4"
        ArtworkKind.SCREENSHOT     -> "screenshot.jpg"
        ArtworkKind.TITLESCREEN    -> "titlescreen.jpg"
        ArtworkKind.PHYSICAL_MEDIA -> "physicalmedia.png"
        ArtworkKind.BOX_ART        -> "boxart.jpg"
        ArtworkKind.BOX_3D         -> "box3d.png"
    }

    /**
     * The extension-less base name used in the portable library, where the real extension is
     * sniffed from the payload (`icon.png` and `icon.jpg` are both valid portable names).
     */
    fun baseName(kind: ArtworkKind): String = fixedName(kind).substringBeforeLast('.')

    /** A fresh timestamped filename for a user pick of [kind]. */
    fun versionedName(kind: ArtworkKind, ext: String, nowMillis: Long = System.currentTimeMillis()): String =
        "${kind.name.lowercase(Locale.US)}_$nowMillis.$ext"

    /**
     * True if [fileName] is an older artifact of [kind] that a new save should prune — any
     * versioned file of the kind, plus the legacy fixed name older builds wrote.
     */
    fun isPruneCandidate(kind: ArtworkKind, fileName: String): Boolean =
        fileName.startsWith("${kind.name.lowercase(Locale.US)}_") || fileName == fixedName(kind)
}
