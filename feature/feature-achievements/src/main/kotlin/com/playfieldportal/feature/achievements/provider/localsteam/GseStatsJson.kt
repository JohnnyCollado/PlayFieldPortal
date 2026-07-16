package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaStat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Serializes a Steam schema's stat list into gbe_fork's `steam_settings/stats.json` (the modern
 * replacement for old Goldberg's `stats.txt`). Games whose achievements unlock through stat
 * thresholds need these definitions present, or the emu never progresses them.
 *
 * Matches gbe_fork's steam_settings.EXAMPLE: an array of objects whose values are all strings.
 * The Web API schema carries no int/float/avgrate type, so the type is inferred from the default
 * value — a fractional default is a float, everything else an int. A float-typed stat whose
 * default happens to be whole is mislabeled int; the emu still records it, so this stays a
 * best-effort improvement over writing no stats at all.
 */
object GseStatsJson {

    @Serializable
    private data class Entry(
        val default: String,
        val global: String,
        val name: String,
        val type: String,
    )

    private val json = Json { prettyPrint = true }

    /** gbe_fork-format JSON for [stats]; an empty list yields an empty array. */
    fun serialize(stats: List<SteamSchemaStat>): String =
        json.encodeToString(
            stats.map { s ->
                Entry(
                    default = format(s.defaultValue),
                    // No global data comes with the schema; 0 mirrors the example's baseline.
                    global = "0",
                    name = s.name,
                    type = if (isWhole(s.defaultValue)) "int" else "float",
                )
            },
        )

    private fun isWhole(v: Double): Boolean = v.isFinite() && v == kotlin.math.floor(v)

    private fun format(v: Double): String = if (isWhole(v)) v.toLong().toString() else v.toString()
}
