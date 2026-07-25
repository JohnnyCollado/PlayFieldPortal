package com.playfieldportal.feature.settings.ui

import com.playfieldportal.feature.settings.viewmodel.LibraryCardRow
import com.playfieldportal.feature.settings.viewmodel.LibraryManagerUiState
import com.playfieldportal.feature.settings.viewmodel.LibraryStep
import com.playfieldportal.feature.settings.viewmodel.RootFolderRow

object SettingsPreviewData {
    val mockCards = listOf(
        LibraryCardRow(
            platformId = "psp",
            displayName = "PlayStation Portable",
            enabled = true,
            pinned = true,
            romDirectory = "/Roms/psp",
            treeUri = "content://...",
            emulatorName = "PPSSPP",
            extensions = listOf("iso", "cso"),
            gameCount = 24
        ),
        LibraryCardRow(
            platformId = "gba",
            displayName = "Game Boy Advance",
            enabled = true,
            pinned = false,
            romDirectory = "/Roms/gba",
            treeUri = "content://...",
            emulatorName = "RetroArch (mGBA)",
            extensions = listOf("gba", "zip"),
            gameCount = 42
        ),
        LibraryCardRow(
            platformId = "android",
            displayName = "Android Apps",
            enabled = true,
            pinned = false,
            romDirectory = null,
            treeUri = null,
            emulatorName = null,
            extensions = emptyList(),
            gameCount = 15
        )
    )

    val mockRoots = listOf(
        RootFolderRow(
            treeUri = "content://...",
            name = "Internal Storage (Roms)",
            linked = true,
            consoles = "psp, gba, snes"
        )
    )

    val libraryListState = LibraryManagerUiState(
        step = LibraryStep.LIST,
        cards = mockCards,
        romRoots = mockRoots
    )
}
