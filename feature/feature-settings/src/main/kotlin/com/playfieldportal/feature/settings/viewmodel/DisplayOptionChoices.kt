package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.IconLegibilityStyle
import com.playfieldportal.core.domain.model.TextLegibilityStyle
import com.playfieldportal.core.domain.model.UmdSlotMode
import com.playfieldportal.core.domain.model.XmbListMotion

// ── Display's pick-one settings ───────────────────────────────────────────────
//
// These rows used to step to the next value on every press and list all their values in the
// sublabel, separated by pipes, because that legend was the only place the other values could be
// seen. Each now opens a choice dialog instead: the row says what the setting is for, and the
// dialog lists the values, in the order the setting's enum declares them — [choices] index N is
// that enum's entry N, which is what DisplaySettingsViewModel.choose relies on.

/** One value of a pick-one setting: its name and, where the name alone is not enough, a few words. */
data class DisplayChoice(val label: String, val detail: String? = null)

/**
 * A Display setting chosen from a short list. [summary] is the row's sublabel and the dialog's
 * message: what the setting is for, never a list of its values.
 */
enum class DisplayOption(val title: String, val summary: String, val choices: List<DisplayChoice>) {
    WAVE_STYLE(
        title = "Wave style",
        summary = "How lively the background wave is.",
        choices = listOf(
            DisplayChoice("Animated", "Full motion"),
            DisplayChoice("Reduced", "Dimmer, calmer"),
            DisplayChoice("Static", "Frozen"),
            DisplayChoice("Reduced + Static"),
        ),
    ),

    /** The same setting as [WAVE_STYLE], named for a motion wallpaper, which it also governs. */
    BACKGROUND_MOTION(
        title = "Background motion",
        summary = "How lively the motion wallpaper is.",
        choices = listOf(
            DisplayChoice("Animated", "Full motion"),
            DisplayChoice("Reduced", "Slower, calmer"),
            DisplayChoice("Static", "Still image"),
            DisplayChoice("Reduced + Static"),
        ),
    ),
    ICON_LEGIBILITY(
        title = "Icon legibility",
        summary = "How XMB icons separate from the background.",
        choices = IconLegibilityStyle.entries.map { style ->
            DisplayChoice(
                label = style.label,
                detail = when (style) {
                    IconLegibilityStyle.OFFSET_SHADOW_LIGHT -> "For dark icons"
                    IconLegibilityStyle.CONTOUR_AUTO -> "Follows the icon color"
                    else -> null
                },
            )
        },
    ),
    ITEM_LIST_MOTION(
        title = "Item list motion",
        summary = "How the item list moves as you step through it. Rewind slips the focused item behind " +
            "the category icon and plays in reverse going up; Glide moves the whole list together.",
        choices = XmbListMotion.entries.map { DisplayChoice(it.label) },
    ),
    UMD_SLOT(
        title = "UMD slot",
        summary = "Which game sits in the UMD slot.",
        choices = UmdSlotMode.entries.map { mode ->
            DisplayChoice(
                label = mode.label,
                detail = when (mode) {
                    UmdSlotMode.OFF -> null
                    UmdSlotMode.INSERTED -> "Only a game you inserted"
                    UmdSlotMode.INSERTED_AND_RECENT -> "Else the last game played"
                },
            )
        },
    ),

    // Honest copy: the choice is stored and travels with themes and backups, but no renderer reads
    // it yet (survey U2, docs/audits/codebase-survey-2026-10-22.md).
    TEXT_LEGIBILITY(
        title = "Text legibility",
        summary = "Not applied yet. Saved with your themes and backups for a later update.",
        choices = TextLegibilityStyle.entries.map { DisplayChoice(it.label) },
    ),
    SCREEN_ORIENTATION(
        title = "Screen orientation",
        summary = "Whether PFP turns with the device. Follow Device shows a rotate prompt in portrait " +
            "(for Tasker / launcher switching).",
        choices = listOf(
            DisplayChoice("Landscape", "PFP stays landscape"),
            DisplayChoice("Follow Device", "Rotates with the device"),
        ),
    ),
    TOUCH_NAV_BUTTON(
        title = "Touch navigation button",
        summary = "The on-screen App Drawer / Back button.",
        choices = listOf(
            DisplayChoice("Auto", "Only while using touch"),
            DisplayChoice("Always Show"),
            DisplayChoice("Always Hide", "Controller-only"),
        ),
    ),
    TOUCH_SENSITIVITY(
        title = "Touch sensitivity",
        summary = "How far a swipe travels per XMB step.",
        choices = listOf(
            DisplayChoice("Low", "Steadier"),
            DisplayChoice("Normal"),
            DisplayChoice("High", "Faster scrubbing"),
        ),
    ),
}
