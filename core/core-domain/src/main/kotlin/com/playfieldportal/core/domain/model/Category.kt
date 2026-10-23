package com.playfieldportal.core.domain.model

import kotlinx.serialization.Serializable

data class Category(
    val id: String,
    val name: String,
    val iconKey: String,                // catalog icon key (a user image lives in the icon store, not here)
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
    const val GAMES            = "games"
    const val MUSIC            = "music"
    const val VIDEO            = "videos"
    const val PHOTO            = "photos"
    const val ANDROID          = "android"
    const val APP_DRAWER       = "app_drawer"
    const val SETTINGS         = "settings"
    const val SOCIAL           = "social"
    const val ACHIEVEMENTS     = "achievements"
}

// Media, system and the Android column own their layouts and never list custom memory cards.
private val NON_CARD_CATEGORY_IDS = setOf(
    BuiltInCategory.MUSIC, BuiltInCategory.VIDEO, BuiltInCategory.PHOTO, BuiltInCategory.ANDROID,
    BuiltInCategory.APP_DRAWER, BuiltInCategory.SETTINGS, BuiltInCategory.ACHIEVEMENTS,
    BuiltInCategory.SOCIAL,
)

/**
 * True for categories that hold custom memory cards: every gaming category, plus the generic app
 * categories (Network, App Store, custom non-gaming). One rule for the XMB and Settings, so both
 * offer the same places to put a card (survey L28, docs/audits/codebase-survey-2026-10-22.md).
 */
fun Category.showsCustomCards(): Boolean = isGamingCategory || id !in NON_CARD_CATEGORY_IDS

/** The categories a new custom card can go in: gaming ones first, then app ones, each in [categories]' order. */
fun cardCategories(categories: List<Category>): List<Category> =
    categories.filter { it.showsCustomCards() }.sortedByDescending { it.isGamingCategory }
