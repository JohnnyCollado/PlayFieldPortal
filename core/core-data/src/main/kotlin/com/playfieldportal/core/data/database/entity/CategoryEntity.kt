package com.playfieldportal.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.FilterRules
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey
    val id: String,

    val name: String,

    @ColumnInfo(name = "icon_key")
    val iconKey: String,

    @ColumnInfo(name = "custom_icon_uri")
    val customIconUri: String? = null,

    @ColumnInfo(name = "accent_color")
    val accentColor: Long? = null,

    val type: String,               // CategoryType.name()

    val position: Int,

    @ColumnInfo(name = "is_visible")
    val isVisible: Boolean = true,

    // JSON-serialized FilterRules — null for non-SMART categories
    @ColumnInfo(name = "filter_rules_json")
    val filterRulesJson: String? = null,
)

private val json = Json { ignoreUnknownKeys = true }

fun CategoryEntity.toDomain() = Category(
    id             = id,
    name           = name,
    iconKey        = iconKey,
    customIconUri  = customIconUri,
    accentColor    = accentColor,
    type           = CategoryType.valueOf(type),
    position       = position,
    isVisible      = isVisible,
    filterRules    = filterRulesJson?.let {
        runCatching { json.decodeFromString<FilterRules>(it) }.getOrNull()
    },
)

fun Category.toEntity() = CategoryEntity(
    id              = id,
    name            = name,
    iconKey         = iconKey,
    customIconUri   = customIconUri,
    accentColor     = accentColor,
    type            = type.name,
    position        = position,
    isVisible       = isVisible,
    filterRulesJson = filterRules?.let {
        runCatching { json.encodeToString(it) }.getOrNull()
    },
)
