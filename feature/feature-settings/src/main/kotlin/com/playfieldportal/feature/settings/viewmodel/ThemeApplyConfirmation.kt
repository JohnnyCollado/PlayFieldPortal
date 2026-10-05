package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.themekit.CustomizableIcons

/**
 * The question asked before a saved theme is applied. Nothing changes until it is answered.
 *
 * It also carries what applying would replace and switch on, so one answer covers everything: a
 * theme's clips, sounds and icons sit in the theme tier, which the user's own assignments outrank,
 * and its Boot / GameBoot clips only play while those presentations are on. When there is such a
 * choice, [options] lists it (the first is preselected):
 *  - option [USE_THEMES]: clear [replace] / [replaceIcons] and turn on what the theme needs;
 *  - option [APPLY_ONLY]: apply and change nothing else.
 * [applyLabel] applies with the chosen option; [cancelLabel] leaves the theme unapplied.
 */
data class ThemeApplyConfirmation(
    val themeId: String,
    val themeName: String,
    /** The user's own media assignments the theme would replace, in slot order. */
    val replace: List<UiMediaSlot>,
    val turnOnGameBoot: Boolean,
    val turnOnBoot: Boolean,
    /** The user's own icons (Customize Icons keys) the theme would replace, in registry order. */
    val replaceIcons: List<String> = emptyList(),
    /**
     * The theme carries a lock screen image. It is offered on its own after applying (it changes
     * the device, not the launcher), never set by Apply.
     */
    val offersLockScreen: Boolean = false,
) {
    val title: String get() = "Apply \"$themeName\"?"

    private val replacesAnything: Boolean get() = replace.isNotEmpty() || replaceIcons.isNotEmpty()

    /** True when applying has more than one way to go (keep yours, or leave a presentation off). */
    val hasChoices: Boolean get() = replacesAnything || turnOnGameBoot || turnOnBoot

    /** The choice to make, in [USE_THEMES] / [APPLY_ONLY] order; empty when there is none. */
    val options: List<String>
        get() = when {
            replacesAnything -> listOf("Use the Theme's", "Keep Mine")
            hasChoices -> listOf("Turn On", "Leave Off")
            else -> emptyList()
        }

    val applyLabel: String get() = "Apply"

    val cancelLabel: String get() = "Cancel"

    val message: String
        get() = buildList {
            add(BASE_MESSAGE)
            if (replaceIcons.isNotEmpty()) {
                add(
                    "This theme has its own icons for " +
                        replaceIcons.joinToString(", ") { CustomizableIcons.byKey(it)?.displayName ?: it } +
                        ", but yours are set and show instead. Use the theme's? Yours are removed for these.",
                )
            }
            if (replace.isNotEmpty()) {
                add(
                    "This theme has its own " + replace.joinToString(", ") { UiMediaRowText.slotName(it) } +
                        ", but yours is set and plays instead. Use the theme's? Yours are removed for these.",
                )
            }
            if (turnOnBoot) add("Boot Sequence is off — turn it on to play the theme's Boot Video.")
            if (turnOnGameBoot) add("GameBoot is off — turn it on to play the theme's GameBoot Video.")
            if (offersLockScreen) add("It also has a lock screen image — you'll be asked about it after applying.")
        }.joinToString("\n\n")

    companion object {
        /** [options] index: apply, replacing and switching on what the theme needs. */
        const val USE_THEMES = 0

        /** [options] index: apply and change nothing else. */
        const val APPLY_ONLY = 1

        const val BASE_MESSAGE = "This changes the launcher's colors, icons and background."

        /**
         * The confirmation for theme [themeId], from what the theme carries ([themeMedia],
         * [themeIcons], read from the saved bundle before anything is applied) and what the user
         * has set themselves ([userMedia], [userIcons]).
         */
        fun of(
            themeId: String,
            themeName: String,
            themeMedia: Set<UiMediaSlot>,
            userMedia: Set<UiMediaSlot>,
            themeIcons: Set<String>,
            userIcons: Set<String>,
            gameBootEnabled: Boolean,
            bootEnabled: Boolean,
            themeHasLockScreen: Boolean = false,
        ): ThemeApplyConfirmation = ThemeApplyConfirmation(
            themeId = themeId,
            themeName = themeName,
            replace = UiMediaSlot.entries.filter { it in themeMedia && it in userMedia },
            turnOnGameBoot = UiMediaSlot.GAMEBOOT_VIDEO in themeMedia && !gameBootEnabled,
            turnOnBoot = UiMediaSlot.BOOT_VIDEO in themeMedia && !bootEnabled,
            replaceIcons = CustomizableIcons.ALL.map { it.key }.filter { it in themeIcons && it in userIcons },
            offersLockScreen = themeHasLockScreen,
        )
    }
}
