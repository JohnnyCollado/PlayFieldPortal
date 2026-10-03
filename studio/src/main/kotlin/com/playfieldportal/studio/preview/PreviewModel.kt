package com.playfieldportal.studio.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.lerp
import com.playfieldportal.studio.IconColorChoice
import com.playfieldportal.studio.StudioState
import com.playfieldportal.themekit.ColorCascade
import com.playfieldportal.themekit.XmbLayoutAdjust
import com.playfieldportal.themekit.XmbLayoutSpec

/** Everything the preview canvas needs, resolved from [StudioState]. */
data class XmbPreviewModel(
    /** The one theme color: drives the wave / menu backdrops (launcher: PFPColors.waveColor). */
    val accent: Color,
    val iconTint: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val wallpaper: ImageBitmap?,
    /** The exact wave style (any of `WaveStyles`); [WaveMotion.paramsFor] turns it into speed / amplitude / alpha / frozen. */
    val waveStyle: String,
    /** IconSlots key → custom bitmap; slots not present draw the built-in glyph. */
    val iconOverrides: Map<String, ImageBitmap>,
    /** Per-theme XMB geometry — the canvas positions everything from this, never DEFAULT. */
    val layout: XmbLayoutSpec = XmbLayoutSpec.DEFAULT,
    /** What the launcher would resolve: the theme's own bar line, or the preview-only "my layout" values. */
    val layoutAdjust: XmbLayoutAdjust = PreviewGeometry.effectiveAdjust(layout, enabled = false, XmbLayoutAdjust.DEFAULT),
    /** The theme's text/icon legibility, approximating the launcher's rendering. */
    val legibility: PreviewLegibility = PreviewLegibility.DEFAULT,
) {
    // Launcher parity: PFPColors.accentColor stays WHITE for presets and imports alike
    // (XMBViewModel.toPFPColors / withWaveTint only retint waveColor + gradient), so the
    // menu cursor formulas below lerp from white — matching MenuCursor.kt on device.
    private val pfpAccentColor = Color.White

    /** The drill ◀ colour: PFPColors.accentColor, which stays white (see above). */
    val drillCursor: Color get() = pfpAccentColor

    /** MenuCursor.menuCursorEdge(): lerp(accent, White, 0.55).copy(alpha = 0.95). */
    val menuCursorEdge: Color get() = lerp(pfpAccentColor, Color.White, 0.55f).copy(alpha = 0.95f)

    /** ContextMenuOverlay panel backdrop: waveColor at 75% alpha. */
    val menuPanelBackdrop: Color get() = accent.copy(alpha = 0.75f)
}

/**
 * [adjust] is the preview-only "Preview with my layout" value (null = off, so the theme's own geometry
 * alone applies — which is what the exported preview.png always uses).
 */
fun StudioState.toPreviewModel(adjust: XmbLayoutAdjust? = null): XmbPreviewModel {
    val accentLong = 0xFF000000L or (accentArgb.toLong() and 0xFFFFFF)
    val (top, bottom) = ColorCascade.lightBackgroundAnchors(accentLong)
    return XmbPreviewModel(
        accent = Color(accentArgb),
        // "Auto" has no derivation in the launcher yet either — both render white until
        // derive-from-accent lands (PfpThemeManifest.ICON_COLOR_AUTO contract).
        iconTint = when (val c = iconColor) {
            IconColorChoice.Auto -> Color.White
            is IconColorChoice.Custom -> Color(c.argb)
        },
        backgroundTop = Color(top.toInt()),
        backgroundBottom = Color(bottom.toInt()),
        wallpaper = wallpaperBitmap,
        waveStyle = waveStyle,
        // Console art (full `sysicon_<id>` keys) rides in the same map: the canvas looks every slot up by key.
        iconOverrides = iconBitmaps + sysiconBitmaps,
        layout = layout,
        layoutAdjust = PreviewGeometry.effectiveAdjust(
            layout, enabled = adjust != null, stored = adjust ?: XmbLayoutAdjust.DEFAULT,
        ),
        legibility = PreviewLegibility.of(legibility),
    )
}

