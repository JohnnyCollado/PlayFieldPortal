package com.playfieldportal.feature.artwork.portable

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `pfp-artwork-library.json` at the root of the user-picked artwork folder. One document read
 * lets a fresh install recognize "this is a PFP artwork library"; the UUID distinguishes "same
 * library re-linked" from "a different library"; the format/normalization versions gate forward
 * migration (slug rules are frozen per normalization version — see ArtworkNaming).
 *
 * Parsed defensively: unknown keys ignored, malformed JSON → null (a corrupted manifest never
 * crashes folder linking; the folder is then treated as not-yet-a-library).
 */
@Serializable
data class ArtworkLibraryManifest(
    @SerialName("format_version") val formatVersion: Int = FORMAT_VERSION,
    @SerialName("library_uuid") val libraryUuid: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("app_version") val appVersion: String = "",
    @SerialName("normalization_version") val normalizationVersion: Int = ArtworkNaming.NORMALIZATION_VERSION,
    @SerialName("entry_count_hint") val entryCountHint: Int = 0,
) {
    companion object {
        const val FORMAT_VERSION = 1
        const val FILE_NAME = "pfp-artwork-library.json"

        /** Directory names reserved at the library root. */
        const val DIR_GAMES = "games"
        const val DIR_IMPORT = "import"

        // Size cap when reading a manifest: a well-formed manifest is <1 KB; anything bigger is
        // not ours and must not be buffered into memory.
        const val MAX_BYTES = 64 * 1024

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun parse(text: String): ArtworkLibraryManifest? =
            runCatching { json.decodeFromString(serializer(), text) }.getOrNull()

        fun encode(manifest: ArtworkLibraryManifest): String =
            json.encodeToString(serializer(), manifest)
    }
}
