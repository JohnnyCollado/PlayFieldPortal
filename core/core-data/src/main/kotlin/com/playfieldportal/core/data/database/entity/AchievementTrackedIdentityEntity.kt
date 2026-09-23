package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable

/**
 * The confirmed-local-match ledger: one row per provider identity that was matched to a game
 * present on this device (docs/plans/PFP_Local_Achievements_Selective_Sync_Implementation_Plan.md
 * section 3). Keyed by (provider, provider_game_id) rather than a volatile game id, so removing and
 * reinstalling a game — or holding two local copies of it — maps to one history entry.
 *
 * Only identities in this table count anywhere account-wide: the tracked list, the wallet and the
 * rarest/recent feeds all join through it. A set with no ledger row (an old account-wide import)
 * stays stored but invisible. [isPresent] separates games refreshed automatically from removed
 * ones that keep their cached coins as history.
 *
 * The remaining columns are this identity's sync bookmarks: when it was last checked, when its
 * full detail last landed, provider metadata age, failure backoff, and an opaque provider-specific
 * comparison snapshot (RA progress summary, Steam playtime, local earned-state fingerprint).
 */
@Serializable
@Entity(
    tableName = "achievement_tracked_identities",
    primaryKeys = ["provider", "provider_game_id"],
)
data class AchievementTrackedIdentityEntity(
    // AchievementProvider enum name.
    val provider: String,

    @ColumnInfo(name = "provider_game_id")
    val providerGameId: String,

    // Display title from the matched game (or the local folder), for rows with no library game.
    val title: String,

    @ColumnInfo(name = "first_matched_at")
    val firstMatchedAt: Long,

    @ColumnInfo(name = "last_matched_at")
    val lastMatchedAt: Long,

    // True while a present game or local folder maps here; false = removed, history only.
    @ColumnInfo(name = "is_present")
    val isPresent: Boolean,

    @ColumnInfo(name = "last_seen_present_at")
    val lastSeenPresentAt: Long? = null,

    // Last summary or detail check, successful or not-found.
    @ColumnInfo(name = "last_checked_at")
    val lastCheckedAt: Long? = null,

    // Last successful full-detail fetch; drives stale-on-open.
    @ColumnInfo(name = "last_detail_at")
    val lastDetailAt: Long? = null,

    @ColumnInfo(name = "retry_at")
    val retryAt: Long? = null,

    @ColumnInfo(name = "failure_count", defaultValue = "0")
    val failureCount: Int = 0,

    @ColumnInfo(name = "summary_snapshot")
    val summarySnapshot: String? = null,
)
