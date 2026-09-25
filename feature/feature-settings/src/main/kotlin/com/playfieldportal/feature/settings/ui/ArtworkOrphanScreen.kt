package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
        title = "Settings",
        subtitle = if (selected == null) "Unmatched Artwork" else "Link “${selected.stem}”",
        onBack = if (selected == null) onBack else ({ viewModel.clearSelection() }),
        modifier = modifier,
    ) {
        state.notice?.let { notice ->
            SettingsRow(label = notice, onClick = { viewModel.dismissNotice() })
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
        SettingsRow(label = "Loading…")
        return
    }
    if (state.orphans.isEmpty()) {
        SettingsRow(
            label = "Nothing unmatched",
            sublabel = "Every artwork file in your folder is linked to a game. " +
                "Run Scan & Relink after adding files to check again.",
        )
        return
    }

    SettingsGroup("${state.orphans.size} files matched no game")
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

    SettingsGroup("File")
    SettingsRow(
        label = selected.fileName,
        sublabel = "${selected.platformId.uppercase()} · ${selected.artworkType.lowercase().replace('_', ' ')}",
    )

    SettingsGroup("Search your games")
    SettingsRow(
        label = "Title",
        trailing = {
            BasicTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
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
        label = if (state.searching) "Searching…" else "Search",
        sublabel = "Looks only at your own ${selected.platformId.uppercase()} games — nothing is downloaded",
        onClick = if (state.searching) null else ({ viewModel.search() }),
    )

    when (val results = state.results) {
        // Nothing has been asked for yet. This is the state the requirement is about: an opened
        // file shows no candidates at all until the user presses Search.
        null -> SettingsRow(
            label = "No search yet",
            sublabel = "Edit the title above and press Search to see matching games",
        )
        else -> if (results.isEmpty()) {
            SettingsRow(
                label = "No matching games",
                sublabel = "Try a shorter title — a single distinctive word usually works",
            )
        } else {
            SettingsGroup("${results.size} match(es) — pick the owner")
            results.forEach { candidate ->
                SettingsRow(
                    label = candidate.title,
                    onClick = { viewModel.assign(candidate.gameId) },
                )
            }
        }
    }
}
