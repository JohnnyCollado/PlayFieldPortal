package com.playfieldportal.studio.preview

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.playfieldportal.core.ui.theme.DefaultPFPColors
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.theme.menuCursorEdgeFor
import com.playfieldportal.core.ui.theme.subTextOr
import com.playfieldportal.core.ui.theme.textOr
import com.playfieldportal.studio.IconColorChoice
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.TextColorChoice
import com.playfieldportal.themekit.ColorCascade
import com.playfieldportal.themekit.XmbLayoutAdjust
import com.playfieldportal.themekit.XmbLayoutSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/** Everything the preview canvas needs, resolved from [StudioState]. */
data class XmbPreviewModel(
    /** The one theme color: drives the wave / menu backdrops (launcher: PFPColors.waveColor). */
    val accent: Color,
    val iconTint: Color,
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val wallpaper: ImageBitmap?,
    /** The theme's lock screen image (v5), for the Lock Screen preview; null for none. */
    val lockScreen: ImageBitmap? = null,
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
    /**
     * PFPColors.textPrimary: the theme's text colour, else white. Read by the screens built on
     * PFPColors (detail, Shiba, pickers, Settings); the XMB's own labels go through [textOr].
     */
    val textPrimary: Color = Color.White,
    /**
     * PFPColors.textOverride / subTextOverride: the theme's Main and Sub text colours, null when the
     * theme leaves them to the built-in label colours. The XMB's own labels (crossbar, rows, status
     * strip) repaint through [textOr] / [subTextOr], exactly as the launcher's themedText /
     * themedSubText do.
     */
    val textOverride: Color? = null,
    val subTextOverride: Color? = null,
) {
    /**
     * The launcher's PFPColors for this theme, as XMBViewModel builds them: the one theme colour
     * drives the wave and the gradient anchors, the accent stays white (presets and imports alike),
     * and the text roles follow the Main / Sub colours. Every preview screen derives its palette
     * and its text roles from this through the launcher's own rules (theme-render).
     */
    val pfp: PFPColors = DefaultPFPColors.copy(
        waveColor = accent,
        backgroundTop = backgroundTop,
        backgroundBottom = backgroundBottom,
        iconColor = iconTint,
        textPrimary = textPrimary,
        textSecondary = textPrimary.copy(alpha = 0.7f),
        textOverride = textOverride,
        subTextOverride = subTextOverride,
    )

    /** PFPColors.textOr: the Main text colour at [default]'s weight, else [default] untouched. */
    fun textOr(default: Color): Color = pfp.textOr(default)

    /** PFPColors.textOr(default, weight): for an opaque [default] whose weight is its tone, not its alpha. */
    fun textOr(default: Color, weight: Float): Color = pfp.textOr(default, weight)

    /** PFPColors.subTextOr: the Sub text colour, else the Main one, at [default]'s weight; else [default]. */
    fun subTextOr(default: Color): Color = pfp.subTextOr(default)

    /** PFPColors.subTextOr(default, weight). */
    fun subTextOr(default: Color, weight: Float): Color = pfp.subTextOr(default, weight)

    /** PFPColors.textSecondary: the text colour at 0.7 alpha, as XMBViewModel derives it. */
    val textSecondary: Color get() = pfp.textSecondary

    /** The drill ◀ colour: PFPColors.accentColor, which stays white. */
    val drillCursor: Color get() = pfp.accentColor

    /** MenuCursor.menuCursorEdge(). */
    val menuCursorEdge: Color get() = menuCursorEdgeFor(pfp.accentColor)
}

/**
 * The preview model for the subtree, for screens whose text roles the launcher reads from
 * LocalPFPColors through themedText / themedSubText rather than from a palette.
 */
val LocalPreviewModel = staticCompositionLocalOf<XmbPreviewModel?> { null }

/** themedText(default): the Main colour at [default]'s weight, else [default]. */
@Composable
fun previewText(default: Color): Color = LocalPreviewModel.current?.textOr(default) ?: default

