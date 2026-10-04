package com.playfieldportal.themekit

/**
 * The themeable XMB icon surface — every UI glyph a theme may replace, keyed stably.
 *
 * This registry is the single source of truth shared by the launcher (which resolves an
 * applied theme's `icons/<key>.png` overrides at render time) and the desktop Theme Studio
 * (which edits the slots and exports the editable template pack). Platform/console icons
 * (`sysicon_*`) are not part of this list: they travel under `sysicons/` and are registered by
 * [CustomizableIcons], as is physical-media art (`physmedia_*`, under `mediaicons/`).
 *
 * Keys are also the bundle entry names (`icons/<key>.png` inside a `.pfptheme`), so they are
 * forever-stable: never rename one, only add.
 */
data class IconSlot(
    val key: String,
    val group: Group,
    /** Human label for editors ("Games", "Video folder"...). */
    val displayName: String,
    /** Square canvas size (px) for exported editable templates. */
    val templateSizePx: Int,
) {
    /**
     * Bucket a slot renders in. CONSOLE is not an `IconSlots` bucket in the bundle contract
     * (`icons/` entries stay gated by [IconSlots.isValidKey]) — it exists for the app-side
     * superset registry [CustomizableIcons], whose console slots travel under the separate
     * `sysicons/` bundle directory (schema v3).
     *
     * SHIBA, MEDIA, GAME_DETAIL, NOTIFICATIONS and MENUS (schema v4) are theme-only groups:
     * they travel under `icons/` like the rest but neither editor lists them. PHYSICAL_MEDIA is,
     * like CONSOLE, a [CustomizableIcons] group: its slots travel under `mediaicons/`.
     */
    enum class Group { CATEGORY_BAR, ITEMS, STATUS, CONSOLE, SHIBA, MEDIA, GAME_DETAIL, NOTIFICATIONS, MENUS, PHYSICAL_MEDIA }
}

object IconSlots {

    private const val CATBAR_TEMPLATE_PX = 256
    private const val ITEM_TEMPLATE_PX = 256
    private const val STATUS_TEMPLATE_PX = 128

    private fun catbar(key: String, name: String) =
        IconSlot(key, IconSlot.Group.CATEGORY_BAR, name, CATBAR_TEMPLATE_PX)

    private fun item(key: String, name: String) =
        IconSlot(key, IconSlot.Group.ITEMS, name, ITEM_TEMPLATE_PX)

    private fun status(key: String, name: String) =
        IconSlot(key, IconSlot.Group.STATUS, name, STATUS_TEMPLATE_PX)

    private fun slot(key: String, group: IconSlot.Group, name: String) =
        IconSlot(key, group, name, ITEM_TEMPLATE_PX)

