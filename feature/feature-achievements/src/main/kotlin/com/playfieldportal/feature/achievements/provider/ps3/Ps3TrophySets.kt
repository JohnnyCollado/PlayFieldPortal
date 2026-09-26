package com.playfieldportal.feature.achievements.provider.ps3

import com.playfieldportal.feature.achievements.provider.vita.TropSfm
import com.playfieldportal.feature.achievements.provider.vita.TropUsrParser

/** One PS3 trophy, already namespaced to its set so subsets can be merged (see [Ps3TrophySets]). */
data class Ps3Trophy(
    /** `"<npCommId>:<trophyId>"` — the stored `providerAchievementId`. */
    val coinId: String,
    val npCommId: String,
    val id: Int,
    val name: String,
    val detail: String,
    val grade: TropUsrParser.Grade,
    val hidden: Boolean,
    val unlocked: Boolean,
    val unlockedAtEpochMillis: Long?,
    val iconUri: String?,
)

/** One trophy set (base or DLC subset) as it exists on disk. */
data class Ps3TrophySet(
    val npCommId: String,
    val titleName: String?,
    val trophies: List<Ps3Trophy>,
)

/**
 * The pure half of PS3 trophy reading: joining a set's `TROPCONF.SFM` definitions with its
 * `TROPUSR.DAT` unlock state, and merging a game's declared sets into **one** coin list.
 *
 * Kept free of SAF and `Context` so the rules that actually matter — namespaced coin ids, set
 * order, and exactly one platinum — are testable without a device. [Ps3TrophyDiscovery] supplies
 * the bytes.
 */
object Ps3TrophySets {

    /**
     * Joins [sfmBytes] (definitions, always required) with [usrBytes] (unlock state, optional — a
     * set the game has registered but never earned in has no `TROPUSR.DAT` yet and tracks at 0%).
     *
     * `TROPCONF.SFM` is the sole authority for the hidden flag: unlike Vita, the PS3 `TROPUSR.DAT`
     * carries no hidden mask. Returns null when the SFM declares no trophies at all.
     */
    fun buildSet(
        npCommId: String,
        sfmBytes: ByteArray,
        usrBytes: ByteArray?,
        iconUriFor: (fileName: String) -> String?,
    ): Ps3TrophySet? {
        val defs = TropSfm.parse(sfmBytes)
        if (defs.trophies.isEmpty()) return null
        val progressById = usrBytes?.let { TropUsrPs3Parser.parse(it) }?.trophies?.associateBy { it.id }.orEmpty()

        val trophies = defs.trophies.map { def ->
            val progress = progressById[def.id]
            Ps3Trophy(
                coinId = coinId(npCommId, def.id),
                npCommId = npCommId,
                id = def.id,
                name = def.name,
                detail = def.detail,
                // The SFM's ttype is authoritative; the DAT's grade mirror only fills a gap.
                grade = def.grade.takeIf { it != TropUsrParser.Grade.UNKNOWN }
                    ?: progress?.grade ?: TropUsrParser.Grade.UNKNOWN,
                hidden = def.hidden,
                unlocked = progress?.unlocked ?: false,
                unlockedAtEpochMillis = progress?.unlockedAtEpochMillis,
                iconUri = iconUriFor("TROP%03d.PNG".format(def.id)),
            )
        }
        return Ps3TrophySet(npCommId = defs.npCommId?.trim()?.ifEmpty { null } ?: npCommId, titleName = defs.titleName, trophies = trophies)
    }

    /**
     * Merges a game's sets into one coin list, in the order `TROPDIR` declared them (base set
     * first). Coin ids are already namespaced per set, so two subsets both numbering their trophies
     * from 0 never collide.
     *
     * Only the base set carries a platinum. A subset grade table that claims one anyway would give
     * the game a second crown, so any platinum after the first is demoted to gold rather than
     * trusted — one game, one platinum.
     */
    fun merge(sets: List<Ps3TrophySet>): List<Ps3Trophy> {
        var platinumSeen = false
        return sets.flatMap { it.trophies }.map { trophy ->
            if (trophy.grade != TropUsrParser.Grade.PLATINUM) return@map trophy
            if (platinumSeen) trophy.copy(grade = TropUsrParser.Grade.GOLD)
            else trophy.also { platinumSeen = true }
        }
    }

    /** The stored coin id for a trophy — namespaced uniformly, base set included. */
    fun coinId(npCommId: String, trophyId: Int): String = "$npCommId:$trophyId"
}
