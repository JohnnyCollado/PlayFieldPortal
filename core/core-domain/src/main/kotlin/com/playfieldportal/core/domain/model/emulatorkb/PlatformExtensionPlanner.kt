package com.playfieldportal.core.domain.model.emulatorkb

/**
 * The outcome of planning one platform's extensions (AD-9).
 *
 * [platform] and each entry of [cards] are the new list for that row, or null when it stays as it is.
 * [cards] is index-aligned with the `cards` passed to [PlatformExtensionPlanner.plan].
 */
data class PlatformExtensionPlan(
    val platform: List<String>?,
    val cards: List<List<String>?>,
    /** What to record as the platform's last KB-applied set. Grows only; never shrinks. */
    val lastApplied: List<String>,
    /** Tokens appended to at least one written row, in KB order. */
    val added: List<String>,
)

/**
 * Pure planner for additive, user-edit-guarded extension updates. The target for a row is
 * `current ∪ kbList`; a row is written only while it is untouched, which follows `MIGRATION_48_49`'s
 * rule that a knowledge-base update must never overwrite an override.
 */
object PlatformExtensionPlanner {

    /**
     * A row is untouched when its current set equals [lastApplied], [seedDefault], or its own target
     * (idempotent). Tokens compare case-insensitively and ignore order; output is lowercase, in the
     * row's order with new tokens appended.
     */
    fun plan(
        seedDefault: List<String>,
        lastApplied: List<String>?,
        kbList: List<String>,
        platformCurrent: List<String>,
        cards: List<List<String>>,
    ): PlatformExtensionPlan {
        val seed = normalize(seedDefault)
        val applied = lastApplied?.let(::normalize)
        val kb = normalize(kbList)

        val added = LinkedHashSet<String>()
        fun rewrite(current: List<String>): List<String>? {
            val row = normalize(current)
            val target = (row + kb).distinct()
            if (target.size == row.size) return null
            val untouched = row.toSet() == seed.toSet() ||
                (applied != null && row.toSet() == applied.toSet())
            if (!untouched) return null
            added += target - row.toSet()
            return target
        }

        return PlatformExtensionPlan(
            platform = rewrite(platformCurrent),
            cards = cards.map(::rewrite),
            lastApplied = ((applied ?: seed) + kb).distinct(),
            added = added.toList(),
        )
    }

    private fun normalize(list: List<String>): List<String> =
        list.map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }.distinct()
}
