package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable
import androidx.room.PrimaryKey

/**
 * Per-provider scheduling state for the selective achievement update: when the provider was last
 * checked, when a failed check may retry, the last actionable pause (so a scheduled repeat of the
 * same condition is not reported again), and RetroAchievements' rotating daily summary cohort.
 */
@Serializable
@Entity(tableName = "achievement_provider_sync_state")
data class AchievementProviderSyncStateEntity(
    // AchievementProvider enum name.
    @PrimaryKey
    val provider: String,

    @ColumnInfo(name = "last_checked_at")
    val lastCheckedAt: Long? = null,

    @ColumnInfo(name = "retry_at")
    val retryAt: Long? = null,

    @ColumnInfo(name = "failure_count", defaultValue = "0")
    val failureCount: Int = 0,

    // UpdatePause code of the last reported pause; null once the provider checks cleanly again.
    @ColumnInfo(name = "paused_reason")
    val pausedReason: String? = null,

    // RA only: the next of seven stable cohorts to summary-check, and the epoch day it last ran.
    @ColumnInfo(name = "cohort_next", defaultValue = "0")
    val cohortNext: Int = 0,

    @ColumnInfo(name = "cohort_last_day")
    val cohortLastDay: Long? = null,
)
