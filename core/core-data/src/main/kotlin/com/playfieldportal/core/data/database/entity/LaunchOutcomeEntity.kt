package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One recorded game-launch verdict (B1 — launch reliability). Written after the launch attempt is
 * settled: immediately for an intent/preflight failure, or when the host lifecycle classifies a
 * successful dispatch as a real session or a never-foregrounded launch.
 *
 * Deliberately a dumb log row: the emulator/core/source snapshot is captured at launch time so the
 * row always says what WAS launched, even if the user changes defaults later. `outcome` stores the
 * feature-launcher enum name (SUCCEEDED / NEVER_FOREGROUNDED / INTENT_FAILED) and `source` the B4
 * LaunchSource name — core-data stays free of feature types and maps through LaunchOutcomeRecorder.
 */
@Entity(
    tableName = "launch_outcomes",
    indices = [
        Index("game_id"),
        Index("platform_id"),
    ],
)
data class LaunchOutcomeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "game_id")
    val gameId: Long,

    @ColumnInfo(name = "game_title")
    val gameTitle: String,

    @ColumnInfo(name = "platform_id")
    val platformId: String?,

    @ColumnInfo(name = "emulator_id")
    val emulatorId: String?,

    @ColumnInfo(name = "emulator_name")
    val emulatorName: String?,

    @ColumnInfo(name = "core_path")
    val corePath: String?,

    @ColumnInfo(name = "core_name")
    val coreName: String?,

    // LaunchSource enum name; null for package/shortcut-backed launches with no emulator ladder.
    @ColumnInfo(name = "source")
    val source: String?,

    // LaunchOutcomeStatus enum name.
    @ColumnInfo(name = "outcome")
    val outcome: String,

    @ColumnInfo(name = "failure_reason")
    val failureReason: String?,

    // Wall-clock dispatch time.
    @ColumnInfo(name = "launched_at_ms")
    val launchedAtMs: Long,

    // When the user returned to the launcher (null until the launch settles).
    @ColumnInfo(name = "returned_at_ms")
    val returnedAtMs: Long? = null,
)
