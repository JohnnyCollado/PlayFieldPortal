package com.playfieldportal.core.ui.preview

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.ui.wave.WaveRenderMode
import com.playfieldportal.feature.xmb.viewmodel.BackgroundTaskInfo
import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBUiState

// Shared fake data used by Compose @Preview annotations and the debug menu.
// Never referenced in production code paths.
object PreviewData {

    val categories = listOf(
        Category(BuiltInCategory.FAVORITES,       "Favorites",       "ic_favorites",  type = CategoryType.BUILT_IN, position = 0),
        Category(BuiltInCategory.RECENTLY_PLAYED, "Recently Played", "ic_recent",     type = CategoryType.BUILT_IN, position = 1),
        Category(BuiltInCategory.GAMES,           "Games",           "ic_games",      type = CategoryType.BUILT_IN, position = 2),
        Category("ps2",                           "PlayStation 2",   "ic_platform_ps2", type = CategoryType.PLATFORM, position = 3),
        Category("gba",                           "Game Boy Advance","ic_platform_gba", type = CategoryType.PLATFORM, position = 4),
        Category(BuiltInCategory.ANDROID,         "Android",         "ic_android",    type = CategoryType.BUILT_IN, position = 5),
        Category(BuiltInCategory.APP_DRAWER,      "App Drawer",      "ic_apps",       type = CategoryType.BUILT_IN, position = 6),
        Category(BuiltInCategory.SETTINGS,        "Settings",        "ic_settings",   type = CategoryType.BUILT_IN, position = 7),
    )

    val ps2Games = listOf(
        XMBItem("1",  "Shadow of the Colossus",  subtitle = "PS2 · 12h 34m played"),
        XMBItem("2",  "God of War",               subtitle = "PS2 · 8h 02m played"),
        XMBItem("3",  "Gran Turismo 4",            subtitle = "PS2 · 24h 11m played"),
        XMBItem("4",  "Kingdom Hearts",            subtitle = "PS2"),
        XMBItem("5",  "Ico",                       subtitle = "PS2"),
        XMBItem("6",  "Silent Hill 2",             subtitle = "PS2"),
        XMBItem("7",  "Metal Gear Solid 3",        subtitle = "PS2 · Last played yesterday"),
        XMBItem("8",  "Devil May Cry 3",           subtitle = "PS2"),
        XMBItem("9",  "Ratchet & Clank",           subtitle = "PS2"),
        XMBItem("10", "Jak and Daxter",            subtitle = "PS2"),
    )

    val gbaGames = listOf(
        XMBItem("20", "Pokémon FireRed",           subtitle = "GBA · 47h played"),
        XMBItem("21", "The Legend of Zelda: Minish Cap", subtitle = "GBA"),
        XMBItem("22", "Metroid Fusion",            subtitle = "GBA"),
        XMBItem("23", "Castlevania: Aria of Sorrow", subtitle = "GBA"),
        XMBItem("24", "Fire Emblem",               subtitle = "GBA · 31h played"),
        XMBItem("25", "Golden Sun",                subtitle = "GBA"),
    )

    val favoriteItems = listOf(
        XMBItem("1",  "Shadow of the Colossus",  subtitle = "PS2 · Favorite"),
        XMBItem("20", "Pokémon FireRed",          subtitle = "GBA · Favorite"),
        XMBItem("7",  "Metal Gear Solid 3",       subtitle = "PS2 · Favorite"),
    )

    val emptyItems = emptyList<XMBItem>()

    val runningTasks = listOf(
        BackgroundTaskInfo("scan",    "Library Scan",    progress = 0.62f),
        BackgroundTaskInfo("artwork", "Artwork Fetch",   progress = 0.31f),
    )

    val completedTasks = listOf(
        BackgroundTaskInfo("sync",  "Profile Sync",  progress = 1f,  isCompleted = true),
        BackgroundTaskInfo("fail",  "Backup",        progress = null, isFailed = true, errorMessage = "Storage full"),
    )

    // Pre-built UiState snapshots for different preview scenarios
    val defaultState = XMBUiState(
        categories           = categories,
        selectedCategoryIndex = 2,
        currentItems         = ps2Games,
        selectedItemIndex    = 0,
        showBootSequence     = false,
        waveRenderMode       = WaveRenderMode.FULL,
    )

    val emptyLibraryState = XMBUiState(
        categories           = categories,
        selectedCategoryIndex = 2,
        currentItems         = emptyItems,
        showBootSequence     = false,
    )

    val withTasksState = XMBUiState(
        categories            = categories,
        selectedCategoryIndex = 2,
        currentItems          = ps2Games,
        showBootSequence      = false,
        showTaskTray          = true,
        activeBackgroundTasks = 2,
        backgroundTasks       = runningTasks + completedTasks,
    )

    val bootState = XMBUiState(
        categories       = categories,
        currentItems     = ps2Games,
        showBootSequence = true,
    )
}
