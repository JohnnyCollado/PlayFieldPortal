package com.playfieldportal.feature.artwork.portable

import com.playfieldportal.feature.artwork.store.ArtworkKind

/**
 * The library layout v3 — a clean two-folder root, ES-DE-shaped inside Artwork/:
 *
 *   {libraryRoot}/
 *     pfp-artwork-library.json
 *     Import/{Launcher}/…                              ← drop zone for other launchers' media
 *     Artwork/{platformId}/{mediaDir}/{PortableName}.{ext}
 *     Artwork/{platformId}/pfp/icon0/…                 ← PFP-only 144:80 icon art (ICON)
 *
 * (Point ES-DE or another frontend at {libraryRoot}/Artwork — the tree inside is exactly a
 * downloaded_media layout.)
 *
 * This object is the single source of the ArtworkKind ↔ media-directory mapping — the ES-DE
 * importer, the write path, relink, and the exporter all read it from here.
 */
object ArtworkPathResolver {

    /** PFP-only namespace under each platform — never holds standard ES-DE media types. */
    const val DIR_PFP = "pfp"

    /** ICON lives at `pfp/icon0` — 144:80 grids are a PFP-ism, not an ES-DE media type, so
     *  `covers/` keeps its ES-DE meaning (true box art = BOX_ART). */
    const val DIR_ICON0 = "$DIR_PFP/icon0"

    /** ICON1 (icon-slot video snap) is PFP-generated, not an ES-DE media type. */
    const val DIR_ICON1 = "$DIR_PFP/icon1"

    /** One-previous backup of an overwritten slot ("Restore Previous"). Never scanned/exported —
     *  `kindForMediaDir("pfp/versions")` is null, so import/scan skip it. */
    const val DIR_VERSIONS = "$DIR_PFP/versions"

    /** Untouched pre-crop copy so the crop editor can re-frame losslessly. Never scanned/exported. */
    const val DIR_ORIGINALS = "$DIR_PFP/originals"

    /** Segments of the versions namespace for [kind] ("pfp","versions","icon"). Kind-nested so a
     *  game's box-art and icon backups (same portable name) never collide. */
    fun versionsDirSegments(platformId: String, kind: ArtworkKind): List<String> =
        listOf(ArtworkLibraryManifest.DIR_ARTWORK, platformId, DIR_PFP, "versions", kind.name.lowercase())

    fun originalsDirSegments(platformId: String, kind: ArtworkKind): List<String> =
        listOf(ArtworkLibraryManifest.DIR_ARTWORK, platformId, DIR_PFP, "originals", kind.name.lowercase())

    private val KIND_TO_DIR: Map<ArtworkKind, String> = mapOf(
        ArtworkKind.ICON           to DIR_ICON0,
        ArtworkKind.ICON1          to DIR_ICON1,
        ArtworkKind.BOX_ART        to "covers",
        ArtworkKind.HERO           to "miximages",
        ArtworkKind.BACKGROUND     to "fanart",
        ArtworkKind.LOGO           to "marquees",
        ArtworkKind.SCREENSHOT     to "screenshots",
        ArtworkKind.TITLESCREEN    to "titlescreens",
        ArtworkKind.PHYSICAL_MEDIA to "physicalmedia",
        ArtworkKind.BOX_3D         to "3dboxes",
        ArtworkKind.MANUAL         to "manuals",
        ArtworkKind.VIDEO          to "videos",
    )

    private val DIR_TO_KIND: Map<String, ArtworkKind> =
        KIND_TO_DIR.entries.associate { (kind, dir) -> dir to kind }

    // ES-DE media dirs we recognize as library structure but do not import/write (yet).
    private val RESERVED_MEDIA_DIRS = setOf("backcovers")

    fun mediaDirFor(kind: ArtworkKind): String = KIND_TO_DIR.getValue(kind)

    fun kindForMediaDir(dirName: String): ArtworkKind? = DIR_TO_KIND[dirName.lowercase()]

    /** True for any directory name that is ES-DE media structure (mapped or reserved). */
    fun isMediaDirName(dirName: String): Boolean {
        val lower = dirName.lowercase()
        return lower in DIR_TO_KIND || lower in RESERVED_MEDIA_DIRS
    }

    /** Media types the ES-DE importer accepts. Videos import too — as locally transcoded
     *  ICON1 snaps, never as stored full-size files (see ArtworkImportExecutor). */
    val importedKinds: Set<ArtworkKind> = KIND_TO_DIR.keys

    /** Relative path of an asset inside the library ("Artwork/ps2/covers/Final Fantasy X (USA).png"). */
    fun relativePath(platformId: String, kind: ArtworkKind, fileName: String): String =
        "${ArtworkLibraryManifest.DIR_ARTWORK}/$platformId/${mediaDirFor(kind)}/$fileName"
}
