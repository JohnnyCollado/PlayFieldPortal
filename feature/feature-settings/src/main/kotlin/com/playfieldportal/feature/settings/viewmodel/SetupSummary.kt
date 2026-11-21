package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.text.labelRes
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.feature.settings.R
import kotlin.math.floor

// Copy for Initial Setup's Controller, Hints & Touch and Home App pages and the Finish summary.

/** "Xbox · A confirms" — the pad style and which face button confirms on it. */
fun controllerSummary(prefs: ControllerLayoutPrefs): UiText =
    UiText.res(R.string.setup_controller_summary, UiText.res(prefs.displayType.labelRes), confirmButton(prefs))

/** The A / B Swap row's sublabel: "Off — A confirms, B goes back". */
fun confirmSwapSublabel(prefs: ControllerLayoutPrefs): UiText {
    val reversed = prefs.confirmBackLayout == ConfirmBackLayout.REVERSED
    val (confirm, back) = if (reversed) "B" to "A" else "A" to "B"
    return UiText.res(
        if (reversed) R.string.setup_confirm_swap_on else R.string.setup_confirm_swap_off,
        confirm, back,
    )
}

private fun confirmButton(prefs: ControllerLayoutPrefs): UiText {
    val reversed = prefs.confirmBackLayout == ConfirmBackLayout.REVERSED
    return when (prefs.displayType) {
        ControllerDisplayType.PLAYSTATION ->
            UiText.res(if (reversed) R.string.setup_button_circle else R.string.setup_button_cross)
        ControllerDisplayType.XBOX,
        ControllerDisplayType.NINTENDO    -> UiText.raw(if (reversed) "B" else "A")
    }
}

/** "1 second", "2 seconds", "2.5 seconds". */
fun hintDelayLabel(seconds: Float): UiText =
    if (seconds == floor(seconds)) UiText.plural(R.plurals.setup_hints_delay_seconds, seconds.toInt())
    else UiText.res(R.string.setup_hints_delay_fraction, seconds.toString())

/** The next whole second after [current], wrapping from 5 back to 1 (Display's slider allows halves). */
fun nextHintDelay(current: Float): Float {
    val next = floor(current) + 1f
    return if (next > MAX_HINT_DELAY_SECONDS) MIN_HINT_DELAY_SECONDS else next
}

fun touchButtonLabel(mode: TouchNavButtonMode): UiText = UiText.res(
    when (mode) {
        TouchNavButtonMode.AUTO        -> R.string.setup_touch_auto
        TouchNavButtonMode.ALWAYS_SHOW -> R.string.setup_touch_always_show
        TouchNavButtonMode.ALWAYS_HIDE -> R.string.setup_touch_always_hide
    },
)

/** "On · 2 seconds", or "Off". */
fun hintsSummary(hints: InterfaceHints): UiText =
    if (hints.enabled) UiText.res(R.string.setup_hints_summary_on, hintDelayLabel(hints.delaySeconds))
    else UiText.res(R.string.setup_off)

fun homeAppSummary(isHome: Boolean): UiText =
    UiText.res(if (isHome) R.string.setup_home_is_pfp else R.string.setup_home_not_pfp)

/** A linked folder's name, or "Not set". */
fun folderSummary(name: String?): UiText =
    if (name != null) UiText.raw(name) else UiText.res(R.string.setup_not_set)