/**
 * Sample content for the interactive preview, mirroring the launcher's category rows. The default
 * frame is the Video category with its second row selected: Video's rows exercise themeable item
 * slots (folders, library, recents...) rather than the non-themeable platform icons Games leads with.
 * A row with [Row.children] drills; a row without is a leaf.
 */
object SampleContent {

    data class Category(val slotKey: String, val label: String)
    data class Row(
        val slotKey: String,
        val title: String,
        val subtitle: String? = null,
        val children: List<Row> = emptyList(),
        /** A game in a console's list: what the Games filter searches and sorts, and what carries a PIC0 logo. */
        val isGame: Boolean = false,
    )

    val categories: List<Category> = listOf(
        Category("catbar_settings", "Settings"),
        Category("catbar_photos", "Photo"),
        Category("catbar_music", "Music"),
        Category("catbar_video", "Video"),
        Category("catbar_games", "Game"),
        Category("catbar_network", "Network"),
        Category("catbar_appstore", "App Store"),
        Category("catbar_social", "Social"),
        Category("catbar_achievements", "Shiba Coins"),
        Category("catbar_favorites", "Favorites"),
    )

    const val SELECTED_CATEGORY = 3 // Video
    const val SELECTED_ROW = 1

    private fun leaf(slotKey: String, title: String, subtitle: String? = null) = Row(slotKey, title, subtitle)

    private fun branch(slotKey: String, title: String, subtitle: String?, vararg children: Row) =
        Row(slotKey, title, subtitle, children.toList())

    private fun leaves(slotKey: String, vararg titles: String) = titles.map { leaf(slotKey, it) }.toTypedArray()

    // Declared in Title order, so the Games filter's default sort leaves the list as written.
    private fun games(consoleKey: String) = arrayOf("Crossbar Racing", "Memory Card Blues", "Portal Quest", "Shiba Run")
        .map { Row(consoleKey, it, isGame = true) }.toTypedArray()

    private val settings = listOf(
        leaf("item_settings", "Android Settings", "Opens device settings"),
        branch(
            "item_settings", "Library", "Library Manager, collections, artwork & hidden games",
            *leaves("item_settings", "Library Manager", "Windows Games", "Collections", "Artwork", "Hidden Games"),
        ),
        branch(
            "item_settings", "Emulators", "Launch profiles & RetroArch cores",
            *leaves("item_settings", "Installed", "Custom Emulators", "RetroArch", "Per-System Defaults"),
        ),
        branch(
            "item_settings", "Interface", "Categories, themes, display & controller",
            *leaves("item_settings", "Display", "Sound", "Notifications", "Categories", "Themes", "Controller"),
        ),
        branch(
            "item_settings", "Achievements", "RetroAchievements & Steam",
            *leaves("item_settings", "Player Card", "Provider Credentials", "Local Windows", "Update Achievements"),
        ),
        branch(
            "item_settings", "Media", "Music, video & photo settings",
            *leaves("item_settings", "Music", "Video", "Photo"),
        ),
        branch(
            "item_settings", "System", "About, logs, backup, setup & credits",
            *leaves("item_settings", "About", "Logs", "Backup", "Setup", "Credits"),
        ),
    )

    private val photos = listOf(
        leaf("item_camera", "Camera"),
        branch(
            "item_memcard_photos", "All Photos", "214 photos",
            *leaves("item_photo_file", "Sunset", "Skyline", "Trail Map", "Group Shot"),
        ),
        branch(
            "item_photo_albums", "Photo Albums", null,
            *leaves("item_photo_folder", "Camera Roll", "Screenshots", "Downloads"),
        ),
        leaf("item_photo_apps", "Photo Apps"),
    )

