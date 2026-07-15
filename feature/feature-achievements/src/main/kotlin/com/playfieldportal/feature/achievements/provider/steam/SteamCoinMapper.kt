package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.SyncedCoin

/**
 * Maps a game's Steam schema + global rarity + the player's earned state to [SyncedCoin]s.
 * Standalone so the tier rules are unit-testable against plain DTOs.
 *
 * Steam tier rules (PFP's classification — Steam has no medal tiers of its own):
 *  - A confirmed **completion achievement** ("earn all other achievements") is the game's
 *    Platinum, regardless of its own rarity. Detection order: the verified
 *    [SteamPlatinumOverrides] table first, then strict phrase matching on the DESCRIPTION only —
 *    names like "Platinum" / "Completionist" / "100%" alone never qualify, since they can describe
 *    unrelated challenges. The Platinum coin is excluded from the Bronze/Silver/Gold tallies and
 *    the progress denominator, and earning it lights the mastery crown (see
 *    AchievementRepository.summaryOf). Games with no completion achievement still mint a synthetic
 *    Platinum on 100% completion.
 *  - Everything else tiers purely by global unlock rarity ([ShibaTier.forRarity]): Gold under 10%,
 *    Silver 10-24.99%, Bronze at 25% and above. Hidden achievements get no special tier — hidden
 *    does not mean rare.
 *  - A coin Steam reports no percentage for is Bronze with its rarity stored as
 *    [SyncedCoin.RARITY_UNAVAILABLE] so the UI can say so; an exact 0% with valid data is real
 *    ultra-rarity (Gold).
 */
internal object SteamCoinMapper {

    // Strict completion wording, checked against the description only: a completion verb followed
    // by an explicit every/all-achievements construction ("Unlock every other achievement",
    // "Earn all achievements", "Complete all the achievements", ...). Anything looser risks
    // false positives on unrelated challenges.
    private val COMPLETION_PATTERNS = listOf(
        Regex(
            """\b(?:unlock|earn|obtain|get|collect|complete)(?:e?d)? (?:every|all) (?:the )?(?:other )?achievements?\b""",
            RegexOption.IGNORE_CASE,
        ),
        Regex("""\bearn(?:ed)? all base[- ]game achievements?\b""", RegexOption.IGNORE_CASE),
        Regex("""\ball (?:other )?achievements (?:unlocked|earned|obtained|completed)\b""", RegexOption.IGNORE_CASE),
    )

    fun map(
        appId: String,
        schema: List<SteamSchemaAchievement>,
        percentByName: Map<String, Double>,
        earnedByName: Map<String, SteamPlayerAchievement>,
    ): List<SyncedCoin> {
        val overrideApiName = SteamPlatinumOverrides.completionApiName(appId)
        return schema.map { a ->
            val percent: Double? = percentByName[a.name]
            val earned = earnedByName[a.name]
            val isEarned = earned?.achieved == 1
            val isPlatinum = a.name == overrideApiName || isCompletionCandidate(a.description)
            SyncedCoin(
                providerAchievementId = a.name,
                title = a.displayName ?: a.name,
                description = a.description.orEmpty(),
                tier = if (isPlatinum) ShibaTier.PLATINUM else ShibaTier.forRarity(percent),
                globalRarity = percent ?: SyncedCoin.RARITY_UNAVAILABLE,
                iconUrl = if (isEarned) a.icon else (a.icongray ?: a.icon),
                isHidden = a.hidden == 1,
                isEarned = isEarned,
                // Steam has no hardcore/softcore split: any unlock counts toward the crown.
                earnedHardcore = isEarned,
                // Steam unlocktime is epoch seconds; store millis. 0 = not earned.
                earnedAt = earned?.unlocktime?.takeIf { it > 0 }?.times(1_000),
            )
        }
    }

    /** True when the description clearly states an all-other-achievements requirement. */
    fun isCompletionCandidate(description: String?): Boolean {
        if (description.isNullOrBlank()) return false
        return COMPLETION_PATTERNS.any { it.containsMatchIn(description) }
    }
}
