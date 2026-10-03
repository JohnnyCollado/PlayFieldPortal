package com.playfieldportal.studio.preview

import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.PlayDisabled
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter

/**
 * The Studio's copy of the launcher's default icon set, keyed by
 * [com.playfieldportal.themekit.IconSlots] keys. Category-bar and status glyphs are the
 * same assets the launcher ships (copied under `resources/xmb/`); item glyphs are the same
 * Material vectors the launcher's item rows reference inline.
 */
object StudioIconSet {

    /** Classpath resources for the raster/XML-vector slots. StudioIconSetTest covers keys. */
    internal val RESOURCE_SLOTS: Map<String, String> = mapOf(
        "catbar_games" to "xmb/catbar_games.png",
        "catbar_music" to "xmb/catbar_music.png",
        "catbar_video" to "xmb/catbar_video.png",
        "catbar_photos" to "xmb/catbar_photos.png",
        "catbar_settings" to "xmb/catbar_settings.png",
        "catbar_network" to "xmb/catbar_network.png",
        "catbar_appstore" to "xmb/catbar_appstore.png",
        "catbar_social" to "xmb/catbar_social.xml",
        "catbar_favorites" to "xmb/catbar_favorites.png",
        "catbar_achievements" to "xmb/catbar_achievements.png",   // the Shiba Coin
        // "All Tracked Games" reads as a memory card in the XMB (MEMORY_CARD_DEFAULT_ART).
        "item_shiba_track" to "xmb/item_memcard.png",
        // Default memory-card art (launcher: systems/physical-media/_default.png) — one
        // asset, four semantic slots so themes can diverge per category.
        "item_memcard_games" to "xmb/item_memcard.png",
        "item_memcard_music" to "xmb/item_memcard.png",
        "item_memcard_video" to "xmb/item_memcard.png",
        "item_memcard_photos" to "xmb/item_memcard.png",
        // Settings rows' wrench badge (launcher: sysicon_settings).
        "item_settings" to "xmb/item_settings.png",
        "status_battery_full" to "xmb/ic_status_battery_full.xml",
        "status_battery_high" to "xmb/ic_status_battery_high.xml",
        "status_battery_medium" to "xmb/ic_status_battery_medium.xml",
        "status_battery_low" to "xmb/ic_status_battery_low.xml",
        "status_battery_charging" to "xmb/ic_status_battery_charging.xml",
        "status_bluetooth" to "xmb/ic_status_bluetooth.xml",
        // Tier medallions (launcher: drawable/shiba_coin_*.webp).
        "shiba_coin_bronze" to "xmb/shiba_coin_bronze.webp",
        "shiba_coin_silver" to "xmb/shiba_coin_silver.webp",
        "shiba_coin_gold" to "xmb/shiba_coin_gold.webp",
        "shiba_coin_platinum" to "xmb/shiba_coin_platinum.webp",
    )

