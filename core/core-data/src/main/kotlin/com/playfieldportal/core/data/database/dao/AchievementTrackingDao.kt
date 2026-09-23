package com.playfieldportal.core.data.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.AchievementMetadataCacheEntity
import com.playfieldportal.core.data.database.entity.AchievementProviderSyncStateEntity
import com.playfieldportal.core.data.database.entity.AchievementTrackedIdentityEntity
import kotlinx.coroutines.flow.Flow

/** A provider link with its game's presence — the input to presence reconciliation. */
data class LinkPresenceRow(
    @ColumnInfo(name = "game_id") val gameId: Long,
    val provider: String,
    @ColumnInfo(name = "provider_game_id") val providerGameId: String,
    val title: String,
    @ColumnInfo(name = "is_missing") val isMissing: Boolean,
)

/** A present ledger identity with the stored set's coin counts, for the update planners. */
data class TrackedEntryRow(
    val provider: String,
    @ColumnInfo(name = "provider_game_id") val providerGameId: String,
    val title: String,
    @ColumnInfo(name = "last_checked_at") val lastCheckedAt: Long?,
    @ColumnInfo(name = "last_detail_at") val lastDetailAt: Long?,
    @ColumnInfo(name = "retry_at") val retryAt: Long?,
    @ColumnInfo(name = "summary_snapshot") val summarySnapshot: String?,
    @ColumnInfo(name = "stored_earned") val storedEarned: Int?,
    @ColumnInfo(name = "stored_total") val storedTotal: Int?,
)

/**
 * The selective achievement sync's own tables: the confirmed-local-match ledger, per-provider
 * scheduling state and the provider metadata cache — plus the one transaction that clears every
 * achievement record PFP stores (docs/plans/PFP_Local_Achievements_Selective_Sync_Implementation_Plan.md).
 */
@Dao
interface AchievementTrackingDao {

    // ── Presence inputs ─────────────────────────────────────────────────────────

    @Query(
        "SELECT l.game_id AS game_id, l.provider AS provider, l.provider_game_id AS provider_game_id, " +
            "COALESCE(g.user_title_override, g.title) AS title, g.is_missing AS is_missing " +
            "FROM provider_game_links l JOIN games g ON g.id = l.game_id"
    )
    suspend fun linksWithPresence(): List<LinkPresenceRow>

    @Query(
        "SELECT l.game_id AS game_id, l.provider AS provider, l.provider_game_id AS provider_game_id, " +
            "COALESCE(g.user_title_override, g.title) AS title, g.is_missing AS is_missing " +
            "FROM provider_game_links l JOIN games g ON g.id = l.game_id WHERE l.game_id = :gameId " +
            "ORDER BY l.provider"
    )
    suspend fun linkPresenceForGame(gameId: Long): List<LinkPresenceRow>

    // ── Ledger ──────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM achievement_tracked_identities")
    suspend fun getAllIdentities(): List<AchievementTrackedIdentityEntity>

    @Query(
        "SELECT * FROM achievement_tracked_identities " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId LIMIT 1"
    )
    suspend fun getIdentity(provider: String, providerGameId: String): AchievementTrackedIdentityEntity?

    @Query(
        "SELECT * FROM achievement_tracked_identities " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId LIMIT 1"
    )
    fun observeIdentity(provider: String, providerGameId: String): Flow<AchievementTrackedIdentityEntity?>

    /** The present LOCAL_STEAM identity a library game links to, for the launch-return check. */
    @Query(
        "SELECT t.* FROM achievement_tracked_identities t " +
            "JOIN provider_game_links l ON l.provider = t.provider AND l.provider_game_id = t.provider_game_id " +
            "WHERE l.game_id = :gameId AND t.provider = 'LOCAL_STEAM' AND t.is_present = 1 LIMIT 1"
    )
    suspend fun presentLocalSteamIdentityForGame(gameId: Long): AchievementTrackedIdentityEntity?

    @Query(
        "SELECT t.provider AS provider, t.provider_game_id AS provider_game_id, t.title AS title, " +
            "t.last_checked_at AS last_checked_at, t.last_detail_at AS last_detail_at, " +
            "t.retry_at AS retry_at, t.summary_snapshot AS summary_snapshot, " +
            "(s.bronze_earned + s.silver_earned + s.gold_earned) AS stored_earned, " +
            "(s.bronze_total + s.silver_total + s.gold_total) AS stored_total " +
            "FROM achievement_tracked_identities t " +
            "LEFT JOIN account_achievement_sets s " +
            "ON s.provider = t.provider AND s.provider_game_id = t.provider_game_id " +
            "WHERE t.is_present = 1"
    )
    suspend fun presentEntries(): List<TrackedEntryRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIdentity(entity: AchievementTrackedIdentityEntity)

