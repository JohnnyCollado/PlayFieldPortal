package com.playfieldportal.feature.artwork.store

import android.net.Uri

/**
 * The artwork asset kinds a game can have. Each maps to the well-known filename the scraper
 * writes ([ArtworkFileNaming.fixedName]) and the prefix manual picks version under.
 * MANUAL/VIDEO/ICON1 are ScreenScraper-only extras (PDF manual, full video, icon snap) — stored
 * like any other asset but never referenced from game columns; their paths derive from names.
 *
 * VIDEO is the full gameplay video (Game Detail media strip); ICON1 is the short muted snap
 * played in the XMB icon slot (PSP ICON1.PMF). They are distinct: ICON1 is generated from a
 * full video when ScreenScraper has no ready-made normalized snap — the full video is always
 * kept untouched as VIDEO.
 */
enum class ArtworkKind {
    ICON,
    HERO,
    BACKGROUND,
    LOGO,
    MANUAL,
    VIDEO,
    ICON1,

    // Imported-but-not-yet-displayed kinds (ES-DE screenshots/titlescreens). Stored like
    // MANUAL/VIDEO — resolved by fixed filename, never referenced from game columns — so
    // future themes can adopt them with zero schema changes.
    SCREENSHOT,
    TITLESCREEN,

    // Icon-display-mode kinds: column-backed alternatives to ICON for the XMB tile
    // (physical_media_uri / box_art_uri / box3d_uri). PHYSICAL_MEDIA predates the other two
    // (ES-DE import-only at first), so it keeps its original position-independent name.
    PHYSICAL_MEDIA,
    BOX_ART,
    BOX_3D,
}

/**
 * The single place artwork bytes are saved, validated, and deleted.
 *
 * Callers (scraper, detail-screen pickers) never build filenames or paths — they hand the store
 * a source (URL or SAF uri) and persist the returned reference string on the game row. The
 * reference is whatever the active storage backend wants the UI to load (today: an absolute
 * internal file path; later: a portable content:// document URI).
 *
 * Two save families:
 *  • [saveFromUrl] — scraper path. Fixed per-kind filename, overwritten in place.
 *  • [saveVersionedFromUrl] / [saveVersionedFromUri] — user picks. A fresh timestamped filename
 *    on every save is what makes the change visible immediately (the DB row, Compose keys, and
 *    Coil's cache key all follow the path string); older files of the same kind are pruned so
 *    versions never accumulate. The prune runs before the caller updates the DB row, so there is
 *    a brief window where the row still names the just-deleted previous file — harmless and
 *    self-healing (stale refs are detected by [isValidRef] and re-scraped).
 */
interface ArtworkStore {

    /** Downloads [url] and stores it under [kind]'s fixed name. Returns the reference, or null. */
    suspend fun saveFromUrl(gameId: Long, kind: ArtworkKind, url: String): String?

    /** Downloads [url] to a fresh versioned name for [kind], pruning older versions. */
    suspend fun saveVersionedFromUrl(gameId: Long, kind: ArtworkKind, url: String): String?

    /** Copies a user-picked document to a fresh versioned name for [kind], pruning older versions. */
    suspend fun saveVersionedFromUri(gameId: Long, kind: ArtworkKind, uri: Uri): String?

    /**
     * Stores a locally produced file (e.g. a transcoded video snap) under [kind]'s fixed name,
     * riding the same validation/naming path as a scrape. Consumes [tempFile] either way.
     */
    suspend fun saveFromFile(gameId: Long, kind: ArtworkKind, tempFile: java.io.File): String?

    /** True if [ref] still resolves to real bytes (remote URLs are trusted as-is). */
    fun isValidRef(ref: String?): Boolean

    /** The stored reference for [kind] of this game under its fixed name, or null if absent. */
    suspend fun find(gameId: Long, kind: ArtworkKind): String?

    /** Deletes every stored artwork file. DB references are the caller's responsibility. */
    suspend fun deleteAll()
}