    private val music = listOf(
        leaf("item_music_apps", "Music Apps"),
        branch(
            "item_memcard_music", "All Music", "412 tracks",
            *leaves("item_music_track", "Journey of Dreams", "Midnight Wave", "Memory Card Blues", "Save Point"),
        ),
        branch(
            "item_playlist", "Playlists", null,
            branch("item_playlist", "Road Trip", "3 tracks", *leaves("item_music_track", "Open Highway", "Neon Miles", "Last Exit")),
            branch("item_playlist", "Focus", "2 tracks", *leaves("item_music_track", "Deep Work", "Quiet Hours")),
        ),
    )

    private val video = listOf(
        leaf("item_video_apps", "Video Apps"),
        // The launcher's "Videos" library row is a memory-card slot, not the library glyph.
        branch(
            "item_memcard_video", "Videos", "132 videos",
            *leaves("item_video_file", "Opening Cinematic", "Gameplay Capture", "Boss Fight", "Credits Roll"),
        ),
        branch("item_video_recent", "Recently Watched", null, *leaves("item_video_file", "Boss Fight", "Gameplay Capture")),
        branch("item_video_favorites", "Favorites", null, *leaves("item_video_file", "Opening Cinematic")),
        branch(
            "item_video_collections", "Collections", null,
            leaf("item_video_folder", "Trailers"), leaf("item_video_folder", "Speedruns"),
        ),
    )

    private val gameRows = listOf(
        branch("sysicon_allgames", "All Games", "Every platform", *games("sysicon_allgames")),
        branch("sysicon_favorites", "Favorites", null, *games("sysicon_favorites")),
        branch("sysicon_ps3", "PlayStation 3", null, *games("sysicon_ps3")),
        branch("sysicon_psp", "PlayStation Portable", null, *games("sysicon_psp")),
        branch("sysicon_windows", "Windows", null, *games("sysicon_windows")),
    )

    private val network = listOf(
        leaf("status_wifi", "Wi-Fi", "Connected"),
        leaf("status_bluetooth", "Bluetooth"),
        leaf("item_settings", "Network Settings"),
    )

    private val appStore = listOf(
        leaf("item_add", "Featured"),
        leaf("item_video_library", "Updates"),
        leaf("item_photo_apps", "Installed"),
    )

    private val social = listOf(
        leaf("item_social_add", "Add Friend"),
        branch("item_social_friends", "Friends", "3 online", *leaves("item_social_account", "Alex", "Sam", "Jordan")),
        leaf("item_social_voice", "Voice Chat"),
        leaf("item_social_activity", "Activity Settings", "Share what you're playing"),
        leaf("item_social_discord_settings", "Discord Settings", "Account & sign out"),
        leaf("item_social_signout", "Sign Out"),
    )

    private val shiba = listOf(
        leaf("item_shiba_connect", "Connect Accounts"),
        branch(
            "item_shiba_track", "All Tracked Games", null,
            leaf("shiba_coin_platinum", "Crossbar Racing", "100%"),
            leaf("shiba_coin_gold", "Portal Quest", "72%"),
            leaf("shiba_coin_silver", "Shiba Run", "40%"),
            leaf("shiba_coin_bronze", "Memory Card Blues", "8%"),
        ),
        leaf("item_shiba_untracked", "Untracked"),
    )

    private val favorites = listOf(
        leaf("item_memcard_games", "Favorite Games"),
        leaf("item_video_favorites", "Favorite Videos"),
        leaf("item_music_track", "Favorite Tracks"),
    )

    // Same order as [categories].
    private val byCategory: List<List<Row>> =
        listOf(settings, photos, music, video, gameRows, network, appStore, social, shiba, favorites)

    /** The first-level rows of category [index]. */
    fun rootRows(index: Int): List<Row> = byCategory[index]

    /** The default (Video) category's rows. */
    val rows: List<Row> get() = rootRows(SELECTED_CATEGORY)
}
