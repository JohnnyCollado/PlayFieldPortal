package com.playfieldportal.feature.artwork.importer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Data shapes shared by the import pipeline. The plan is serializable because it is handed from
 * the preview UI to the WorkManager executor as a JSON file (WorkManager's Data payload is
 * capped at ~10 KB; a 50k-file plan is megabytes). Candidates carry SAF *document ids*, not
 * URIs — the worker rebuilds tree-scoped URIs, so a plan file can never smuggle a reference
 * outside the granted tree.
 */

/** One artwork file found in the import drop zone, ~100 bytes — safe to hold 50k in memory. */
@Serializable
data class ImportCandidate(
    @SerialName("platform_id") val platformId: String,
    // ArtworkKind name (ICON, HERO, …) this file maps to.
    val kind: String,
    @SerialName("document_id") val documentId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("size_bytes") val sizeBytes: Long,
)

/** A recognized launcher folder under `import/` ("ES-DE — 14 systems"). */
data class DetectedImportSource(
    val sourceId: String,           // importer implementation id, e.g. "esde"
    val label: String,              // folder name shown to the user, e.g. "ES-DE"
    val folderDocId: String,        // the import/<Launcher> folder
    val systemsRootDocId: String,   // where the per-system folders live (may be a child, e.g. downloaded_media)
    val systems: List<SystemFolder>,
    // platformId → gamelist.xml document id, when the drop also contains ES-DE gamelists.
    val gamelistDocIds: Map<String, String> = emptyMap(),
) {
    data class SystemFolder(val platformId: String, val docId: String, val folderName: String)
}

/** How a candidate was tied to a game, strongest first (ordinal = priority). */
enum class MatchConfidence { EXACT_FILENAME, DISPLAY_TITLE, SIMPLIFIED_TITLE, INDEXED_FILENAME }

@Serializable
data class PlannedItem(
    val kind: String,
    @SerialName("document_id") val documentId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val confidence: MatchConfidence,
    // True when the game's DB column for this kind held a dead reference — the executor
    // overwrites instead of fill-if-missing.
    @SerialName("replaces_stale") val replacesStale: Boolean = false,
)

@Serializable
data class PlannedGame(
    @SerialName("game_id") val gameId: Long,
    @SerialName("platform_id") val platformId: String,
    @SerialName("artwork_key") val artworkKey: String,
    val slug: String,
    val title: String,
    @SerialName("rom_file_name") val romFileName: String? = null,
    val items: List<PlannedItem>,
)

@Serializable
data class AmbiguousCandidate(
    val candidate: ImportCandidate,
    @SerialName("game_ids") val gameIds: List<Long>,
    @SerialName("game_titles") val gameTitles: List<String>,
)

@Serializable
data class ImportPlan(
    @SerialName("source_id") val sourceId: String,
    @SerialName("source_label") val sourceLabel: String,
    @SerialName("tree_uri") val treeUri: String,
    val games: List<PlannedGame>,
    val ambiguous: List<AmbiguousCandidate>,
    @SerialName("unmatched_count") val unmatchedCount: Int,
    @SerialName("skipped_existing_count") val skippedExistingCount: Int,
    @SerialName("unknown_system_folders") val unknownSystemFolders: List<String> = emptyList(),
    // Recognized system folders whose platform has zero games in the PFP library — their files
    // can never match, and the preview should say so instead of a bare "unmatched" count.
    @SerialName("platforms_without_games") val platformsWithoutGames: List<String> = emptyList(),
    // Text metadata (description/developer/…) parsed from the source's gamelist.xml files,
    // matched to games. Applied fill-missing-only by the executor.
    @SerialName("metadata_updates") val metadataUpdates: List<MetadataUpdate> = emptyList(),
) {
    val itemCount: Int get() = games.sumOf { it.items.size }
    val totalBytes: Long get() = games.sumOf { g -> g.items.sumOf { it.sizeBytes } }
    fun countsByKind(): Map<String, Int> =
        games.flatMap { it.items }.groupingBy { it.kind }.eachCount()

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun parse(text: String): ImportPlan? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
        fun encode(plan: ImportPlan): String = json.encodeToString(serializer(), plan)
    }
}

/** One game's text metadata from gamelist.xml — only non-null fields are applied, and only
 *  where the game row is still empty (existing scraper/user values always win). */
@Serializable
data class MetadataUpdate(
    @SerialName("game_id") val gameId: Long,
    val name: String? = null,
    val description: String? = null,
    val developer: String? = null,
    val publisher: String? = null,
    @SerialName("release_year") val releaseYear: Int? = null,
    val genre: String? = null,
)

/** The persisted outcome of a run — what the Import Report screen renders. */
@Serializable
data class ImportSummary(
    @SerialName("source_label") val sourceLabel: String,
    val transfer: String,                        // COPY or MOVE
    val imported: Int = 0,
    val skipped: Int = 0,                        // already present (resume / missing-only)
    val failed: Int = 0,
    val ambiguous: Int = 0,                      // left for review
    val unmatched: Int = 0,
    @SerialName("bytes_written") val bytesWritten: Long = 0,
    @SerialName("metadata_applied") val metadataApplied: Int = 0,
    @SerialName("counts_by_kind") val countsByKind: Map<String, Int> = emptyMap(),
    @SerialName("unknown_system_folders") val unknownSystemFolders: List<String> = emptyList(),
    // First N failure messages, capped — enough to diagnose without unbounded growth.
    val errors: List<String> = emptyList(),
    val cancelled: Boolean = false,
) {
    companion object {
        const val MAX_ERRORS = 50
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun parse(text: String): ImportSummary? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
        fun encode(summary: ImportSummary): String = json.encodeToString(serializer(), summary)
    }
}
