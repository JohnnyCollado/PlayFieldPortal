package com.playfieldportal.core.ui.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.playfieldportal.core.domain.text.UiText

/**
 * Resolves [UiText] in the current language. Reading the configuration makes the caller recompose
 * when the language changes, the same way `stringResource` does.
 */
@Composable
@ReadOnlyComposable
fun UiText.asString(): String {
    LocalConfiguration.current
    return resolve(LocalContext.current.resources)
}
