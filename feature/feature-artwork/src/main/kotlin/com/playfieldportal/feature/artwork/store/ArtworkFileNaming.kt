package com.playfieldportal.feature.artwork.store

import java.util.Locale

/**
 * Pure filename rules for the internal artwork layout — kept free of Android types so the
 * naming and prune-selection logic is unit-testable on the JVM.
 *
 * Layout (unchanged from pre-seam builds, so existing installs keep their files):
 *   artwork/{gameId}/icon.jpg | hero.jpg | background.jpg | logo.png     ← scraper (fixed)
 *   artwork/{gameId}/{kind}_{timestamp}.{ext}                            ← user picks (versioned)
 *   artwork/{gameId}/screenshot_01.jpg | video_01.mp4                    ← extra assets (ordinal)
 *
 * ## Ordinals (C16 task 0.1)
 *
 * Kinds in [MULTI_ASSET_KINDS] may hold several assets at once. Their position is the record's
 * `sort_order`, and — because `ArtworkRecordEntity`'s contract is that *the folder is the source
 * of truth and Relink/Scan can rebuild rows* — that position has to survive in the filename too:
 *
 *   sort order 0 → `screenshot.jpg`      (the legacy bare name; existing installs never move)
 *   sort order 1 → `screenshot_01.jpg`
 *   sort order 2 → `screenshot_02.jpg`
 *
 * The same `_NN` suffix shapes portable names ("Final Fantasy X (USA)_01"), so one rule covers
 * both layouts. It is deliberately EXACTLY two digits: [versionedName]'s timestamps are 13, so
 * the two namespaces cannot be confused, and ordinals are only ever read back for kinds that
 * support multiples.
 */
object ArtworkFileNaming {

    /** Kinds that may hold several assets at once (AD-2: only VIDEO joins SCREENSHOT). */
    val MULTI_ASSET_KINDS: Set<ArtworkKind> = setOf(ArtworkKind.SCREENSHOT, ArtworkKind.VIDEO)

    /** Highest position an ordinal filename can express. */
    const val MAX_SORT_ORDER = 99

    /** A trailing "_07" on a name stem — exactly two digits, so a 13-digit timestamp never matches. */
    private val ORDINAL_SUFFIX = Regex("_(\\d{2})$")

    fun supportsMultiple(kind: ArtworkKind): Boolean = kind in MULTI_ASSET_KINDS

    /**
     * The well-known filename the scraper overwrites for [kind] at [sortOrder].
     * Position 0 is always the historic bare name — that is the on-disk contract with every
     * install that predates ordinals.
     */
    fun fixedName(kind: ArtworkKind, sortOrder: Int = 0): String {
        val bare = bareFixedName(kind)
        if (sortOrder <= 0) return bare
        return "${withOrdinal(bare.substringBeforeLast('.'), sortOrder)}.${bare.substringAfterLast('.')}"
    }

    private fun bareFixedName(kind: ArtworkKind): String = when (kind) {
        ArtworkKind.ICON       -> "icon.jpg"
        ArtworkKind.HERO       -> "hero.jpg"
        ArtworkKind.BACKGROUND -> "background.jpg"
        ArtworkKind.LOGO       -> "logo.png"
        ArtworkKind.MANUAL     -> "manual.pdf"
        ArtworkKind.VIDEO      -> "video.mp4"
        ArtworkKind.ICON1      -> "icon1.mp4"
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
    fun baseName(kind: ArtworkKind, sortOrder: Int = 0): String =
        fixedName(kind, sortOrder).substringBeforeLast('.')

    /** A fresh timestamped filename for a user pick of [kind]. */
    fun versionedName(kind: ArtworkKind, ext: String, nowMillis: Long = System.currentTimeMillis()): String =
        "${kind.name.lowercase(Locale.US)}_$nowMillis.$ext"

    // ── Ordinals ──────────────────────────────────────────────────────────────

    /** "Final Fantasy X (USA)" at position 2 → "Final Fantasy X (USA)_02"; position 0 is bare. */
    fun withOrdinal(stem: String, sortOrder: Int): String =
        if (sortOrder <= 0) stem else "${stem}_%02d".format(Locale.US, sortOrder.coerceAtMost(MAX_SORT_ORDER))

    /** The position encoded in [stem], or 0 when it carries no ordinal suffix. */
    fun ordinalOf(stem: String): Int =
        ORDINAL_SUFFIX.find(stem)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..MAX_SORT_ORDER } ?: 0

    /** [stem] without its ordinal suffix — "Zelda_03" → "Zelda"; "Zelda" → "Zelda". */
    fun stripOrdinal(stem: String): String = ORDINAL_SUFFIX.replace(stem, "")

    /**
     * The sort order [fileName] encodes for [kind], or null when the file is not an asset of
     * that kind at all. Single-art kinds always resolve to 0 — their names never carry an
     * ordinal, so a ROM stem that happens to end in "_07" can never be misread.
     */
    fun sortOrderFromFileName(kind: ArtworkKind, fileName: String): Int? {
        val stem = fileName.substringBeforeLast('.')
        val base = baseName(kind)
        if (stem == base) return 0
        if (!supportsMultiple(kind)) return null
        val ordinal = ordinalOf(stem)
        return if (ordinal > 0 && stripOrdinal(stem) == base) ordinal else null
    }

    /**
     * True if [fileName] is an older artifact that a save at [sortOrder] should prune.
     *
     * Ordinal-aware (C16 AD-1): a save at position 1 may only replace position 1's own files, so
     * writing screenshot #2 can never delete screenshot #1's bytes. Versioned user-pick files
     * carry no ordinal and belong to the primary slot, so only a position-0 save prunes them.
     */
    fun isPruneCandidate(kind: ArtworkKind, fileName: String, sortOrder: Int = 0): Boolean {
        if (fileName == fixedName(kind, sortOrder)) return true
        if (sortOrder > 0) return false
        // Position 0: the legacy versioned namespace ("icon_1718000000.jpg"), minus any file
        // that is really a sibling ordinal of a multi-asset kind.
        if (!fileName.startsWith("${kind.name.lowercase(Locale.US)}_")) return false
        return sortOrderFromFileName(kind, fileName).let { it == null || it == 0 }
    }
}
