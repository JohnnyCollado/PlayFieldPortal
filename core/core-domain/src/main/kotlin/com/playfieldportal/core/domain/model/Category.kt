package com.playfieldportal.core.domain.model

import kotlinx.serialization.Serializable

data class Category(
    val id: String,
    val name: String,
    val iconKey: String,                // built-in icon key or "custom"
    val customIconUri: String? = null,
    val accentColor: Long? = null,      // per-category wave color override
    val type: CategoryType,
    val position: Int,
    val isVisible: Boolean = true,
    val filterRules: FilterRules? = null, // only for SMART type
    val isGamingCategory: Boolean = false, // true for games/collections, false for apps
)

enum class CategoryType {
    BUILT_IN,       // Favorites, Recently Played, Games, Android, App Drawer, Settings
    PLATFORM,       // Pinned platform — mirrors a Platform entry
    SMART,          // Auto-populates by filter rules
    SHORTCUT_GROUP, // Groups specific app shortcuts
    MANUAL,         // User hand-picks items
}

@Serializable
data class FilterRules(
    val platformIds: List<String> = emptyList(),
    val maxLastPlayedDays: Int? = null,
    val isFavoriteOnly: Boolean = false,
    val genres: List<String> = emptyList(),
)

// Built-in category IDs — never change these
object BuiltInCategory {
    const val FAVORITES        = "favorites"
    const val RECENTLY_PLAYED  = "recently_played"
    const val GAMES            = "games"
    const val ANDROID          = "android"
    const val APP_DRAWER       = "app_drawer"
    const val SETTINGS         = "settings"
}
