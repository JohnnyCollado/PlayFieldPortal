package com.playfieldportal.themekit

/** The icon editors' tabs, in tab order: the same four on the device and in the Theme Studio. */
enum class IconEditorTab(val label: String) {
    CROSSBAR("Crossbar"),
    ITEMS("Items"),
    CONSOLES("Consoles"),
    PHYSICAL_MEDIA("Physical Media"),
}

/**
 * Item slots that sit in one XMB column, top to bottom. [columnKeys] are the crossbar slots of the
 * column(s) the run belongs to; the run sits where the first of them sits on the bar.
 */
data class IconColumnRun(
    val label: String,
    val columnKeys: List<String>,
    val slots: List<IconSlot>,
)

/**
 * The one icon list both editors show — Customize XMB Icons on the device and the Theme Studio's
 * picker — so the two can never drift apart. Every slot is listed in the order the XMB shows it:
 * the crossbar left to right, then each column's item rows top to bottom, then the console cards
 * and their physical media.
 *
 * Not listed: the status strip, Shiba Coin medallions, media controls, Game Detail, notifications
 * and menus (they follow the theme's colours). Those slots stay in
 * [CustomizableIcons], so a theme that carries them still applies them.
 */
object IconEditorLayout {

    /** The crossbar's seeded order (CategoryRepositoryImpl), used when no live bar is given. */
    val DEFAULT_BAR_ORDER: List<String> = listOf(
        "catbar_settings", "catbar_photos", "catbar_music", "catbar_video", "catbar_games",
        "catbar_network", "catbar_appstore", "catbar_social", "catbar_achievements",
    )

    private class Run(val label: String, val columnKeys: List<String>, val keys: List<String>)

    private val RUNS = listOf(
        Run("Settings", listOf("catbar_settings"), listOf("item_settings")),
        Run(
            "Photo", listOf("catbar_photos"),
            listOf("item_camera", "item_photo_albums", "item_photo_folder", "item_photo_file", "item_photo_apps", "item_memcard_photos"),
        ),
        Run("Music", listOf("catbar_music"), listOf("item_music_track", "item_playlist", "item_music_apps", "item_memcard_music")),
        Run(
            "Video", listOf("catbar_video"),
            listOf(
                "item_video_collections", "item_video_recent", "item_video_favorites", "item_video_library",
                "item_video_folder", "item_video_file", "item_video_apps", "item_memcard_video",
            ),
        ),
        Run(
            "Game", listOf("catbar_games"),
            listOf("item_umd", "sysicon_allgames", "sysicon_favorites", "item_missing", "item_memcard_games"),
        ),
        // Installed apps draw their own icons; Add Apps is the one row these columns theme.
        Run("Network · App Store", listOf("catbar_network", "catbar_appstore"), listOf("item_add")),
        Run(
            "Social", listOf("catbar_social"),
            listOf(
                "item_social_account", "item_social_friends", "item_social_add", "item_social_voice",
                "item_social_voice_invite", "item_social_voice_mute", "item_social_voice_settings",
                "item_social_voice_leave", "item_social_activity", "item_social_discord_settings", "item_social_signout",
            ),
        ),
        Run("Shiba Coins", listOf("catbar_achievements"), listOf("item_shiba_connect", "item_shiba_track", "item_shiba_untracked")),
    )

    private fun slot(key: String): IconSlot = requireNotNull(CustomizableIcons.byKey(key)) { "unregistered slot $key" }

    /** Crossbar slots in [barOrder] (crossbar slot keys; unknown keys are skipped), then the rest in default order. */
    fun crossbar(barOrder: List<String> = DEFAULT_BAR_ORDER): List<IconSlot> {
        val live = barOrder.filter { it in DEFAULT_BAR_ORDER }.distinct()
        return (live + DEFAULT_BAR_ORDER.filter { it !in live }).map(::slot)
    }

    /** Item runs ordered by where their column sits in [barOrder]; columns not on it keep default order after it. */
    fun itemRuns(barOrder: List<String> = DEFAULT_BAR_ORDER): List<IconColumnRun> {
        val position = crossbar(barOrder).withIndex().associate { (i, s) -> s.key to i }
        return RUNS
            .sortedBy { run -> run.columnKeys.minOf { position.getValue(it) } }
            .map { IconColumnRun(it.label, it.columnKeys, it.keys.map(::slot)) }
    }

    private val ITEM_KEYS: Set<String> = RUNS.flatMap { it.keys }.toSet()

    private val CONSOLES: List<IconSlot> =
        CustomizableIcons.group(IconSlot.Group.CONSOLE).filter { it.key !in ITEM_KEYS }

    /** A tab's slots in XMB order; [barOrder] is the live crossbar (crossbar slot keys) for the first two tabs. */
    fun slots(tab: IconEditorTab, barOrder: List<String> = DEFAULT_BAR_ORDER): List<IconSlot> = when (tab) {
        IconEditorTab.CROSSBAR -> crossbar(barOrder)
        IconEditorTab.ITEMS -> itemRuns(barOrder).flatMap { it.slots }
        IconEditorTab.CONSOLES -> CONSOLES
        IconEditorTab.PHYSICAL_MEDIA -> CustomizableIcons.group(IconSlot.Group.PHYSICAL_MEDIA)
    }

    /** Every listed slot, tab by tab, in the default bar order. */
    val ALL: List<IconSlot> = IconEditorTab.entries.flatMap { slots(it) }

    private val TAB_BY_KEY: Map<String, IconEditorTab> =
        IconEditorTab.entries.flatMap { tab -> slots(tab).map { it.key to tab } }.toMap()

    /** The tab that lists [key], or null when no editor lists it. */
    fun tabOf(key: String): IconEditorTab? = TAB_BY_KEY[key]
}
