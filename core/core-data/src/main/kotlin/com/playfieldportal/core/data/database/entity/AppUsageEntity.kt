package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

// When an app was last launched from PFP. Backs the "Recently Used" app sort without needing
// the Usage Access permission.
@Serializable
@Entity(tableName = "app_usage")
data class AppUsageEntity(
    @PrimaryKey
    @ColumnInfo(name = "package_name")
    val packageName: String,

    @ColumnInfo(name = "last_launched_at")
    val lastLaunchedAt: Long,

    @ColumnInfo(name = "launch_count")
    val launchCount: Int = 0,
)
