package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.model.displayLabel
import kotlin.math.floor

// Copy for Initial Setup's Controller, Hints & Touch and Home App pages and the Finish summary.

private const val NOT_SET = "Not set"

/** "Xbox · A confirms" — the pad style and which face button confirms on it. */
fun controllerSummary(prefs: ControllerLayoutPrefs): String =
    "${prefs.displayType.displayLabel()} · ${confirmButton(prefs)} confirms"

/** The A / B Swap row's sublabel: "Off — A confirms, B goes back". */
fun confirmSwapSublabel(prefs: ControllerLayoutPrefs): String {
    val reversed = prefs.confirmBackLayout == ConfirmBackLayout.REVERSED
    val (confirm, back) = if (reversed) "B" to "A" else "A" to "B"
    return "${if (reversed) "On" else "Off"} — $confirm confirms, $back goes back"
}

private fun confirmButton(prefs: ControllerLayoutPrefs): String {
    val reversed = prefs.confirmBackLayout == ConfirmBackLayout.REVERSED
    return when (prefs.displayType) {
        ControllerDisplayType.PLAYSTATION -> if (reversed) "Circle" else "Cross"
        ControllerDisplayType.XBOX,
        ControllerDisplayType.NINTENDO    -> if (reversed) "B" else "A"
    }
}

/** "1 second", "2 seconds", "2.5 seconds". */
fun hintDelayLabel(seconds: Float): String {
    val whole = seconds == floor(seconds)
    val number = if (whole) seconds.toInt().toString() else seconds.toString()
    return if (whole && seconds.toInt() == 1) "1 second" else "$number seconds"
}

/** The next whole second after [current], wrapping from 5 back to 1 (Display's slider allows halves). */
fun nextHintDelay(current: Float): Float {
    val next = floor(current) + 1f
    return if (next > MAX_HINT_DELAY_SECONDS) MIN_HINT_DELAY_SECONDS else next
}

fun touchButtonLabel(mode: TouchNavButtonMode): String = when (mode) {
    TouchNavButtonMode.AUTO        -> "Auto"
    TouchNavButtonMode.ALWAYS_SHOW -> "Always Show"
    TouchNavButtonMode.ALWAYS_HIDE -> "Always Hide"
}

/** "On · 2 seconds", or "Off". */
fun hintsSummary(hints: InterfaceHints): String =
    if (hints.enabled) "On · ${hintDelayLabel(hints.delaySeconds)}" else "Off"

fun homeAppSummary(isHome: Boolean): String =
    if (isHome) "Play Field Portal" else "Not Play Field Portal"

/** A linked folder's name, or "Not set". */
fun folderSummary(name: String?): String = name ?: NOT_SET
