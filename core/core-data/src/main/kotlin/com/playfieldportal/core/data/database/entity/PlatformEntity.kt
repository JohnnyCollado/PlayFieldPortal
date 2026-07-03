package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import com.playfieldportal.core.domain.model.Platform

@Serializable
@Entity(tableName = "platforms")
data class PlatformEntity(
    @PrimaryKey
    val id: String,                         // e.g. "ps2", "gba" — never auto-generated

    val name: String,

    @ColumnInfo(name = "short_name")
    val shortName: String,

    @ColumnInfo(name = "icon_res")
    val iconRes: String?,

    @ColumnInfo(name = "accent_color")
    val accentColor: Long,

    @ColumnInfo(name = "is_pinned_to_bar")
    val isPinnedToBar: Boolean = false,

    @ColumnInfo(name = "bar_position")
    val barPosition: Int = -1,

    @ColumnInfo(name = "preferred_emulator_package")
    val preferredEmulatorPackage: String? = null,

    // Comma-separated — kept simple, TypeConverter not needed for display only
    @ColumnInfo(name = "rom_extensions")
    val romExtensions: String = "",
)

fun PlatformEntity.toDomain() = Platform(
    id                       = id,
    name                     = name,
    shortName                = shortName,
    iconRes                  = iconRes,
    accentColor              = accentColor,
    isPinnedToBar            = isPinnedToBar,
    barPosition              = barPosition,
    preferredEmulatorPackage = preferredEmulatorPackage,
    romExtensions            = romExtensions.split(",").filter { it.isNotBlank() },
)

fun Platform.toEntity() = PlatformEntity(
    id                       = id,
    name                     = name,
    shortName                = shortName,
    iconRes                  = iconRes,
    accentColor              = accentColor,
    isPinnedToBar            = isPinnedToBar,
    barPosition              = barPosition,
    preferredEmulatorPackage = preferredEmulatorPackage,
    romExtensions            = romExtensions.joinToString(","),
)
