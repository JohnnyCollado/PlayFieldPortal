package com.playfieldportal.feature.settings.pc

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One `.pfpgame` file: a manually added PC game, or a pinned shortcut, as written by Export Manual
 * Games and read back by Scan Import Folder (C18).
 *
 * GameNative and Winlator export a game to a file PFP can import; a game added by id, a legacy
 * `INSTALL_SHORTCUT` capture and a pin had nothing of the kind, so a fresh install lost them or
 * left their artwork unconnected. This carries what a fresh install needs to bring the game back
 * and reconnect its artwork by the exact names it was saved under. It carries no artwork bytes.
 *
 * The file sits on shared storage, where any app can write it, so nothing in it is trusted as it
 * stands: [PcGameExportCodec.decode] validates its shape, and the importer validates the launch
 * intent before it is stored.
 */
@Serializable
data class PcGameExport(
    val format: String = PcGameExportCodec.FORMAT,
    val version: Int = PcGameExportCodec.VERSION,
    val title: String = "",
    val scrapedTitle: String? = null,
    val userTitleOverride: String? = null,
    val launcherPackage: String = "",
    val launchIntentUri: String? = null,
    val shortcutId: String? = null,
    val storefront: String? = null,
    val storefrontGameId: String? = null,
    val ssId: Long? = null,
    val tgdbId: Long? = null,
    val igdbId: Long? = null,
    val steamGridDbId: Long? = null,
    val artwork: List<PcGameExportArtwork> = emptyList(),
) {
    /**
     * A pinned shortcut. Pin reconcile brings the game itself back, so a pin entry is only matched,
     * for its artwork names and fill-only identity, and never creates or launches anything.
     */
    val isPin: Boolean get() = shortcutId != null
}

/** One artwork file the game had, by the name it was saved under (`artwork_records.portable_name`). */
@Serializable
data class PcGameExportArtwork(
    val kind: String = "",
    val sortOrder: Int = 0,
    val portableName: String = "",
)

/** What reading a `.pfpgame` body produced. */
sealed interface PcGameExportDecode {
    data class Valid(val export: PcGameExport) : PcGameExportDecode

    /** Not usable. [reason] completes "This export file …" for a log or a report. */
    data class Rejected(val reason: String) : PcGameExportDecode
}

object PcGameExportCodec {
    const val FORMAT = "pfp-pc-game"
    const val VERSION = 1
    const val EXTENSION = "pfpgame"

    // One game's entry is a few kilobytes. Anything this large, or listing this many files, was not
    // written by Export Manual Games.
    const val MAX_CHARS = 256 * 1024
    const val MAX_ARTWORK_ITEMS = 200

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Users open these files, as they do the launchers' own exports.
        prettyPrint = true
    }

    /** The file body for [export], always stamped with this version's [FORMAT] and [VERSION]. */
    fun encode(export: PcGameExport): String =
        json.encodeToString(PcGameExport.serializer(), export.copy(format = FORMAT, version = VERSION))

    /**
     * Reads a `.pfpgame` body. Never throws: anything unusable is [PcGameExportDecode.Rejected].
     *
     * Another format is rejected, and a newer version is refused whole rather than half read. A valid
     * entry comes back normalized: trimmed, blank optional values and non-positive provider ids as
     * null, unusable artwork items dropped, and a pin entry's launch intent removed.
     */
    fun decode(text: String): PcGameExportDecode {
        if (text.length > MAX_CHARS) return PcGameExportDecode.Rejected("is too large to be a game export")
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: IllegalArgumentException) {
            // SerializationException, for a body that is not JSON at all.
            null
        } ?: return PcGameExportDecode.Rejected("is not a PlayFieldPortal game export")
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != FORMAT) {
            return PcGameExportDecode.Rejected("is not a PlayFieldPortal game export")
        }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: VERSION
        if (version > VERSION) {
            return PcGameExportDecode.Rejected(
                "was made by a newer version of PlayFieldPortal (export version $version); update to import it",
            )
        }
        if (version < 1) return PcGameExportDecode.Rejected("has an invalid version ($version)")

        val raw = try {
            json.decodeFromJsonElement(PcGameExport.serializer(), root)
        } catch (e: IllegalArgumentException) {
            return PcGameExportDecode.Rejected("could not be read: ${e.message}")
        }
        return validate(raw)
    }

    private fun validate(raw: PcGameExport): PcGameExportDecode {
        val title = raw.title.trim()
        if (title.isEmpty()) return PcGameExportDecode.Rejected("has no title")
        val launcherPackage = raw.launcherPackage.trim()
        if (launcherPackage.isEmpty()) return PcGameExportDecode.Rejected("names no launcher")
        if (raw.artwork.size > MAX_ARTWORK_ITEMS) return PcGameExportDecode.Rejected("lists too many artwork files")

        val shortcutId = raw.shortcutId.orNullIfBlank()
        val launchIntentUri = raw.launchIntentUri.orNullIfBlank()
        if (shortcutId == null && launchIntentUri == null) {
            return PcGameExportDecode.Rejected("has neither a launch intent nor a pinned shortcut")
        }

        return PcGameExportDecode.Valid(
            raw.copy(
                title = title,
                scrapedTitle = raw.scrapedTitle.orNullIfBlank(),
                userTitleOverride = raw.userTitleOverride.orNullIfBlank(),
                launcherPackage = launcherPackage,
                shortcutId = shortcutId,
                // A pin entry only matches: an intent inside one is never kept, so it can never be
                // stored or launched, whatever the file says.
                launchIntentUri = if (shortcutId != null) null else launchIntentUri,
                storefront = raw.storefront.orNullIfBlank(),
                storefrontGameId = raw.storefrontGameId.orNullIfBlank(),
                ssId = raw.ssId.orNullIfNotPositive(),
                tgdbId = raw.tgdbId.orNullIfNotPositive(),
                igdbId = raw.igdbId.orNullIfNotPositive(),
                steamGridDbId = raw.steamGridDbId.orNullIfNotPositive(),
                artwork = raw.artwork.mapNotNull { item ->
                    val kind = item.kind.trim()
                    val name = item.portableName.trim()
                    if (kind.isEmpty() || name.isEmpty() || item.sortOrder < 0) null
                    else item.copy(kind = kind, portableName = name)
                },
            ),
        )
    }

    private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun Long?.orNullIfNotPositive(): Long? = this?.takeIf { it > 0 }
}
