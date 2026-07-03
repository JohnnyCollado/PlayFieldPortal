package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import com.playfieldportal.core.domain.model.PFPTheme

// Installed theme metadata — the actual assets live in
// /storage/emulated/0/PlayFieldPortal/themes/{id}/
@Serializable
@Entity(tableName = "themes")
data class ThemeEntity(
    @PrimaryKey
    val id: String,

    val name: String,
    val author: String? = null,
    val version: String = "1.0",

    @ColumnInfo(name = "wave_color")
    val waveColor: Long,

    @ColumnInfo(name = "wave_opacity")
    val waveOpacity: Float = 0.7f,

    @ColumnInfo(name = "wave_speed")
    val waveSpeed: Float = 1.0f,

    @ColumnInfo(name = "wave_amplitude")
    val waveAmplitude: Float = 1.0f,

    @ColumnInfo(name = "accent_color")
    val accentColor: Long,

    @ColumnInfo(name = "text_color")
    val textColor: Long,

    @ColumnInfo(name = "background_uri")
    val backgroundUri: String? = null,

    @ColumnInfo(name = "font_key")
    val fontKey: String = "system_default",

    @ColumnInfo(name = "has_boot_animation")
    val hasBootAnimation: Boolean = false,

    @ColumnInfo(name = "boot_animation_uri")
    val bootAnimationUri: String? = null,

    @ColumnInfo(name = "sound_pack_uri")
    val soundPackUri: String? = null,

    @ColumnInfo(name = "package_path")
    val packagePath: String? = null,

    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean = false,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = false,

    @ColumnInfo(name = "installed_at")
    val installedAt: Long = System.currentTimeMillis(),
)

fun ThemeEntity.toDomain() = PFPTheme(
    id               = id,
    name             = name,
    author           = author,
    version          = version,
    waveColor        = waveColor,
    waveOpacity      = waveOpacity,
    waveSpeed        = waveSpeed,
    waveAmplitude    = waveAmplitude,
    accentColor      = accentColor,
    textColor        = textColor,
    backgroundUri    = backgroundUri,
    fontKey          = fontKey,
    hasBootAnimation = hasBootAnimation,
    bootAnimationUri = bootAnimationUri,
    soundPackUri     = soundPackUri,
    packagePath      = packagePath,
    isBuiltIn        = isBuiltIn,
)

fun PFPTheme.toEntity() = ThemeEntity(
    id               = id,
    name             = name,
    author           = author,
    version          = version,
    waveColor        = waveColor,
    waveOpacity      = waveOpacity,
    waveSpeed        = waveSpeed,
    waveAmplitude    = waveAmplitude,
    accentColor      = accentColor,
    textColor        = textColor,
    backgroundUri    = backgroundUri,
    fontKey          = fontKey,
    hasBootAnimation = hasBootAnimation,
    bootAnimationUri = bootAnimationUri,
    soundPackUri     = soundPackUri,
    packagePath      = packagePath,
    isBuiltIn        = isBuiltIn,
)
