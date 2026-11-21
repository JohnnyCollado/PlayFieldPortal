package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.ui.theme.themedText
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.ArtworkOrphanViewModel

/**
 * Unmatched Artwork — the files Scan & Relink walked but could not tie to a game, and the place a
 * user resolves one by hand (C22 task T4).
 *
 * **Results appear only after an explicit Search.** There is no `LaunchedEffect(query)` and no
 * debounce anywhere in this file; the text field's only job is to hold what was typed. The one
 * call to `viewModel.search()` is the Search row (and the keyboard's Search action, which is the
 * same explicit press by another input method).
 *
 * Everything here is local — the candidate list is the user's own games on that file's platform.
 * No provider is contacted.
 */
@Composable
fun ArtworkOrphanScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtworkOrphanViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selected = state.selected

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = if (selected == null) stringResource(R.string.setd_orphan_title)
        else stringResource(R.string.setd_orphan_link_title, selected.stem),
        onBack = if (selected == null) onBack else ({ viewModel.clearSelection() }),
        modifier = modifier,
    ) {
        state.notice?.let { notice ->
            SettingsRow(label = notice.asString(), onClick = { viewModel.dismissNotice() })
        }

        if (selected == null) {
            OrphanList(state, viewModel)
        } else {
            OrphanResolver(state, viewModel)
        }
    }
}

@Composable
private fun OrphanList(
    state: com.playfieldportal.feature.settings.viewmodel.ArtworkOrphanUiState,
    viewModel: ArtworkOrphanViewModel,
) {
    if (state.loading) {
        SettingsRow(label = stringResource(R.string.setd_loading))
        return
    }
    if (state.orphans.isEmpty()) {
        SettingsRow(
            label = stringResource(R.string.setd_orphan_none),
            sublabel = stringResource(R.string.setd_orphan_none_sublabel),
        )
        return
    }

    SettingsGroup(pluralStringResource(R.plurals.setd_orphan_count, state.orphans.size, state.orphans.size))
    // Grouped by platform so a user resolving one console's drop is not reading the whole library.
    state.orphans.groupBy { it.platformId }.forEach { (platformId, rows) ->
        SettingsGroup(platformId.uppercase())
        rows.forEach { row ->
            SettingsRow(
                label = row.fileName,
                sublabel = row.artworkType.lowercase().replace('_', ' '),
                onClick = { viewModel.select(row) },
            )
        }
    }
}

@Composable
private fun OrphanResolver(
    state: com.playfieldportal.feature.settings.viewmodel.ArtworkOrphanUiState,
    viewModel: ArtworkOrphanViewModel,
) {
    val selected = state.selected ?: return

    SettingsGroup(stringResource(R.string.setd_orphan_group_file))
    SettingsRow(
        label = selected.fileName,
        sublabel = "${selected.platformId.uppercase()} · ${selected.artworkType.lowercase().replace('_', ' ')}",
    )

    SettingsGroup(stringResource(R.string.setd_orphan_group_search))
    SettingsRow(
        label = stringResource(R.string.setd_orphan_title_field),
        trailing = {
            BasicTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = themedText(Color.White), fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                // The keyboard's Search key is the same explicit press as the row below — it is
                // not typing that triggers a search, it is asking for one.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.search() }),
                modifier = Modifier.fillMaxWidth(0.6f).padding(end = 8.dp),
            )
        },
    )
    SettingsRow(
        label = stringResource(if (state.searching) R.string.setd_orphan_searching else R.string.setd_orphan_search),
        sublabel = stringResource(R.string.setd_orphan_search_sublabel, selected.platformId.uppercase()),
        onClick = if (state.searching) null else ({ viewModel.search() }),
    )

    when (val results = state.results) {
        // Nothing has been asked for yet. This is the state the requirement is about: an opened
        // file shows no candidates at all until the user presses Search.
        null -> SettingsRow(
            label = stringResource(R.string.setd_orphan_no_search),
            sublabel = stringResource(R.string.setd_orphan_no_search_sublabel),
        )
        else -> if (results.isEmpty()) {
            SettingsRow(
                label = stringResource(R.string.setd_orphan_no_match),
                sublabel = stringResource(R.string.setd_orphan_no_match_sublabel),
            )
        } else {
            SettingsGroup(pluralStringResource(R.plurals.setd_orphan_matches, results.size, results.size))
            results.forEach { candidate ->
                SettingsRow(
                    label = candidate.title,
                    onClick = { viewModel.assign(candidate.gameId) },
                )
            }
        }
    }
}
