package com.playfieldportal.feature.xmb.viewmodel

import kotlin.math.abs

/**
 * Which row the cursor should land on when it reappears after a touch drag: the visible row
 * nearest the viewport centre — the content the user was actually looking at.
 *
 * Shared by the fullscreen music browser and the Add-Tracks picker, because both re-anchor on the
 * same gesture and "nearest visible" must mean one thing in the two lists. Pure, so the rule is
 * pinned by a plain test rather than by a ViewModel or a Compose host.
 *
 * [viewportCentreY] and the offsets in [visibleOffsets] are in the list's own coordinate space —
 * a row's main-axis offset from the top of the viewport, which is what Compose reports as
 * `LazyListItemInfo.offset`. A row scrolled half off the top or bottom is still visible and can
 * still be the nearest one. Null when nothing is visible, which the callers read as "leave the
 * cursor where it was" rather than as "row 0".
 */
internal fun <T> nearestToViewportCentre(centreY: Float, visibleOffsets: Map<T, Float>): T? =
    visibleOffsets.minByOrNull { (_, y) -> abs(y - centreY) }?.key
