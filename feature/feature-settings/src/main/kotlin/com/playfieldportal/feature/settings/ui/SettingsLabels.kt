package com.playfieldportal.feature.settings.ui

import com.playfieldportal.feature.settings.R
import androidx.annotation.StringRes

/**
 * Settings and wizard labels the context-menu glossary names, so every screen that shows one reads the same text.
 *
 * The constants are the English reference (the glossary test pins them). What a screen draws is the
 * matching string resource: `stringResource(SettingsLabels.EDIT_FOLDER_RES)` and so on.
 */
internal object SettingsLabels {
    @StringRes val EDIT_FOLDER_RES = R.string.sets_label_edit_folder
    @StringRes val REMOVE_FOLDER_RES = R.string.sets_label_remove_folder
    @StringRes val OPTIONS_HINT_RES = R.string.sets_label_options_hint
    @StringRes val SHOW_ON_BAR_RES = R.string.sets_label_show_on_bar
    @StringRes val SHOW_IN_GAMES_RES = R.string.sets_label_show_in_games
    @StringRes val PIN_TO_TOP_RES = R.string.sets_label_pin_to_top

    const val EDIT_FOLDER = "Edit Folder"
    const val REMOVE_FOLDER = "Remove Folder"
    const val OPTIONS_HINT = "Options Hint"
    const val SHOW_ON_BAR = "Show on Bar"
    const val SHOW_IN_GAMES = "Show in Games"
    const val PIN_TO_TOP = "Pin to Top"
}
