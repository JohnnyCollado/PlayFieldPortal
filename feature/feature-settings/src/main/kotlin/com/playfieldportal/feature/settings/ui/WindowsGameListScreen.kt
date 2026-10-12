package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.domain.model.WindowsGameLabel
import com.playfieldportal.core.ui.components.PfpChoiceOption
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.settings.viewmodel.KitChoice
import com.playfieldportal.feature.settings.viewmodel.WindowsGameListUiState
import com.playfieldportal.feature.settings.viewmodel.WindowsGameListViewModel
import com.playfieldportal.feature.settings.viewmodel.WindowsGameRow
import com.playfieldportal.feature.settings.viewmodel.labelPickerItems
import com.playfieldportal.feature.settings.viewmodel.labelValueText
import com.playfieldportal.feature.settings.viewmodel.showsFolderAction

private const val FOLDER_ACTION_LINK = "Link Folder"
private const val FOLDER_ACTION_RELINK = "Change Linked Folder"

/**
 * Windows ▸ Game List: one row per Windows game with its label and, for Local games, a folder
 * action. A / tap on a row opens the label picker; RIGHT reaches the folder icon (A / tap picks the
 * folder). Back is the scaffold's: B, or the touch back chip.
 */
@Composable
fun WindowsGameListScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WindowsGameListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // The ViewModel outlives this screen, so re-read on every visit; `loaded` stays true, so a
    // revisit shows the previous rows rather than flashing the empty state.
    LaunchedEffect(Unit) { viewModel.load() }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onFolderPicked(uri) }

    LaunchedEffect(state.requestFolderPick) {
        if (state.requestFolderPick) {
            viewModel.onFolderPickLaunched()
            folderPicker.launch(null)
        }
    }

    WindowsGameListContent(
        state = state,
        onBack = onBack,
        onSetLabel = viewModel::setLabel,
        onPickFolder = viewModel::beginFolderPick,
        onLinkOnly = viewModel::linkOnly,
        onInstallKitAndLink = viewModel::installKitAndLink,
        onDismissKitChoice = viewModel::dismissKitChoice,
        onDismissNotice = viewModel::dismissNotice,
        modifier = modifier,
    )
}

@Composable
private fun WindowsGameListContent(
    state: WindowsGameListUiState,
    onBack: () -> Unit,
    onSetLabel: (Long, WindowsGameLabel?) -> Unit,
    onPickFolder: (Long) -> Unit,
    onLinkOnly: () -> Unit,
    onInstallKitAndLink: () -> Unit,
    onDismissKitChoice: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier,
) {
    val itemMenu = rememberSettingsItemMenu()
    val modal = rememberSettingsModal(
        state.kitChoice?.let { kitChoiceSpec(it, onLinkOnly, onInstallKitAndLink, onDismissKitChoice) }
            ?: state.notice?.let { message ->
                PfpModalSpec.Notice(
                    key = "notice:$message",
                    title = "Game List",
                    message = message,
                    onDismiss = onDismissNotice,
                )
            },
    )

    SettingsScaffold(
        title = "Game List",
        subtitle = "${state.rows.size} games",
        onBack = onBack,
        modifier = modifier,
        modalOpen = modal.open,
        onInterceptAction = { modal.intercept(it) || itemMenu.intercept(it) },
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            if (state.loaded && state.rows.isEmpty()) {
                Text(
                    text = "No Windows games yet — import some from Import PC Games first.",
                    color = SettingsSubtext,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                )
            }
            state.rows.forEach { row ->
                GameListRow(row, itemMenu, onSetLabel, onPickFolder)
            }
        }
    }

    modal.Content()
    itemMenu.Content()
}

@Composable
private fun GameListRow(
    row: WindowsGameRow,
    itemMenu: SettingsItemMenuState,
    onSetLabel: (Long, WindowsGameLabel?) -> Unit,
    onPickFolder: (Long) -> Unit,
) {
    val showFolder = showsFolderAction(row)
    SettingsRow(
        label = row.title,
        // The linked folder, muted under the title; only a Local game carries one.
        sublabel = row.folderPath?.takeIf { showFolder },
        focusKey = "game:${row.gameId}",
        enabled = !row.busy,
        hideRowHighlightOnActionFocus = showFolder,
        trailing = {
            Text(
                text = if (row.busy) "Working…" else labelValueText(row),
                color = SettingsText,
                fontSize = 13.sp,
                style = TextStyle(shadow = SettingsTextShadow),
            )
        },
        onClick = {
            itemMenu.show(
                row.title,
                labelPickerItems(row).map { item ->
                    SettingsMenuItem(item.text, value = "Current".takeIf { item.isCurrent }) {
                        onSetLabel(row.gameId, item.label)
                    }
                },
            )
        },
        actions = if (showFolder) listOf(folderAction(row) { onPickFolder(row.gameId) }) else emptyList(),
    )
}

private fun folderAction(row: WindowsGameRow, onClick: () -> Unit): SettingsRowAction {
    val label = if (row.folderLinked) FOLDER_ACTION_RELINK else FOLDER_ACTION_LINK
    return SettingsRowAction(
        label, onClick,
        actionFocusBackgroundColor = lerp(SettingsAccent, Color.Black, 0.50f),
    ) {
        // Material has no folder-check glyph, so a linked folder is Folder with a small Check badge.
        Box(
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                .padding(4.dp),
        ) {
            Icon(Icons.Default.Folder, contentDescription = label, tint = SettingsAccent)
            if (row.folderLinked) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(10.dp)
                        .align(Alignment.Center)
                        .padding(top = 2.dp),
                )
            }
        }
    }
}

// A picked folder that carries no achievement list: link as is, or write the emulator kit first.
private fun kitChoiceSpec(
    choice: KitChoice,
    onLinkOnly: () -> Unit,
    onInstallKitAndLink: () -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec {
    val options = buildList {
        add(PfpChoiceOption("Link only"))
        if (choice.installerEnabled) add(PfpChoiceOption("Install kit & link"))
    }
    return PfpModalSpec.Choice(
        key = choice,
        title = "No achievement list in \"${choice.folderName}\"",
        message = "This folder has no emulator achievement data yet. Link it as is, or install the " +
            "emulator kit into it first.",
        options = options,
        confirmLabel = "Link",
        onConfirm = { index -> if (index == 1) onInstallKitAndLink() else onLinkOnly() },
        onCancel = onCancel,
    )
}
