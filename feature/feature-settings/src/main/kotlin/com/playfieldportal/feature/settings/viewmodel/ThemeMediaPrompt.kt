package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.themekit.CustomizableIcons

/**
 * What to ask after a theme applies so that its media and icons actually show. A theme's clips,
 * sounds and icons sit in the theme tier, which the user's own assignments outrank, and its boot /
 * GameBoot clips need those presentations switched on. Confirming replaces [replace] and
 * [replaceIcons] (the user's assignments for slots the theme also supplies) and turns the
 * presentations on; declining changes nothing.
 */
data class ThemeMediaPrompt(
    /** The user's own media assignments the theme would replace, in slot order. */
    val replace: List<UiMediaSlot>,
    val turnOnGameBoot: Boolean,
    val turnOnBoot: Boolean,
    /** The user's own icons (Customize Icons keys) the theme would replace, in registry order. */
    val replaceIcons: List<String> = emptyList(),
) {
    val title: String
        get() = when {
            replaceIcons.isNotEmpty() && replace.isEmpty() && !turnOnGameBoot && !turnOnBoot -> "Use This Theme's Icons?"
            replaceIcons.isNotEmpty() -> "Use This Theme's Icons and Media?"
            else -> "Use This Theme's Media?"
        }

    private val replacesAnything: Boolean get() = replace.isNotEmpty() || replaceIcons.isNotEmpty()

    val confirmLabel: String get() = if (replacesAnything) "Use Theme's" else "Turn On"

    val cancelLabel: String get() = if (replacesAnything) "Keep Mine" else "Not Now"

    val message: String
        get() = buildList {
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
        }.joinToString("\n\n")

    companion object {
        /**
         * Null when the theme's media and icons already show as things stand. The sets are
         * ThemeTiers' answers once the theme is on disk: [themeMedia] what the theme tier supplies,
         * [shadowedMedia] / [shadowedIcons] what the user's own choices hide.
         */
        fun of(
            themeMedia: Set<UiMediaSlot>,
            shadowedMedia: Set<UiMediaSlot>,
            shadowedIcons: Set<String>,
            gameBootEnabled: Boolean,
            bootEnabled: Boolean,
        ): ThemeMediaPrompt? {
            val prompt = ThemeMediaPrompt(
                replace = UiMediaSlot.entries.filter { it in shadowedMedia },
                turnOnGameBoot = UiMediaSlot.GAMEBOOT_VIDEO in themeMedia && !gameBootEnabled,
                turnOnBoot = UiMediaSlot.BOOT_VIDEO in themeMedia && !bootEnabled,
                replaceIcons = CustomizableIcons.ALL.map { it.key }.filter { it in shadowedIcons },
            )
            return prompt.takeIf { it.replacesAnything || it.turnOnGameBoot || it.turnOnBoot }
        }
    }
}
