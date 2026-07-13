package com.playfieldportal.feature.xmb.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.IconDisplayMode

/**
 * The ICON1 video snap of the currently focused game, non-null only while the XMBViewModel's
 * linger gate + battery/thermal gates all pass. [GameIcon] plays it in-slot for the matching
 * game; everything else ignores it.
 */
data class FocusedGameVideo(val gameId: Long, val uri: String)

// Provided by XMBShell alongside LocalXmbIconOverrides so the deeply nested tile composables
// (main list, drill flyout game column) never need the values plumbed through their params.
val LocalIconDisplayMode = compositionLocalOf { IconDisplayMode.DEFAULT }
val LocalFocusedGameVideo = compositionLocalOf<FocusedGameVideo?> { null }

// Live horizontal shift of the whole XMB cross (from the "Adjust XMB Layout" editor). Read by the
// category bar and item column so the cross moves left/right as one; 0.dp = the spec's anchor.
val LocalXmbHorizontalShift = compositionLocalOf { 0.dp }
