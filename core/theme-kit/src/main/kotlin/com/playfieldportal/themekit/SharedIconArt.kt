package com.playfieldportal.themekit

/**
 * Icon slots whose built-in art is one picture, grouped once so every job that fills one of them
 * fills them all: a PTF import ([PtfIcons]) and each editor's default art (the launcher's
 * DefaultSlotGlyph, the Studio's StudioIconSet) read these lists instead of keeping their own.
 *
 * The slots stay separate keys, so a user or a theme can still give each one its own art; only
 * the jobs that set a whole family at once fan out over a group. The Studio's
 * StudioIconSetSharedArtTest fails when two slots ship byte-identical art without being grouped here.
 */
object SharedIconArt {

    /**
     * The default memory card: the media library cards, a games collection with no picked icon,
     * All Games (console art, the same picture) and the Shiba Coins hub's All Tracked Games row.
     */
    val MEMORY_CARD: List<String> = listOf(
        "item_memcard_games", "item_memcard_music", "item_memcard_video", "item_memcard_photos",
        "sysicon_allgames", "item_shiba_track",
    )

    /** The Game column glyph, which is also the generic art for a console with none of its own. */
    val GAMES: List<String> = listOf("catbar_games", "sysicon_default")

    val ALL: List<List<String>> = listOf(MEMORY_CARD, GAMES)

    /** The group [key] draws its art with, or just [key] when it shares its art with no other slot. */
    fun groupOf(key: String): List<String> = ALL.firstOrNull { key in it } ?: listOf(key)
}
