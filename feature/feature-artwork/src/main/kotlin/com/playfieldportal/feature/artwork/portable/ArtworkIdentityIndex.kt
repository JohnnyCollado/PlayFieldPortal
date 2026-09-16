package com.playfieldportal.feature.artwork.portable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * `pfp-artwork-identity.json` at the root of the artwork folder: which game each library file
 * belongs to, stated as durable identity rather than as a name (C16 task D.1).
 *
 * **Why this exists.** Artwork identity has been spelled as a filename at every tier —
 * `ArtworkKeyFactory` mints a ROM game's "stable, portable identity" as
 * `rom/{platform}/{slug(rom filename stem)}`, and all four tiers of `RelinkOwnerLookup` match on
 * names. A renamed ROM therefore orphans its artwork, and a `.pfpgame` export that carries real
 * scraper ids still reconnects by name.
 *
 * **This is not a new idea.** The v1 layout's per-entry `metadata.json` ([ArtworkEntryMetadata])
 * already carried `rom_crc32` and the scraper ids as "identity evidence the reconnect matcher uses
 * when the primary key misses (renamed ROM)". The v1→v2 migration moved the assets into the flat
 * media-dir layout and deleted that file, and nothing has written identity since. The serialized
 * names here are deliberately the same ones, so the evidence means the same thing it always did.
 *
 * **Why one file at the root** (user decision, 2026-09-16): one read per relink and one rewrite per
 * operation, invisible to ES-DE, which shares the folder. The accepted cost is that a file the user
 * hand-moves between platform folders leaves a stale row — relink then falls back to the name tiers
 * for it, exactly as it does today.
 *
 * Parsed defensively like the manifest: unknown keys ignored, malformed JSON → null. A corrupted
 * index costs the library its durable identity for one scan, never its artwork.
 */
@Serializable
data class ArtworkIdentityIndex(
    @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
    @SerialName("updated_at") val updatedAt: Long = 0,
    val entries: List<Entry> = emptyList(),
) {
    /**
     * One library file, and the identity of the game that owns it.
     *
     * Every id is nullable because none is universal: `romCrc32` is only written by a ScreenScraper
     * scrape, and the scraper ids only exist once a game has been matched. An entry with none of
     * them yields no tokens and falls through to name matching.
     */
    @Serializable
    data class Entry(
        @SerialName("platform_id") val platformId: String,
        // ArtworkKind name (ICON, HERO, …).
        val kind: String,
        // The file's name without extension — `artwork_records.portable_name`.
        @SerialName("portable_name") val portableName: String,
        @SerialName("rom_crc32") val romCrc32: String? = null,
        @SerialName("ss_id") val ssId: Long? = null,
        @SerialName("tgdb_id") val tgdbId: Long? = null,
        @SerialName("igdb_id") val igdbId: Long? = null,
        @SerialName("sgdb_id") val sgdbId: Long? = null,
        // Name-derived and therefore the weakest evidence, kept only as a last resort above the
        // fuzzy matcher — it is exactly the key that fails when a ROM is renamed.
        @SerialName("artwork_key") val artworkKey: String? = null,
    ) {
        /**
         * This file's owner as match tokens, strongest evidence first: content hash, then scraper
         * ids, then the name-derived key. A consumer takes the first token that names a game it
         * knows (task D.3), so the order here is the precedence.
         */
        fun tokens(): List<String> = tokensOf(romCrc32, ssId, tgdbId, igdbId, sgdbId, artworkKey)
    }

    // Built once per index instance: relink asks for every file in the library, so a linear scan
    // per question would make the walk quadratic on a large folder.
    private val byFile: Map<Triple<String, String, String>, Entry> by lazy {
        entries.associateBy { keyOf(it.platformId, it.kind, it.portableName) }
    }

    /** The row for one library file, or null when it has none (a foreign or pre-D.2 file). */
    fun find(platformId: String, kind: String, portableName: String): Entry? =
        byFile[keyOf(platformId, kind, portableName)]

    /** This index with [entry] replacing any row for the same file. */
    fun upsert(entry: Entry): ArtworkIdentityIndex {
        val key = keyOf(entry.platformId, entry.kind, entry.portableName)
        return copy(entries = entries.filterNot { keyOf(it.platformId, it.kind, it.portableName) == key } + entry)
    }

    /**
     * This index with every row in [rows] replacing any row for the same file — one pass.
     *
     * [upsert] rebuilds the whole list per call, which is fine for one save and quadratic for a
     * relink that backfills a whole library (task D.4). Later rows win over earlier ones, and
     * existing rows keep their position so the file stays diff-friendly between scans.
     */
    fun upsertAll(rows: List<Entry>): ArtworkIdentityIndex {
        if (rows.isEmpty()) return this
        val merged = LinkedHashMap<Triple<String, String, String>, Entry>(entries.size + rows.size)
        entries.forEach { merged[keyOf(it.platformId, it.kind, it.portableName)] = it }
        rows.forEach { merged[keyOf(it.platformId, it.kind, it.portableName)] = it }
        return copy(entries = merged.values.toList())
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "pfp-artwork-identity.json"

        // Unlike the manifest — a fixed-size config read at 64 KB — this scales with the library:
        // roughly 120 bytes per file, so 4 MB carries a library far larger than any real one while
        // still refusing to buffer something that is not ours.
        const val MAX_BYTES = 4 * 1024 * 1024

        /**
         * The identity tokens for one game's ids, strongest evidence first.
         *
         * **Both sides of a match must build tokens here.** Relink compares the tokens of a file
         * (from its [Entry]) against the tokens of every game in the database; two spellings of the
         * same id would simply never meet, and the failure would look like "no durable identity"
         * rather than like a bug.
         */
        fun tokensOf(
            romCrc32: String?,
            ssId: Long?,
            tgdbId: Long?,
            igdbId: Long?,
            sgdbId: Long?,
            artworkKey: String?,
        ): List<String> = buildList {
            romCrc32?.takeIf { it.isNotBlank() }?.let { add("crc:${it.uppercase(Locale.US)}") }
            ssId?.let { add("ss:$it") }
            tgdbId?.let { add("tgdb:$it") }
            igdbId?.let { add("igdb:$it") }
            sgdbId?.let { add("sgdb:$it") }
            artworkKey?.takeIf { it.isNotBlank() }?.let { add("key:$it") }
        }

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        // Names and platform ids are matched case-insensitively everywhere else in relink; the
        // index has to agree, or a file saved as "Final Fantasy VI" would not be found from the
        // lowercased name the lookup carries.
        private fun keyOf(platformId: String, kind: String, portableName: String) =
            Triple(
                platformId.lowercase(Locale.US),
                kind.uppercase(Locale.US),
                portableName.lowercase(Locale.US),
            )

        fun parse(text: String): ArtworkIdentityIndex? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun encode(index: ArtworkIdentityIndex): String =
            json.encodeToString(serializer(), index)
    }
}
