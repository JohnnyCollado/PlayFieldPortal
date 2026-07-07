package com.playfieldportal.themekit

/**
 * The themeable XMB icon surface — every UI glyph a theme may replace, keyed stably.
 *
 * This registry is the single source of truth shared by the launcher (which resolves an
 * applied theme's `icons/<key>.png` overrides at render time) and the desktop Theme Studio
 * (which edits the slots and exports the editable template pack). Platform/console icons
 * (`sysicon_*`) and physical-media art are deliberately NOT slots: those identify content,
 * not UI, and stay uniform across themes.
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
    enum class Group { CATEGORY_BAR, ITEMS, STATUS }
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

        // ── First-level item glyphs (one key per semantic slot, not per shape:
        //    the video folder and photo folder may diverge in a custom theme) ──
        item("item_add", "Add / create action"),
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

        // ── Status strip ─────────────────────────────────────────────────────
        status("status_battery_full", "Battery (full)"),
        status("status_battery_high", "Battery (high)"),
        status("status_battery_medium", "Battery (medium)"),
        status("status_battery_low", "Battery (low)"),
        status("status_battery_charging", "Battery (charging)"),
        status("status_bluetooth", "Bluetooth"),
    )

    private val byKey: Map<String, IconSlot> = ALL.associateBy { it.key }

    fun byKey(key: String): IconSlot? = byKey[key]

    fun isValidKey(key: String): Boolean = key in byKey
}
