package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.components.XmbKebabTouchButton

/** Test tag for a detail page's touch Options button. */
internal const val DETAIL_OPTIONS_TAG = "detail:options"

/**
 * A detail page's Options in touch mode: the app-wide drawn ⋮ kebab, at the header's right and the
 * pills' height (ARCHITECTURE.md ▸ Conventions) — never a text "Options" pill in the footer band.
 */
@Composable
internal fun DetailOptionsKebab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    XmbKebabTouchButton(onClick = onClick, modifier = modifier.testTag(DETAIL_OPTIONS_TAG), size = 36.dp)
}
