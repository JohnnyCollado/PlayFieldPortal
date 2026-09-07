package com.playfieldportal.feature.xmb.ui

import androidx.annotation.DrawableRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.playfieldportal.core.ui.icons.ConsoleIcon
import com.playfieldportal.core.ui.icons.PortalIcon
import com.playfieldportal.core.ui.icons.catbarIconKeyFor
import com.playfieldportal.core.ui.icons.categoryIconFor
import com.playfieldportal.core.ui.icons.systemIconRes
import com.playfieldportal.themekit.IconSlot

/**
 * What a themeable slot draws when neither a user pick nor the applied theme replaces it —
 * the built-in glyph, named in one place so an editor can preview it away from the render
 * site that owns it.
 *
 * The launcher's defaults are deliberately heterogeneous (drawables for the crossbar and
 * status strip, a bundled asset for the memory card, Material vectors for the item rows), so
 * this is a description of WHICH art, not a painter: resolving it is pure and therefore
 * testable, and only [DefaultSlotGlyph] needs a composition.
 *
 * Mirrors the Theme Studio's `StudioIconSet` — same slot, same glyph on both sides.
 */
internal sealed interface SlotGlyphDefault {
    /** Console art, resolved through [ConsoleIcon]'s own `sysicon_*` override lookup. */
    data class Console(val platformId: String) : SlotGlyphDefault

    /** A bundled drawable (crossbar art, the status strip, the settings wrench). */
    data class Drawable(@DrawableRes val resId: Int) : SlotGlyphDefault

    /** A `file:///android_asset/...` silhouette — the physical-media memory card. */
    data class BundledAsset(val assetUri: String) : SlotGlyphDefault

    /** A Material vector, as the item rows draw it. */
    data class Vector(val image: ImageVector) : SlotGlyphDefault

    /** No built-in art for this key. Unreachable for a registered slot — see the test. */
    data object None : SlotGlyphDefault
}

/**
 * The built-in glyph behind [slotKey], or [SlotGlyphDefault.None] for an unregistered key.
 *
 * Every key in `CustomizableIcons.ALL` resolves to real art; `DefaultSlotGlyphTest` is the
 * guard, so a slot added without a default fails the build instead of silently degrading to
 * a placeholder letter in the customizer.
 */
internal fun defaultGlyphFor(slot: IconSlot): SlotGlyphDefault {
    if (slot.group == IconSlot.Group.CONSOLE) {
        return SlotGlyphDefault.Console(slot.key.removePrefix("sysicon_"))
    }
    // Crossbar art, via the catalog — catbarSlotKeyFor's inverse keeps the pairing single-sourced.
    catbarIconKeyFor(slot.key)?.let { iconKey ->
        return SlotGlyphDefault.Drawable(categoryIconFor(iconKey).resId)
    }
    // Status strip; the resource IDs themselves stay private to XmbStatusStrip.kt.
    XmbStatusIcons.forSlotKey(slot.key)?.let { return SlotGlyphDefault.Drawable(it) }
    return when (slot.key) {
        // The default memory-card art. "All Tracked Games" reads as a card too (its row draws
        // MEMORY_CARD_DEFAULT_ART directly), so it previews as one.
        "item_memcard_games", "item_memcard_music", "item_memcard_video", "item_memcard_photos",
        "item_shiba_track",
        -> SlotGlyphDefault.BundledAsset(MEMORY_CARD_DEFAULT_ART)
        // The Settings rows' wrench badge is console art, not a Material glyph.
        "item_settings" -> SlotGlyphDefault.Drawable(systemIconRes("settings"))
        else -> ITEM_VECTORS[slot.key]?.let { SlotGlyphDefault.Vector(it) } ?: SlotGlyphDefault.None
    }
}

/**
 * Material glyphs for the item slots — keep in lockstep with the leading icons in
 * [XmbItemLeadingIcon] (same vector per slot) and with the Studio's `StudioIconSet`.
 */
private val ITEM_VECTORS: Map<String, ImageVector> = mapOf(
    "item_add" to Icons.Filled.Add,
    "item_missing" to Icons.Filled.HelpOutline,
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
    "item_playlist" to Icons.Filled.QueueMusic,
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
    "item_shiba_untracked" to Icons.Filled.HelpOutline,
)

/**
 * Draws [slot]'s built-in glyph — what the XMB shows when nothing overrides the slot.
 *
 * Every branch goes through [PortalIcon] (or [ConsoleIcon], which does the same internally),
 * so a preview carries the theme's unified icon tint and the icon-legibility matte exactly as
 * the real render site does. Returns false without drawing when the key has no built-in,
 * leaving the caller to decide what a slot with no art should look like.
 */
@Composable
internal fun DefaultSlotGlyph(
    slot: IconSlot,
    contentDescription: String?,
    modifier: Modifier = Modifier,
): Boolean {
    when (val default = defaultGlyphFor(slot)) {
        is SlotGlyphDefault.Console -> ConsoleIcon(
            platformId = default.platformId,
            contentDescription = contentDescription,
            modifier = modifier,
        )
        is SlotGlyphDefault.Drawable -> PortalIcon(
            painter = painterResource(default.resId),
            contentDescription = contentDescription,
            modifier = modifier,
        )
        is SlotGlyphDefault.BundledAsset -> BundledSilhouetteIcon(
            assetUri = default.assetUri,
            modifier = modifier,
        )
        is SlotGlyphDefault.Vector -> PortalIcon(
            painter = rememberVectorPainter(default.image),
            contentDescription = contentDescription,
            modifier = modifier,
        )
        SlotGlyphDefault.None -> return false
    }
    return true
}