    /**
     * Material glyphs for the item slots — keep in lockstep with the launcher's
     * XMBItemList leading icons (same vector per slot).
     */
    val ITEM_VECTORS: Map<String, ImageVector> = mapOf(
        "item_add" to Icons.Filled.Add,
        "item_missing" to Icons.AutoMirrored.Filled.HelpOutline,
        "item_video_folder" to Icons.Filled.Folder,
        "item_video_library" to Icons.Filled.VideoLibrary,
        "item_video_recent" to Icons.Filled.History,
        "item_video_favorites" to Icons.Filled.Star,
        "item_video_collections" to Icons.Filled.Bookmarks,
        "item_video_apps" to Icons.Filled.Movie,
        "item_video_file" to Icons.Filled.Movie,
        "item_photo_folder" to Icons.Filled.Folder,
        "item_photo_file" to Icons.Filled.Photo,
        "item_photo_albums" to Icons.Filled.PhotoLibrary,
        "item_photo_apps" to Icons.Filled.Collections,
        "item_camera" to Icons.Filled.PhotoCamera,
        "item_music_track" to Icons.Filled.MusicNote,
        "item_playlist" to Icons.AutoMirrored.Filled.QueueMusic,
        "item_music_apps" to Icons.Filled.LibraryMusic,
        "item_social_add" to Icons.Filled.QrCode2,
        "item_social_account" to Icons.Filled.AccountCircle,
        "item_social_friends" to Icons.Filled.People,
        "item_social_voice" to Icons.Filled.Headset,
        "item_social_voice_invite" to Icons.Filled.PersonAdd,
        "item_social_voice_mute" to Icons.Filled.Mic,
        "item_social_voice_settings" to Icons.Filled.Tune,
        "item_social_voice_leave" to Icons.Filled.CallEnd,
        "item_social_activity" to Icons.Filled.SportsEsports,
        "item_social_discord_settings" to Icons.Filled.Settings,
        "item_social_signout" to Icons.AutoMirrored.Filled.Logout,
        // Shiba Coins (achievements) hub rows.
        "item_shiba_connect" to Icons.Filled.Link,
        "item_shiba_untracked" to Icons.AutoMirrored.Filled.HelpOutline,
        // Status strip slots drawn as vectors / meters on the launcher.
        "status_notifications" to Icons.Filled.Notifications,
        "status_controller" to Icons.Filled.SportsEsports,
        "status_wifi" to Icons.Filled.Wifi,
        "status_signal" to Icons.Filled.SignalCellularAlt,
        // Media transports.
        "media_play" to Icons.Filled.PlayArrow,
        "media_pause" to Icons.Filled.Pause,
        "media_prev" to Icons.Filled.SkipPrevious,
        "media_next" to Icons.Filled.SkipNext,
        "media_back10" to Icons.Filled.Replay10,
        "media_fwd10" to Icons.Filled.Forward10,
        // Game Detail action row.
        "detail_play" to Icons.Filled.PlayArrow,
        "detail_favorite" to Icons.Filled.Favorite,
        "detail_artwork" to Icons.Filled.Brush,
        "detail_manual" to Icons.AutoMirrored.Filled.MenuBook,
        "detail_more" to Icons.Filled.MoreHoriz,
        // Notification kinds (launcher: NotificationIcons.notificationGlyph, Outlined set).
        "notif_album" to Icons.Outlined.Album,
        "notif_image" to Icons.Outlined.Image,
        "notif_tag" to Icons.Outlined.Sell,
        "notif_coin" to Icons.Outlined.MonetizationOn,
        "notif_blocked" to Icons.Outlined.PlayDisabled,
        "notif_settings" to Icons.Outlined.Settings,
        "notif_download" to Icons.Outlined.Download,
        "notif_feed" to Icons.Outlined.RssFeed,
        // Menus.
        "menu_check" to Icons.Filled.Check,
        "menu_back" to Icons.AutoMirrored.Filled.ArrowBack,
    )

    private const val CONSOLE_PREFIX = "sysicon_"

    /**
     * The launcher's console art (core-ui drawable-nodpi/sysicon_*.png) for a `sysicon_<id>` key, with
     * SystemIcons.kt's fallbacks: xbox borrows the Xbox 360 icon, and ids with no dedicated art
     * (cps1/2/3, default) use the generic one. Null for a non-console key.
     */
    internal fun consoleResource(key: String): String? {
        if (!key.startsWith(CONSOLE_PREFIX)) return null
        val id = when (val raw = key.removePrefix(CONSOLE_PREFIX)) {
            "xbox" -> "x360"
            "cps1", "cps2", "cps3" -> "default"
            else -> raw
        }
        return "xmb/sysicon_$id.png"
    }

    /** Art the launcher draws as authored (untinted): console art, medallions, memory cards. */
    fun isFullColour(key: String): Boolean =
        key.startsWith(CONSOLE_PREFIX) || key.startsWith("shiba_coin_") ||
            key == "item_shiba_track" || key.startsWith("item_memcard_")

    /** Default painter for a slot key; an unknown key gets a play arrow. */
    @Composable
    fun defaultPainter(key: String): Painter {
        RESOURCE_SLOTS[key]?.let { return resourcePainter(it) }
        consoleResource(key)?.let { return resourcePainter(it) }
        return rememberVectorPainter(ITEM_VECTORS[key] ?: Icons.Filled.PlayArrow)
    }

    // Classpath art for the bundled slots: PNG/WebP art decodes through Skia (as ImageCodecs does) and the
    // Android vector drawables through the Compose resources decoder.
    @OptIn(ExperimentalResourceApi::class)
    @Composable
    private fun resourcePainter(path: String): Painter {
        val density = LocalDensity.current
        if (path.endsWith(".xml")) {
            val vector = remember(path, density) {
                readResource(path).decodeToImageVector(density)
            }
            return rememberVectorPainter(vector)
        }
        val bitmap = remember(path) {
            org.jetbrains.skia.Image.makeFromEncoded(readResource(path)).toComposeImageBitmap()
        }
        return remember(bitmap) { BitmapPainter(bitmap) }
    }

    private fun readResource(path: String): ByteArray =
        checkNotNull(StudioIconSet::class.java.classLoader.getResourceAsStream(path)) { "Missing resource $path" }
            .use { it.readBytes() }
}
