package com.playfieldportal.feature.xmb.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * The current [Resources], for the plain functions that build menu rows, prompt items and modal
 * specs from state and so cannot call `stringResource` themselves. Reading the configuration makes
 * the caller recompose when the language changes, the same way `stringResource` does.
 */
@Composable
@ReadOnlyComposable
internal fun uiResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}
