package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.feature.settings.viewmodel.CollectionsSettingsViewModel

// A two-level, fully controller-navigable manager:
//   • List step  — create a collection, or open one.
//   • Detail step — rename / reorder / delete the collection, and remove its games.
// Naming uses a text dialog (a keyboard is unavoidable for free-text); every other action is
// a focusable row that works with D-Pad + A/B.
@Composable
fun CollectionsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CollectionsSettingsViewModel = hiltViewModel(),
) {
    val collections by viewModel.collections.collectAsState()

    var openCollectionId by remember { mutableStateOf<Long?>(null) }
    // Pending text dialog: Pair(title, renameId?) — renameId null means "create new".
    var dialog by remember { mutableStateOf<CollectionDialog?>(null) }

    val openCollection = collections.firstOrNull { it.id == openCollectionId }

    // BACK collapses the current sub-step before leaving the screen.
    val handleBack: () -> Unit = {
        when {
            dialog != null            -> dialog = null
            openCollectionId != null  -> openCollectionId = null
            else                      -> onBack()
        }
    }

    SettingsScaffold(
        title    = "Settings",
        subtitle = if (openCollection != null) openCollection.name else "Collections",
        onBack   = handleBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (openCollection == null) {
                CollectionListStep(
                    collections = collections,
                    onCreate    = { dialog = CollectionDialog(title = "New Collection") },
                    onOpen      = { openCollectionId = it.id },
                )
            } else {
                CollectionDetailStep(
                    collection  = openCollection,
                    gamesFlow   = { viewModel.gamesIn(openCollection.id) },
                    onRename    = { dialog = CollectionDialog(title = "Rename Collection", renameId = openCollection.id, initial = openCollection.name) },
                    onMoveUp    = { viewModel.moveUp(openCollection.id) },
                    onMoveDown  = { viewModel.moveDown(openCollection.id) },
                    onDelete    = { viewModel.delete(openCollection.id); openCollectionId = null },
                    onRemoveGame = { game -> viewModel.removeGame(openCollection.id, game.id) },
                )
            }
        }
    }

    dialog?.let { d ->
        CollectionTextDialog(
            title = d.title,
            initial = d.initial,
            onConfirm = { name ->
                if (d.renameId != null) viewModel.rename(d.renameId, name) else viewModel.create(name)
                dialog = null
            },
            onCancel = { dialog = null },
        )
    }
}

private data class CollectionDialog(
    val title: String,
    val renameId: Long? = null,
    val initial: String = "",
)

@Composable
private fun CollectionListStep(
    collections: List<GameCollection>,
    onCreate: () -> Unit,
    onOpen: (GameCollection) -> Unit,
) {
    SettingsGroup("Manage")
    SettingsRow(
        label    = "Create New Collection",
        sublabel = "e.g. RPGs, Currently Playing, Best PSP Games",
        onClick  = onCreate,
    )

    SettingsGroup("Your Collections")
    if (collections.isEmpty()) {
        SettingsRow(
            label    = "No collections yet",
            sublabel = "Create one above, or add a game from its options (△) menu.",
        )
    } else {
        collections.forEach { collection ->
            SettingsRow(
                label    = collection.name,
                sublabel = "${collection.gameCount} ${if (collection.gameCount == 1) "game" else "games"}",
                onClick  = { onOpen(collection) },
            )
        }
    }
}

@Composable
private fun CollectionDetailStep(
    collection: GameCollection,
    gamesFlow: () -> kotlinx.coroutines.flow.Flow<List<Game>>,
    onRename: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onRemoveGame: (Game) -> Unit,
) {
    val games by remember(collection.id) { gamesFlow() }.collectAsState(initial = emptyList())

    SettingsGroup("Collection")
    SettingsRow(label = "Rename", onClick = onRename)
    SettingsRow(label = "Move Up", onClick = onMoveUp)
    SettingsRow(label = "Move Down", onClick = onMoveDown)
    SettingsRow(label = "Delete Collection", sublabel = "Removes the collection; games are kept", onClick = onDelete)

    SettingsGroup("Games (${games.size})")
    if (games.isEmpty()) {
        SettingsRow(
            label    = "No games in this collection",
            sublabel = "Add games from their options (△) menu.",
        )
    } else {
        games.forEach { game ->
            SettingsRow(
                label    = game.displayTitle,
                sublabel = "${game.platformId.uppercase()}  ·  tap to remove from collection",
                onClick  = { onRemoveGame(game) },
            )
        }
    }
}

@Composable
private fun CollectionTextDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("Collection name") },
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