    val ALL: List<IconSlot> = listOf(
        // ── Category bar (crossbar column glyphs) ────────────────────────────
        catbar("catbar_games", "Games"),
        catbar("catbar_music", "Music"),
        catbar("catbar_video", "Video"),
        catbar("catbar_photos", "Photos"),
        catbar("catbar_settings", "Settings"),
        catbar("catbar_network", "Network"),
        catbar("catbar_appstore", "App Store"),
        catbar("catbar_social", "Social"),
        catbar("catbar_favorites", "Favorites"),
        catbar("catbar_achievements", "Shiba Coins"),

        // ── First-level item glyphs (one key per semantic slot, not per shape:
        //    the video folder and photo folder may diverge in a custom theme) ──
        item("item_add", "Add / create action"),
        item("item_missing", "Missing games bucket"),
        // Default memory-card art: the "Music"/"Videos"/"Photos" library cards and the
        // Games-side default (collections without a picked icon). Per-console cards keep
        // their console identity and stay non-themeable.
        item("item_memcard_games", "Memory card (Games)"),
        item("item_memcard_music", "Memory card (Music)"),
        item("item_memcard_video", "Memory card (Video)"),
        item("item_memcard_photos", "Memory card (Photos)"),
        item("item_settings", "Settings item (wrench)"),
        item("item_video_folder", "Video folder"),
        item("item_video_library", "Video library"),
        item("item_video_recent", "Recent videos"),
        item("item_video_favorites", "Favorite videos"),
        item("item_video_collections", "Video collections"),
        item("item_video_apps", "Video apps"),
        item("item_video_file", "Video file"),
        item("item_photo_folder", "Photo folder"),
        item("item_photo_file", "Photo file"),
        item("item_photo_albums", "Photo albums"),
        item("item_photo_apps", "Photo apps"),
        item("item_camera", "Camera"),
        item("item_music_track", "Music track"),
        item("item_playlist", "Playlist"),
        item("item_music_apps", "Music apps"),
        item("item_social_add", "Add friend (QR)"),
        item("item_social_account", "Social account"),
        item("item_social_friends", "Friends"),
        item("item_social_voice", "Voice chat"),
        item("item_social_voice_invite", "Voice invite"),
        item("item_social_voice_mute", "Voice mute"),
        item("item_social_voice_settings", "Voice settings"),
        item("item_social_voice_leave", "Leave voice"),
        item("item_social_activity", "Activity status"),
        item("item_social_discord_settings", "Discord settings"),
        item("item_social_signout", "Sign out"),

        // ── Shiba Coins (achievements) hub rows ──────────────────────────────
        item("item_shiba_connect", "Connect account"),
        item("item_shiba_track", "Tracked"),
        item("item_shiba_untracked", "Untracked"),

        // ── Status strip ─────────────────────────────────────────────────────
        status("status_battery_full", "Battery (full)"),
        status("status_battery_high", "Battery (high)"),
        status("status_battery_medium", "Battery (medium)"),
        status("status_battery_low", "Battery (low)"),
        status("status_battery_charging", "Battery (charging)"),
        status("status_bluetooth", "Bluetooth"),

        // ── Schema v4 additions: appended only, so everything above keeps its order ──
        status("status_notifications", "Notifications"),
        status("status_controller", "Controller"),
        status("status_wifi", "Wi-Fi"),
        status("status_signal", "Cellular signal"),

        slot("shiba_coin_bronze", IconSlot.Group.SHIBA, "Bronze coin"),
        slot("shiba_coin_silver", IconSlot.Group.SHIBA, "Silver coin"),
        slot("shiba_coin_gold", IconSlot.Group.SHIBA, "Gold coin"),
        slot("shiba_coin_platinum", IconSlot.Group.SHIBA, "Platinum coin"),

        slot("media_play", IconSlot.Group.MEDIA, "Play"),
        slot("media_pause", IconSlot.Group.MEDIA, "Pause"),
        slot("media_prev", IconSlot.Group.MEDIA, "Previous"),
        slot("media_next", IconSlot.Group.MEDIA, "Next"),
        slot("media_back10", IconSlot.Group.MEDIA, "Back 10 seconds"),
        slot("media_fwd10", IconSlot.Group.MEDIA, "Forward 10 seconds"),

        slot("detail_play", IconSlot.Group.GAME_DETAIL, "Play"),
        slot("detail_favorite", IconSlot.Group.GAME_DETAIL, "Favorite"),
        slot("detail_artwork", IconSlot.Group.GAME_DETAIL, "Artwork"),
        slot("detail_manual", IconSlot.Group.GAME_DETAIL, "Manual"),
        slot("detail_more", IconSlot.Group.GAME_DETAIL, "More"),

        slot("notif_album", IconSlot.Group.NOTIFICATIONS, "Scan"),
        slot("notif_image", IconSlot.Group.NOTIFICATIONS, "Artwork"),
        slot("notif_tag", IconSlot.Group.NOTIFICATIONS, "Metadata"),
        slot("notif_coin", IconSlot.Group.NOTIFICATIONS, "Achievement"),
        slot("notif_blocked", IconSlot.Group.NOTIFICATIONS, "Launch blocked"),
        slot("notif_settings", IconSlot.Group.NOTIFICATIONS, "System"),
        slot("notif_download", IconSlot.Group.NOTIFICATIONS, "Download"),
        slot("notif_feed", IconSlot.Group.NOTIFICATIONS, "Feed"),

        slot("menu_check", IconSlot.Group.MENUS, "Check mark"),
        slot("menu_back", IconSlot.Group.MENUS, "Back arrow"),

        // The UMD slot atop each gaming column, unfocused: the PSP's UMD whatever the game.
        // Appended (keys only ever add); focused, it still turns into the game's ICON0.
        item("item_umd", "UMD slot"),
    )

    private val byKey: Map<String, IconSlot> = ALL.associateBy { it.key }

    fun byKey(key: String): IconSlot? = byKey[key]

    fun isValidKey(key: String): Boolean = key in byKey
}
