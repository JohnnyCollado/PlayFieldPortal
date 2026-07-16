package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaAchievement
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Serializes a Steam achievement schema into the Goldberg/GSE `steam_settings/achievements.json`
 * an emulator reads to know a game's achievement list. This is the same file GSE-Generator writes
 * on a PC; PFP produces it locally so an emu-run game can start recording unlocks — the progress
 * file PFP later reads — without a PC round-trip.
 *
 * Icons are intentionally omitted: the JSON alone is all the emu needs to track unlocks, and the
 * `icon`/`icongray` paths just point at where artwork would live for the emu's own overlay.
 */
object GoldbergAchievementsJson {

    @Serializable
    private data class Entry(
        val description: String,
        val displayName: String,
        val hidden: Int,
        val icon: String,
        val icongray: String,
        val name: String,
    )

    private val json = Json { prettyPrint = true }

    /** Goldberg-format JSON for [schema]; an empty schema yields an empty array. */
    fun serialize(schema: List<SteamSchemaAchievement>): String =
        json.encodeToString(
            schema.map { a ->
                Entry(
                    description = a.description.orEmpty(),
                    displayName = a.displayName.orEmpty(),
                    hidden = a.hidden,
                    icon = imagePath(a.icon),
                    icongray = imagePath(a.icongray),
                    name = a.name,
                )
            },
        )

    // The schema serves full CDN urls; Goldberg wants a folder-relative images/<file> path. With no
    // icon downloaded the emu simply shows no artwork — unlock tracking is unaffected.
    private fun imagePath(url: String?): String {
        val file = url?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: return ""
        return "images/$file"
    }
}
