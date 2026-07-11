package com.playfieldportal.feature.xmb.ui

import androidx.compose.runtime.compositionLocalOf
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
