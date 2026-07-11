package com.playfieldportal.core.domain.model

/**
 * How a game entity's XMB tile is drawn. [ICON0] is the PSP-authentic 144:80 edge-to-edge fill
 * (and the only mode that plays ICON1 video snaps); the other three render their artwork at
 * natural aspect with the selection chrome hugging the fitted bounds. A game with no art for
 * its mode falls back toward the ICON0 look ([PHYSICAL_MEDIA] falls back to the bundled
 * per-platform cartridge/disc icon instead).
 *
 * Stored as the enum name: globally in DataStore, per-game in `games.icon_display_mode`
 * (null = follow the global setting).
 */
enum class IconDisplayMode(val label: String) {
    ICON0("Custom Icon"),
    BOX_ART("Box Art"),
    PHYSICAL_MEDIA("Physical Media"),
    BOX_3D("3D Box Art");

    companion object {
        val DEFAULT = ICON0

        fun fromName(name: String?): IconDisplayMode? = entries.firstOrNull { it.name == name }
    }
}
