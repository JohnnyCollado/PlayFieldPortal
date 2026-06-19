package com.playfieldportal.feature.xmb.preview

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
        Category(BuiltInCategory.SETTINGS, "Settings", "ic_settings", type = CategoryType.BUILT_IN, position = 0),
        Category("photos",                 "Photos",   "ic_photos",   type = CategoryType.BUILT_IN, position = 1),
        Category("music",                  "Music",    "ic_music",    type = CategoryType.BUILT_IN, position = 2),
        Category("videos",                 "Videos",   "ic_videos",   type = CategoryType.BUILT_IN, position = 3),
        Category(BuiltInCategory.GAMES,    "Games",    "ic_games",    type = CategoryType.BUILT_IN, position = 4),
        Category("network",                "Network",  "ic_network",  type = CategoryType.BUILT_IN, position = 5),
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

    val platformFolders = listOf(
        XMBItem("platform_psp",    "PSP",            subtitle = "3 games",  platformId = "psp"),
        XMBItem("platform_ps2",    "PlayStation 2",  subtitle = "10 games", platformId = "ps2"),
        XMBItem("platform_n64",    "Nintendo 64",    subtitle = "7 games",  platformId = "n64"),
        XMBItem("platform_snes",   "Super Nintendo", subtitle = "12 games", platformId = "snes"),
        XMBItem("platform_arcade", "Arcade",         subtitle = "18 games", platformId = "arcade"),
        XMBItem("platform_dc",     "Dreamcast",      subtitle = "6 games",  platformId = "dreamcast"),
    )

    val runningTasks = listOf(
        BackgroundTaskInfo("scan",    "Library Scan",    progress = 0.62f),
        BackgroundTaskInfo("artwork", "Artwork Fetch",   progress = 0.31f),
    )

    val completedTasks = listOf(
        BackgroundTaskInfo("sync",  "Profile Sync",  progress = 1f,  isCompleted = true),
        BackgroundTaskInfo("fail",  "Backup",        progress = null, isFailed = true, errorMessage = "Storage full"),
    )

    val defaultState = XMBUiState(
        categories            = categories,
        selectedCategoryIndex = 4,
        currentItems          = platformFolders,
        selectedItemIndex     = 0,
        showBootSequence      = false,
        waveRenderMode        = WaveRenderMode.FULL,
    )

    val emptyLibraryState = XMBUiState(
        categories            = categories,
        selectedCategoryIndex = 4,
        currentItems          = emptyItems,
        showBootSequence      = false,
    )

    val withTasksState = XMBUiState(
        categories            = categories,
        selectedCategoryIndex = 4,
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