/** themedSubText(default): the Sub (else Main) colour at [default]'s weight, else [default]. */
@Composable
fun previewSubText(default: Color): Color = LocalPreviewModel.current?.subTextOr(default) ?: default

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
        lockScreen = lockScreenBitmap,
        waveStyle = waveStyle,
        // Every slot key — theme slots, console and physical-media art — in one map: the canvas looks each up by key.
        iconOverrides = iconBitmaps,
        layout = layout,
        layoutAdjust = PreviewGeometry.effectiveAdjust(
            layout, enabled = adjust != null, stored = adjust ?: XmbLayoutAdjust.DEFAULT,
        ),
        legibility = PreviewLegibility.of(legibility),
        textPrimary = when (val c = textColor) {
            TextColorChoice.Auto -> Color.White
            is TextColorChoice.Custom -> Color(c.argb)
        },
        textOverride = (textColor as? TextColorChoice.Custom)?.let { Color(it.argb) },
        subTextOverride = (subTextColor as? TextColorChoice.Custom)?.let { Color(it.argb) },
    )
}

/**
 * Sample content for the interactive preview: the launcher's XMB as a fully set-up user sees it —
 * Full flavour, media libraries scanned, a track playing, a game played (so the UMD slot is up),
 * favorites and a custom memory card, Discord signed in, Shiba Coins connected. Rows, order,
 * titles, subtitles, slot keys and menus come from XMBViewModel (settingsSectionItems,
 * photo/music/videoRootItems, memoryCardItems, the app-category branch, the Social and Shiba hubs);
 * only the user data (games, files, apps, friends, counts) is sample. A row with [Row.children]
 * drills; a row without is a leaf.
 */
object SampleContent {

    data class Category(val slotKey: String, val label: String)

    /** How a row's leading icon draws (XMBItemList.XmbItemLeadingIcon). */
    enum class Leading {
        /** A themeable slot: [Row.slotKey]'s override or its built-in glyph. */
        SLOT,

        /** An installed app: the app's own icon, not themeable (the preview draws a stand-in). */
        APP,

        /** A placeholder row (XMBItemType.EMPTY): no icon, drawn at half alpha. */
        EMPTY,

        /**
         * A game with no artwork under the default Custom Icon display: the launcher's PspIcon0Icon
         * letter tile in its platform's accent, not themeable. Only the selected game shows its text.
         */
        GAME,

        /** A photo or video file with no thumbnail: [Row.slotKey]'s glyph framed in a 60x40 tile. */
        THUMB,

        /** A music track with no cover: [Row.slotKey]'s glyph framed in a 56 dp square. */
        COVER,

        /** The UMD slot: the item_umd slot (the PSP's UMD, in the icon colour, by default). */
        UMD,

        /** The Shiba Coins player card: a ring around [Row.badge] ("Lv 27"), in the icon colour. */
        LEVEL,
    }

    data class Row(
        /** The IconSlots key, or null when the row's icon is not themeable. */
        val slotKey: String?,
        val title: String,
        val subtitle: String? = null,
        val children: List<Row> = emptyList(),
        /** A game: what the Games filter searches and sorts. */
        val isGame: Boolean = false,
        val leading: Leading = Leading.SLOT,
        /** A [Leading.GAME] tile's platform accent (PlatformSeeder), ARGB. */
        val accentArgb: Long? = null,
        /** The options menu the launcher gives this row (XMBViewModel.contextMenuKind). */
        val menu: RowKind = RowKind.NONE,
        /** The row the category lands on (XmbLists.defaultRootIndex). */
        val lands: Boolean = false,
        /** [Leading.LEVEL]'s ring text. */
        val badge: String? = null,
    )

