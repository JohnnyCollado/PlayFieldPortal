package com.playfieldportal.feature.achievements.provider.localsteam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** One achievement's earned state as the emulator recorded it. */
data class EmuEarnedAchievement(
    val apiName: String,
    val earned: Boolean,
    val earnedAtEpochSeconds: Long?,
)

/**
 * Parses a GSE / Goldberg progress `achievements.json` (the file the emu WRITES under its save
 * path — not the schema-input file of the same name in `steam_settings/`). Verified live format
 * (docs/local-steam-achievements-plan.md Phase 0): an object keyed by achievement api name, each
 * value carrying `earned` and `earned_time` (epoch seconds), with optional `progress` /
 * `max_progress` counters this parser deliberately ignores.
 *
 * The file is untrusted input from shared storage: parsing is tolerant (old Goldberg builds may
 * write 0/1 instead of booleans), bounded in size, and never throws — anything unreadable is an
 * empty result, which callers treat as "no local progress".
 */
object EmuAchievementFile {

    /** A real progress file is a few KB; anything beyond this is not the file we expect. */
    const val MAX_BYTES = 512 * 1024

    private val json = Json { isLenient = true }

    fun parse(text: String): List<EmuEarnedAchievement> {
        if (text.length > MAX_BYTES) return emptyList()
        return runCatching {
            val root = json.parseToJsonElement(text) as? JsonObject ?: return emptyList()
            root.mapNotNull { (apiName, value) ->
                val fields = value as? JsonObject ?: return@mapNotNull null
                EmuEarnedAchievement(
                    apiName = apiName,
                    earned = fields.flag("earned"),
                    earnedAtEpochSeconds = (fields["earned_time"] as? JsonPrimitive)
                        ?.longOrNull?.takeIf { it > 0 },
                )
            }
        }.getOrElse { emptyList() }
    }

    // gbe_fork writes real booleans; older Goldberg lineages have used 0/1. Absent means false.
    private fun JsonObject.flag(key: String): Boolean {
        val primitive = this[key] as? JsonPrimitive ?: return false
        return primitive.booleanOrNull ?: (primitive.longOrNull == 1L)
    }
}
