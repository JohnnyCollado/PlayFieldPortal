package com.playfieldportal.feature.artwork.portable

import com.playfieldportal.feature.artwork.store.ArtworkKind

/**
 * The library layout v2 — ES-DE-shaped, so the folder is directly usable by other frontends:
 *
 *   {libraryRoot}/
 *     pfp-media-library.json
 *     import/…                                ← drop zone (unchanged)
 *     {platformId}/{mediaDir}/{PortableName}.{ext}
 *     {platformId}/pfp/{icons,backgrounds,overlays,banners}/   ← PFP-only art, reserved
 *
 * This object is the single source of the ArtworkKind ↔ media-directory mapping — the ES-DE
 * importer, the write path, relink, and the future exporter all read it from here.
 */
object ArtworkPathResolver {

    /** PFP-only namespace under each platform — never holds standard media types. */
    const val DIR_PFP = "pfp"

    private val KIND_TO_DIR: Map<ArtworkKind, String> = mapOf(
        ArtworkKind.ICON           to "covers",
        ArtworkKind.HERO           to "miximages",
        ArtworkKind.BACKGROUND     to "fanart",
        ArtworkKind.LOGO           to "marquees",
        ArtworkKind.SCREENSHOT     to "screenshots",
        ArtworkKind.TITLESCREEN    to "titlescreens",
        ArtworkKind.PHYSICAL_MEDIA to "physicalmedia",
        ArtworkKind.MANUAL         to "manuals",
        ArtworkKind.VIDEO          to "videos",
    )

    private val DIR_TO_KIND: Map<String, ArtworkKind> =
        KIND_TO_DIR.entries.associate { (kind, dir) -> dir to kind }

    // ES-DE media dirs we recognize as library structure but do not import/write (yet).
    private val RESERVED_MEDIA_DIRS = setOf("3dboxes", "backcovers")

    fun mediaDirFor(kind: ArtworkKind): String = KIND_TO_DIR.getValue(kind)

    fun kindForMediaDir(dirName: String): ArtworkKind? = DIR_TO_KIND[dirName.lowercase()]

    /** True for any directory name that is ES-DE media structure (mapped or reserved). */
    fun isMediaDirName(dirName: String): Boolean {
        val lower = dirName.lowercase()
        return lower in DIR_TO_KIND || lower in RESERVED_MEDIA_DIRS
    }

    /** Media types imported/written in V1 (video snaps stay out for now). */
    val importedKinds: Set<ArtworkKind> = KIND_TO_DIR.keys - ArtworkKind.VIDEO

    /** Relative path of an asset inside the library ("ps2/covers/Final Fantasy X (USA).png"). */
    fun relativePath(platformId: String, kind: ArtworkKind, fileName: String): String =
        "$platformId/${mediaDirFor(kind)}/$fileName"
}