    /** CategoryRepositoryImpl.BUILT_IN_CATEGORIES, in seeded order (Social is the Full flavour's). */
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
    )

    /** XMBViewModel.defaultXmbCategoryIndex: the launcher opens on Game, on All Games. */
    const val SELECTED_CATEGORY = 4

    private fun leaf(slotKey: String, title: String, subtitle: String? = null, menu: RowKind = RowKind.NONE) =
        Row(slotKey, title, subtitle, menu = menu)

    private fun branch(slotKey: String, title: String, subtitle: String?, vararg children: Row, menu: RowKind = RowKind.NONE) =
        Row(slotKey, title, subtitle, children.toList(), menu = menu)

    private fun app(title: String) = Row(null, title, leading = Leading.APP, menu = RowKind.APP)

    // The media subtitles' separator ("4032×3024  ·  Jul 14, 2026").
    private const val SEP = "  ·  "

    // ── Settings (settingsSectionItems) ──────────────────────────────────────

    private fun section(title: String, subtitle: String, vararg rows: Pair<String, String>) =
        branch("item_settings", title, subtitle, *rows.map { (t, s) -> leaf("item_settings", t, s) }.toTypedArray())

    private val settings = listOf(
        leaf("item_settings", "Android Settings", "Opens device settings"),
        section(
            "Library", "Sources, cards, artwork & hidden games",
            "Library Manager" to "ROM sources & scanning",
            "Windows Games" to "PC games, launchers & imports",
            "Custom Memory Cards" to "Create & manage custom memory cards",
            "Artwork" to "Scraping sources & cache",
            "Hidden Games" to "Review apps & games you've hidden",
        ),
        section(
            "Emulators", "Launch profiles & RetroArch cores",
            "Installed" to "Detected emulator profiles",
            "Custom Emulators" to "Custom profiles & Add Custom Emulator",
            "RetroArch" to "Core detection & linking",
            "Per-System Defaults" to "Default emulator & core per console, and per-game overrides",
            "Emulator knowledge" to "Updates, your own files & reset to built-in",
        ),
        section(
            "Interface", "Categories, themes, display & controller",
            "Display" to "Wave, wallpaper, boot & icons",
            "Sound" to "Menu & boot sounds",
            "Notifications" to "Panel history & retention",
            "Categories" to "Manage XMB categories",
            "Themes" to "XMB appearance, colors & icons",
            "Controller" to "Button mapping",
        ),
        section(
            "Achievements", "RetroAchievements & Steam",
            "Player Card" to "Levels, ranks & sync status",
            "Provider Credentials" to "RetroAchievements & Steam accounts",
            "Local Windows" to "Track local Windows (Steam-emu) games",
            "Update Achievements" to "Sync & auto-match tracked games",
        ),
        section(
            "Media", "Music, video & photo settings",
            "Music" to "Music folders & default player",
            "Video" to "Video libraries, scanning & playback",
            "Photo" to "Photo libraries & scanning",
        ),
        section(
            "System", "About, logs, setup & credits",
            "About" to "Play Field Portal",
            "Logs" to "Debug & error log viewer",
            "Setup Wizard" to "Guided folder & account setup",
            "Credits" to "Artwork & attributions",
        ),
    )

    // ── Photo (photoRootItems, a library scanned, a camera app present) ──────

    private const val PICK_APPS = "Pick installed apps to show here"

    // A photo opens the fullscreen viewer.
    private fun photo(title: String, size: String, date: String) =
        Row("item_photo_file", title, "$size$SEP$date", leading = Leading.THUMB, menu = RowKind.PHOTO_FILE)

    private val cameraRoll = listOf(
        photo("Beach Sunset", "4032×3024", "Jul 14, 2026"),
        photo("City Lights", "4032×3024", "Jun 2, 2026"),
        photo("Mountain Trail", "3024×4032", "May 19, 2026"),
    )
    private val screenshots = listOf(
        photo("Crossbar Racing", "1920×1080", "Sep 28, 2026"),
        photo("Portal Quest", "1920×1080", "Sep 30, 2026"),
    )

    private fun album(title: String, photos: List<Row>) = Row("item_photo_folder", title, null, photos, menu = RowKind.ALBUM)

    private val photos = listOf(
        leaf("item_camera", "Camera", "Open the camera"),
        branch("item_photo_albums", "Albums", "2 albums", album("Camera Roll", cameraRoll), album("Screenshots", screenshots)),
        branch("item_photo_apps", "Photo Apps", "Open your installed photo apps", app("Gallery"), leaf("item_add", "Add Photo Apps", PICK_APPS)),
        Row("item_memcard_photos", "Photos", null, cameraRoll + screenshots, menu = RowKind.MEDIA_CARD),
    )

    // ── Music (musicRootItems, a track loaded) ───────────────────────────────

    // Now Playing, Playlist and Music open the fullscreen player / browser rather than drilling.
    private val music = listOf(
        Row(
            "item_music_track", "Midnight Wave", "Now Playing${SEP}Neon Arcade",
            leading = Leading.COVER, menu = RowKind.NOW_PLAYING, lands = true,
        ),
        leaf("item_playlist", "Playlist", "Build and play your own track lists"),
        branch("item_music_apps", "Music Apps", "Open your installed music apps", app("Spotify"), leaf("item_add", "Add Music Apps", PICK_APPS)),
        leaf("item_memcard_music", "Music", menu = RowKind.MEDIA_CARD),
    )

    // ── Video (videoRootItems, libraries scanned) ────────────────────────────

    // A video opens its detail screen. Subtitle: the duration (formatDuration).
    private fun video(title: String, duration: String) =
        Row("item_video_file", title, duration, leading = Leading.THUMB, menu = RowKind.VIDEO_FILE)

    private val movies = listOf(video("Big Night Out", "1:52:40"), video("Ocean Deep", "1:31:05"))
    private val captures = listOf(video("Boss Fight", "12:05"), video("Speedrun Attempt", "24:31"))

    private val video = listOf(
        branch(
            "item_video_collections", "Collections", "Recently Watched, Favorites & Playlists",
            branch("item_video_recent", "Recently Watched", "Pick up where you left off", captures[0], movies[1]),
            branch("item_video_favorites", "Favorites", "Your starred videos", movies[0]),
            branch(
                "item_playlist", "Playlists", "Build and play your own lists",
                Row("item_playlist", "Highlights", null, captures, menu = RowKind.VIDEO_PLAYLIST),
                leaf("item_add", "Create Playlist", "Start a new video playlist"),
                leaf("item_add", "Import Playlist", "From an .m3u, .m3u8, .pls or .xspf file"),
            ),
        ),
        branch(
            "item_video_library", "Video Libraries", "2 libraries",
            Row("item_video_folder", "Movies", null, movies, menu = RowKind.VIDEO_LIBRARY),
            Row("item_video_folder", "Captures", null, captures, menu = RowKind.VIDEO_LIBRARY),
        ),
        branch("item_video_apps", "Video Apps", "Open your installed video apps", app("YouTube"), leaf("item_add", "Add Video Apps", PICK_APPS)),
        Row("item_memcard_video", "Videos", null, (movies + captures).sortedBy { it.title }, menu = RowKind.MEDIA_CARD),
    )

    // ── Game (memoryCardItems + the UMD slot) ────────────────────────────────

    private class Console(
        val key: String,
        val card: String,
        val platform: String,
        val accentArgb: Long,
        val titles: List<String>,
    )

    // Titles in Title order, so the Games filter's default sort leaves each list as written.
    private val consoles = listOf(
        Console(
            "sysicon_ps3", "PlayStation 3 Memory Card", "PlayStation 3", 0xFF003087,
            listOf("Crossbar Racing", "Memory Card Blues", "Portal Quest", "Shiba Run"),
        ),
        Console(
            "sysicon_psp", "PlayStation Portable Memory Card", "PlayStation Portable", 0xFF003791,
            listOf("Neon Drift", "Pocket Legends"),
        ),
        // The Windows card is titled "Windows Games" and only shows while it has games.
        Console("sysicon_windows", "Windows Games", "Windows", 0xFF0078D4, listOf("Desktop Dungeon")),
    )

    // Subtitle: platformEmulatorLabel, which is the platform alone until an emulator is known.
    private fun game(console: Console, title: String) =
        Row(null, title, console.platform, isGame = true, leading = Leading.GAME, accentArgb = console.accentArgb, menu = RowKind.GAME)

    private fun count(n: Int) = "$n ${if (n == 1) "Game" else "Games"}"

    private val allGames = consoles.flatMap { c -> c.titles.map { game(c, it) } }.sortedBy { it.title }

    private fun gamesNamed(vararg titles: String) = allGames.filter { it.title in titles }

    private val gameRows: List<Row> = buildList {
        // The last-played game sits in the UMD slot, above the default card (XmbLists.withUmdAboveDefault).
        val played = consoles.first()
        add(Row(null, "Shiba Run", played.platform, leading = Leading.UMD, menu = RowKind.UMD))
        add(Row("sysicon_allgames", "All Games", "Total Games ${allGames.size}", allGames, menu = RowKind.ALL_GAMES, lands = true))
        val favorites = gamesNamed("Crossbar Racing", "Neon Drift")
        add(Row("sysicon_favorites", "Favorites", count(favorites.size), favorites, menu = RowKind.FAVORITES))
        // customCardSubtitle: "Custom · N Games" ("Custom · Pinned · N Games" once pinned).
        val coop = gamesNamed("Portal Quest", "Shiba Run")
        add(Row("item_memcard_games", "Co-op Night", "Custom · ${count(coop.size)}", coop, menu = RowKind.CUSTOM_CARD))
        consoles.forEach { c -> add(Row(c.key, c.card, count(c.titles.size), c.titles.map { game(c, it) }, menu = RowKind.CONSOLE)) }
    }

    // ── Network / App Store: auto-classified installed apps, then Add Apps ───

    private val addApps = leaf("item_add", "Add Apps", "Pick installed apps to add to this section")

    private val network = listOf(app("Chrome"), app("Firefox"), addApps)

    private val appStore = listOf(app("Play Store"), addApps)

    // ── Social (signed in): the account, then its hub ────────────────────────

    private fun voiceSetting(title: String, value: String) = leaf("item_social_voice_settings", title, value)

    private val social = listOf(
        branch(
            "item_social_account", "PlayerOne", "Online",
            branch(
                "item_social_friends", "Friends", "2 online",
                // Online first, then by name.
                leaf("item_social_account", "Alex", "Playing Crossbar Racing"),
                leaf("item_social_account", "Sam", "Online"),
                leaf("item_social_account", "Jordan", "Offline"),
            ),
            branch(
                "item_social_voice", "Voice", "Talk in a shared room",
                leaf("item_social_voice", "Create Lobby", "Start a private room and invite friends"),
                leaf("item_social_voice_invite", "Invites", "No pending invites"),
                branch(
                    "item_social_voice_settings", "Voice Settings", "Mic sensitivity, noise filter, volume",
                    voiceSetting("Mic Sensitivity", "Auto · lower filters clicks"),
                    voiceSetting("Noise Cancellation", "On · Krisp removes background + button clicks"),
                    voiceSetting("Echo Cancellation", "On · stops speaker feedback"),
                    voiceSetting("Auto Gain Control", "On · levels your mic volume"),
                    voiceSetting("Mic Volume", "100%"),
                    voiceSetting("Audio Balance", "Game ◀────●────▶ Voice"),
                    voiceSetting("Push-to-Talk", "Off"),
                ),
            ),
            branch(
                "item_social_activity", "Activity Settings", "Share what you're playing",
                leaf("item_social_activity", "Share Activity", "On · friends can see you're in Playfield Portal"),
                leaf("item_social_activity", "Generic Mode", "Off · shows the app name"),
            ),
            branch(
                "item_social_discord_settings", "Discord Settings", "Account & sign out",
                leaf("item_social_signout", "Sign Out", "Disconnect PlayerOne"),
            ),
            menu = RowKind.SOCIAL_ACCOUNT,
        ),
    )

    // ── Shiba Coins (accounts connected) ─────────────────────────────────────

    // All three open fullscreen views (player status, the tracked / untracked libraries).
    private const val TRACKED = 5

    private val shiba = listOf(
        Row(
            null, "Ruffian", "1,240 / 3,600 coins  •  $TRACKED tracked  •  1 mastered",
            leading = Leading.LEVEL, badge = "Lv 27", menu = RowKind.SHIBA,
        ),
        leaf("item_shiba_track", "All Tracked Games", "$TRACKED games", menu = RowKind.SHIBA),
        leaf("item_shiba_untracked", "Untracked", "${allGames.size - TRACKED} games", menu = RowKind.SHIBA),
    )

    // Same order as [categories].
    private val byCategory: List<List<Row>> =
        listOf(settings, photos, music, video, gameRows, network, appStore, social, shiba)

    /** The first-level rows of category [index]. */
    fun rootRows(index: Int): List<Row> = byCategory[index]

    /**
     * The row a category lands on (XmbLists.defaultRootIndex): Now Playing or All Games where marked,
     * else the media library card (Photos, Videos), else the top row.
     */
    fun landingRow(index: Int): Int {
        val rows = rootRows(index)
        rows.indexOfFirst { it.lands }.takeIf { it >= 0 }?.let { return it }
        return rows.indexOfFirst { it.menu == RowKind.MEDIA_CARD }.coerceAtLeast(0)
    }

    /** The default (Game) category's rows. */
    val rows: List<Row> get() = rootRows(SELECTED_CATEGORY)
}