    /**
     * Records a confirmed local match: a new identity enters the ledger as present; an existing one
     * (a reinstall, a second copy) is marked present again and keeps its first-match time and
     * sync bookmarks.
     */
    @Transaction
    suspend fun confirm(provider: String, providerGameId: String, title: String, now: Long) {
        val existing = getIdentity(provider, providerGameId)
        upsertIdentity(
            existing?.copy(
                title = title.ifBlank { existing.title },
                lastMatchedAt = now,
                isPresent = true,
                lastSeenPresentAt = now,
            ) ?: AchievementTrackedIdentityEntity(
                provider = provider,
                providerGameId = providerGameId,
                title = title,
                firstMatchedAt = now,
                lastMatchedAt = now,
                isPresent = true,
                lastSeenPresentAt = now,
            ),
        )
    }

    @Query(
        "UPDATE achievement_tracked_identities SET is_present = 1, last_seen_present_at = :now " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId"
    )
    suspend fun markPresent(provider: String, providerGameId: String, now: Long)

    @Query(
        "UPDATE achievement_tracked_identities SET is_present = 0 " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId"
    )
    suspend fun markAbsent(provider: String, providerGameId: String)

    @Query(
        "DELETE FROM achievement_tracked_identities " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId"
    )
    suspend fun deleteIdentity(provider: String, providerGameId: String)

    /** A summary or not-found check: the snapshot is kept when the check carried none. */
    @Query(
        "UPDATE achievement_tracked_identities SET last_checked_at = :checkedAt, " +
            "summary_snapshot = COALESCE(:snapshot, summary_snapshot), retry_at = NULL, failure_count = 0 " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId"
    )
    suspend fun recordCheck(provider: String, providerGameId: String, checkedAt: Long, snapshot: String?)

    @Query(
        "UPDATE achievement_tracked_identities SET last_checked_at = :now, last_detail_at = :now, " +
            "summary_snapshot = COALESCE(:snapshot, summary_snapshot), retry_at = NULL, failure_count = 0 " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId"
    )
    suspend fun recordDetail(provider: String, providerGameId: String, now: Long, snapshot: String?)

    @Query(
        "UPDATE achievement_tracked_identities SET retry_at = :retryAt, failure_count = failure_count + 1 " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId"
    )
    suspend fun recordFailure(provider: String, providerGameId: String, retryAt: Long)

    // ── Provider state ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM achievement_provider_sync_state WHERE provider = :provider LIMIT 1")
    suspend fun getProviderState(provider: String): AchievementProviderSyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProviderState(entity: AchievementProviderSyncStateEntity)

    // ── Metadata cache ──────────────────────────────────────────────────────────

    @Query(
        "SELECT * FROM achievement_metadata_cache " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId LIMIT 1"
    )
    suspend fun getMetadata(provider: String, providerGameId: String): AchievementMetadataCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(entity: AchievementMetadataCacheEntity)

    // ── Backup ──────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM achievement_provider_sync_state")
    suspend fun getAllProviderStates(): List<AchievementProviderSyncStateEntity>

    // ── Clear all tracked achievements ──────────────────────────────────────────

    @Query("DELETE FROM account_achievements") suspend fun deleteAllCoins()
    @Query("DELETE FROM account_achievement_sets") suspend fun deleteAllSets()
    @Query("DELETE FROM achievement_tracked_identities") suspend fun deleteAllIdentities()
    @Query("DELETE FROM achievement_provider_sync_state") suspend fun deleteAllProviderState()
    @Query("DELETE FROM achievement_metadata_cache") suspend fun deleteAllMetadata()
    @Query("DELETE FROM achievement_match_notes") suspend fun deleteAllMatchNotes()
    @Query("DELETE FROM steam_no_achievements") suspend fun deleteSteamNoAchievementMemo()
    @Query("UPDATE steam_owned_games SET synced_playtime_minutes = NULL") suspend fun invalidateSteamBookmarks()

    /**
     * Removes every achievement record PFP stores — per-coin rows, set summaries (legacy imports
     * included), the ledger, sync state, metadata and match notes — in one transaction. Library
     * games, provider links and credentials are untouched; the Steam owned-games cache stays for
     * local-copy ownership, with only its achievement bookmarks invalidated.
     */
    @Transaction
    suspend fun clearAllAchievementRecords() {
        deleteAllCoins()
        deleteAllSets()
        deleteAllIdentities()
        deleteAllProviderState()
        deleteAllMetadata()
        deleteAllMatchNotes()
        deleteSteamNoAchievementMemo()
        invalidateSteamBookmarks()
    }
}
