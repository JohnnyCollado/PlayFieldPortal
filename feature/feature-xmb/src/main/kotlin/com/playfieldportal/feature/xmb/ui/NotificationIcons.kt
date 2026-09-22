package com.playfieldportal.feature.xmb.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.PlayDisabled
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.playfieldportal.core.domain.model.NotificationKind
import com.playfieldportal.core.domain.model.NotificationSeverity
import com.playfieldportal.core.domain.model.TaskKind

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

/** A running task borrows the glyph of the history row it will become. */
internal fun taskGlyph(kind: TaskKind): ImageVector = notificationGlyph(kind.notificationKind)

/** The ring and tint that carry severity. Values from the plan's §5 table. */
internal fun severityColor(severity: NotificationSeverity): Color = when (severity) {
    NotificationSeverity.INFO -> Color(0xFF44586D)
    NotificationSeverity.SUCCESS -> Color(0xFF2E7D5B)
    NotificationSeverity.WARNING -> Color(0xFF9D6B1C)
    NotificationSeverity.ERROR -> Color(0xFFC0453A)
}
