package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.PlayDisabled
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.ui.icons.CustomIconSurface
import com.playfieldportal.core.ui.icons.LocalXmbIcons

/**
 * Kind → glyph, as one table.
 *
 * These are Material stand-ins, deliberately. The nine hand-drawn glyphs the plan specifies (§5)
 * are new on-screen art and wait on the art gate; because the mapping is this one function,
 * swapping each stand-in for a real drawable later is a single-file change with no call-site churn.
 *
 * Severity is NOT part of this table: a scan that succeeded and a scan that failed share
 * [NotificationKind.SCAN] and differ only by [severityColor]. That is what keeps the set at nine
 * glyphs instead of thirty-six.
 */
internal fun notificationGlyph(kind: NotificationKind): ImageVector = when (kind) {
    NotificationKind.SCAN -> Icons.Outlined.Album
    NotificationKind.ARTWORK -> Icons.Outlined.Image
    // A tag, not a picture frame: scraping text has to read as different work from fetching art.
    NotificationKind.METADATA -> Icons.Outlined.Sell
    NotificationKind.ACHIEVEMENT -> Icons.Outlined.MonetizationOn
    NotificationKind.LAUNCH -> Icons.Outlined.PlayDisabled
    NotificationKind.SYSTEM -> Icons.Outlined.Settings
    NotificationKind.DOWNLOAD -> Icons.Outlined.Download
    NotificationKind.FEED -> Icons.Outlined.RssFeed
}

/** Themeable `notif_*` slot (theme-kit IconSlots key) for a kind; the inverse of DefaultSlotGlyph's table. */
internal fun notificationSlotKey(kind: NotificationKind): String = when (kind) {
    NotificationKind.SCAN -> "notif_album"
    NotificationKind.ARTWORK -> "notif_image"
    NotificationKind.METADATA -> "notif_tag"
    NotificationKind.ACHIEVEMENT -> "notif_coin"
    NotificationKind.LAUNCH -> "notif_blocked"
    NotificationKind.SYSTEM -> "notif_settings"
    NotificationKind.DOWNLOAD -> "notif_download"
    NotificationKind.FEED -> "notif_feed"
}

/**
 * A kind's glyph with the usual precedence: the user's pick, then the applied theme's art, then the
 * built-in [notificationGlyph]. Custom art renders as authored (untinted); only the built-in takes [tint].
 */
@Composable
internal fun NotificationKindIcon(kind: NotificationKind, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    val key = notificationSlotKey(kind)
    val override = LocalXmbIcons.current[key]
    if (override != null) {
        CustomIconSurface(icon = override, contentDescription = null, modifier = modifier.size(size))
    } else {
        Icon(imageVector = notificationGlyph(kind), contentDescription = null, tint = tint, modifier = modifier.size(size))
    }
}

/** The ring and tint that carry severity. Values from the plan's §5 table. */
internal fun severityColor(severity: NotificationSeverity): Color = when (severity) {
    NotificationSeverity.INFO -> Color(0xFF44586D)
    NotificationSeverity.SUCCESS -> Color(0xFF2E7D5B)
    NotificationSeverity.WARNING -> Color(0xFF9D6B1C)
    NotificationSeverity.ERROR -> Color(0xFFC0453A)
}

/** The kind as the Notes sheet's meta line names it ("Launch · 12 Minutes Ago"). */
internal fun notificationKindLabel(kind: NotificationKind): String = when (kind) {
    NotificationKind.SCAN -> "Scan"
    NotificationKind.ARTWORK -> "Artwork"
    NotificationKind.METADATA -> "Metadata"
    NotificationKind.ACHIEVEMENT -> "Achievements"
    NotificationKind.LAUNCH -> "Launch"
    NotificationKind.SYSTEM -> "System"
    NotificationKind.DOWNLOAD -> "Download"
    NotificationKind.FEED -> "Feed"
}

/** What the ✕ hint calls an action inside a sheet, or null when there is nowhere to go. */
internal fun notificationActionLabel(action: com.playfieldportal.core.domain.model.NotificationAction): String? =
    when (action) {
        is com.playfieldportal.core.domain.model.NotificationAction.OpenGame -> "Go to Game"
        is com.playfieldportal.core.domain.model.NotificationAction.OpenMemoryCard -> "Open Memory Card"
        is com.playfieldportal.core.domain.model.NotificationAction.OpenSettingsScreen -> "Open Settings"
        is com.playfieldportal.core.domain.model.NotificationAction.OpenCategory -> "Open Category"
        is com.playfieldportal.core.domain.model.NotificationAction.ReviewShortcut -> "Review"
        is com.playfieldportal.core.domain.model.NotificationAction.OpenUrl,
        com.playfieldportal.core.domain.model.NotificationAction.None -> null
    }
