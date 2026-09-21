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
 * [viewportCentreY] and the values in [visibleRowCentres] are in the list's own coordinate space,
 * and both are **centres**: the middle of the viewport, and each row's own middle
 * (`LazyListItemInfo.offset + size / 2`). Comparing a row's centre against the viewport's centre
 * is what makes "nearest" mean the row the middle of the screen is inside; comparing a row's
 * *top* against it hands the win to the row below as soon as the centre falls in a row's lower
 * half. A row scrolled half off the top or bottom is still visible — Compose reports it with a
 * negative offset, and its centre is then near the viewport start — and can still be the nearest
 * one. Null when nothing is visible, which the callers read as "leave the cursor where it was"
 * rather than as "row 0".
 */
internal fun <T> nearestToViewportCentre(centreY: Float, visibleRowCentres: Map<T, Float>): T? =
    visibleRowCentres.minByOrNull { (_, y) -> abs(y - centreY) }?.key
