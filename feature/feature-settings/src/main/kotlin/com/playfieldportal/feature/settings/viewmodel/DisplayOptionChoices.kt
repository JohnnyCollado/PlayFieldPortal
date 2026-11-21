package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.IconLegibilityStyle
import com.playfieldportal.core.domain.model.TextLegibilityStyle
import com.playfieldportal.core.domain.model.UmdSlotMode
import com.playfieldportal.core.domain.model.XmbListMotion
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.domain.text.labelRes
import com.playfieldportal.feature.settings.R

// ── Display's pick-one settings ───────────────────────────────────────────────
//
// These rows used to step to the next value on every press and list all their values in the
// sublabel, separated by pipes, because that legend was the only place the other values could be
// seen. Each now opens a choice dialog instead: the row says what the setting is for, and the
// dialog lists the values, in the order the setting's enum declares them — [choices] index N is
// that enum's entry N, which is what DisplaySettingsViewModel.choose relies on.

/** One value of a pick-one setting: its name and, where the name alone is not enough, a few words. */
data class DisplayChoice(val label: UiText, val detail: UiText? = null)

/**
 * A Display setting chosen from a short list. [summary] is the row's sublabel and the dialog's
 * message: what the setting is for, never a list of its values.
 */
enum class DisplayOption(val title: UiText, val summary: UiText, val choices: List<DisplayChoice>) {
    /** How much moves on its own: everything, only what is selected, or nothing. Above every other motion choice. */
    MOTION(
        title = UiText.res(R.string.motion_title),
        summary = UiText.res(R.string.motion_summary),
        choices = com.playfieldportal.core.domain.model.ImageMotion.entries.map { motion ->
            DisplayChoice(
                label = UiText.res(motion.labelRes),
                detail = UiText.res(
                    when (motion) {
                        com.playfieldportal.core.domain.model.ImageMotion.ANIMATED -> R.string.motion_detail_animated
                        com.playfieldportal.core.domain.model.ImageMotion.REDUCED -> R.string.motion_detail_reduced
                        com.playfieldportal.core.domain.model.ImageMotion.STATIC -> R.string.motion_detail_static
                    },
                ),
            )
        },
    ),
    WAVE_STYLE(
        title = UiText.res(R.string.setd_opt_wave_style_title),
        summary = UiText.res(R.string.setd_opt_wave_style_summary),
        choices = listOf(
            DisplayChoice(UiText.res(R.string.setd_motion_animated), UiText.res(R.string.setd_motion_animated_detail)),
            DisplayChoice(UiText.res(R.string.setd_motion_reduced), UiText.res(R.string.setd_wave_reduced_detail)),
            DisplayChoice(UiText.res(R.string.setd_motion_static), UiText.res(R.string.setd_wave_static_detail)),
            DisplayChoice(UiText.res(R.string.setd_motion_reduced_static)),
        ),
    ),

    /** The same setting as [WAVE_STYLE], named for a motion wallpaper, which it also governs. */
    BACKGROUND_MOTION(
        title = UiText.res(R.string.setd_opt_background_motion_title),
        summary = UiText.res(R.string.setd_opt_background_motion_summary),
        choices = listOf(
            DisplayChoice(UiText.res(R.string.setd_motion_animated), UiText.res(R.string.setd_motion_animated_detail)),
            DisplayChoice(UiText.res(R.string.setd_motion_reduced), UiText.res(R.string.setd_bgmotion_reduced_detail)),
            DisplayChoice(UiText.res(R.string.setd_motion_static), UiText.res(R.string.setd_bgmotion_static_detail)),
            DisplayChoice(UiText.res(R.string.setd_motion_reduced_static)),
        ),
    ),
    ICON_LEGIBILITY(
        title = UiText.res(R.string.setd_opt_icon_legibility_title),
        summary = UiText.res(R.string.setd_opt_icon_legibility_summary),
        choices = IconLegibilityStyle.entries.map { style ->
            DisplayChoice(
                label = UiText.res(style.labelRes),
                detail = when (style) {
                    IconLegibilityStyle.OFFSET_SHADOW_LIGHT -> UiText.res(R.string.setd_icon_legibility_light_detail)
                    IconLegibilityStyle.CONTOUR_AUTO -> UiText.res(R.string.setd_icon_legibility_auto_detail)
                    else -> null
                },
            )
        },
    ),
    ITEM_LIST_MOTION(
        title = UiText.res(R.string.setd_opt_item_list_motion_title),
        summary = UiText.res(R.string.setd_opt_item_list_motion_summary),
        choices = XmbListMotion.entries.map { DisplayChoice(UiText.res(it.labelRes)) },
    ),
    UMD_SLOT(
        title = UiText.res(R.string.setd_opt_umd_slot_title),
        summary = UiText.res(R.string.setd_opt_umd_slot_summary),
        choices = UmdSlotMode.entries.map { mode ->
            DisplayChoice(
                label = UiText.res(mode.labelRes),
                detail = when (mode) {
                    UmdSlotMode.OFF -> null
                    UmdSlotMode.INSERTED -> UiText.res(R.string.setd_umd_inserted_detail)
                    UmdSlotMode.INSERTED_AND_RECENT -> UiText.res(R.string.setd_umd_recent_detail)
                },
            )
        },
    ),

    // Honest copy: the choice is stored and travels with themes and backups, but no renderer reads
    // it yet (survey U2, docs/audits/codebase-survey-2026-10-22.md).
    TEXT_LEGIBILITY(
        title = UiText.res(R.string.setd_opt_text_legibility_title),
        summary = UiText.res(R.string.setd_opt_text_legibility_summary),
        choices = TextLegibilityStyle.entries.map { DisplayChoice(UiText.res(it.labelRes)) },
    ),
    SCREEN_ORIENTATION(
        title = UiText.res(R.string.setd_opt_orientation_title),
        summary = UiText.res(R.string.setd_opt_orientation_summary),
        choices = listOf(
            DisplayChoice(UiText.res(R.string.setd_orientation_landscape), UiText.res(R.string.setd_orientation_landscape_detail)),
            DisplayChoice(UiText.res(R.string.setd_orientation_follow), UiText.res(R.string.setd_orientation_follow_detail)),
        ),
    ),
    TOUCH_NAV_BUTTON(
        title = UiText.res(R.string.setd_opt_touch_nav_title),
        summary = UiText.res(R.string.setd_opt_touch_nav_summary),
        choices = listOf(
            DisplayChoice(UiText.res(R.string.setd_touch_nav_auto), UiText.res(R.string.setd_touch_nav_auto_detail)),
            DisplayChoice(UiText.res(R.string.setd_touch_nav_show)),
            DisplayChoice(UiText.res(R.string.setd_touch_nav_hide), UiText.res(R.string.setd_touch_nav_hide_detail)),
        ),
    ),
    TOUCH_SENSITIVITY(
        title = UiText.res(R.string.setd_opt_touch_sens_title),
        summary = UiText.res(R.string.setd_opt_touch_sens_summary),
        choices = listOf(
            DisplayChoice(UiText.res(R.string.setd_touch_sens_low), UiText.res(R.string.setd_touch_sens_low_detail)),
            DisplayChoice(UiText.res(R.string.setd_touch_sens_normal)),
            DisplayChoice(UiText.res(R.string.setd_touch_sens_high), UiText.res(R.string.setd_touch_sens_high_detail)),
        ),
    ),
}
