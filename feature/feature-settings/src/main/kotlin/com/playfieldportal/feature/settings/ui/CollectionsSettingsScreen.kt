package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.ui.icons.CATEGORY_ICON_CATALOG
import com.playfieldportal.core.ui.icons.categoryIconFor
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
    val gamingCategories by viewModel.gamingCategories.collectAsState()

    var openCollectionId by remember { mutableStateOf<Long?>(null) }
    // Pending text dialog: Pair(title, renameId?) — renameId null means "create new".
    var dialog by remember { mutableStateOf<CollectionDialog?>(null) }
    var selectedCategoryForNewCollection by remember { mutableStateOf<String?>(null) }
    // Non-null while the icon picker is open for that collection id.
    var iconPickerFor by remember { mutableStateOf<Long?>(null) }

    val openCollection = collections.firstOrNull { it.id == openCollectionId }

    // BACK collapses the current sub-step before leaving the screen.
    val handleBack: () -> Unit = {
        when {
            iconPickerFor != null     -> iconPickerFor = null
            selectedCategoryForNewCollection != null -> selectedCategoryForNewCollection = null
            dialog != null            -> dialog = null
            openCollectionId != null  -> openCollectionId = null
            else                      -> onBack()
        }
    }

    // Each step owns its own SettingsScaffold so opening/closing a collection re-mounts it and
    // re-assigns controller focus (a single shared scaffold never re-runs its focus pass, which is
    // what broke the cursor after clicking into a collection).
    if (openCollection == null) {
        CollectionListStep(
            collections = collections,
            onCreate    = { dialog = CollectionDialog(title = "New Collection") },
            onOpen      = { openCollectionId = it.id },
            onBack      = handleBack,
            modifier    = modifier,
        )
    } else {
        CollectionDetailStep(
            collection  = openCollection,
            gamesFlow   = { viewModel.gamesIn(openCollection.id) },
            onRename    = { dialog = CollectionDialog(title = "Rename Collection", renameId = openCollection.id, initial = openCollection.name) },
            onChangeIcon = { iconPickerFor = openCollection.id },
            onMoveUp    = { viewModel.moveUp(openCollection.id) },
            onMoveDown  = { viewModel.moveDown(openCollection.id) },
            onDelete    = { viewModel.delete(openCollection.id); openCollectionId = null },
            onRemoveGame = { game -> viewModel.removeGame(openCollection.id, game.id) },
            onBack      = handleBack,
            modifier    = modifier,
        )
    }

    // Show text dialog for name entry (new or rename)
    if (selectedCategoryForNewCollection == null) {
        dialog?.let { d ->
            CollectionTextDialog(
                title = d.title,
                initial = d.initial,
                onConfirm = { name ->
                    if (d.renameId != null) {
                        // Rename existing collection
                        viewModel.rename(d.renameId, name)
                        dialog = null
                    } else {
                        // Creating new collection — ask which category to add to
                        d.pendingName = name
                        selectedCategoryForNewCollection = gamingCategories.firstOrNull()?.id ?: "games"
                    }
                },
                onCancel = { dialog = null },
            )
        }
    }

    // Show category picker for new collection
    dialog?.pendingName?.let { name ->
        if (selectedCategoryForNewCollection != null) {
            CollectionCategoryPickerDialog(
                categories = gamingCategories,
                selectedCategoryId = selectedCategoryForNewCollection ?: "games",
                onCategorySelected = { categoryId ->
                    viewModel.create(name, categoryId)
                    selectedCategoryForNewCollection = null
                    dialog = null
                },
                onCancel = { selectedCategoryForNewCollection = null },
            )
        }
    }

    // Icon picker for the open collection.
    iconPickerFor?.let { id ->
        val current = collections.firstOrNull { it.id == id }?.iconKey
        CollectionIconPickerDialog(
            selectedIconKey = current,
            onPick = { key -> viewModel.setIcon(id, key); iconPickerFor = null },
            onCancel = { iconPickerFor = null },
        )
    }
}

private data class CollectionDialog(
    val title: String,
    val renameId: Long? = null,
    val initial: String = "",
    var pendingName: String? = null,  // Temporarily holds name while category is selected
)

@Composable
private fun CollectionListStep(
    collections: List<GameCollection>,
    onCreate: () -> Unit,
    onOpen: (GameCollection) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "Settings", subtitle = "Collections", onBack = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
    }
}

@Composable
private fun CollectionDetailStep(
    collection: GameCollection,
    gamesFlow: () -> kotlinx.coroutines.flow.Flow<List<Game>>,
    onRename: () -> Unit,
    onChangeIcon: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
    onRemoveGame: (Game) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val games by remember(collection.id) { gamesFlow() }.collectAsState(initial = emptyList())

    SettingsScaffold(title = "Settings", subtitle = collection.name, onBack = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsGroup("Collection")
            SettingsRow(label = "Rename", onClick = onRename)
            SettingsRow(
                label    = "Change Icon",
                sublabel = collection.iconKey?.let { categoryIconFor(it).label } ?: "Default (Memory Card)",
                onClick  = onChangeIcon,
            )
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

@Composable
private fun CollectionCategoryPickerDialog(
    categories: List<Category>,
    selectedCategoryId: String,
    onCategorySelected: (String) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add to Category") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                categories.forEach { category ->
                    TextButton(
                        onClick = { onCategorySelected(category.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            category.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            color = if (category.id == selectedCategoryId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    )
}

// Icon picker for a collection: a "Default (Memory Card)" option plus the shared category icon
// catalog. Picking null resets to the default art. Mirrors the category icon picker.
@Composable
private fun CollectionIconPickerDialog(
    selectedIconKey: String?,
    onPick: (String?) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Collection Icon") },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { onPick(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Default (Memory Card)",
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        color = if (selectedIconKey == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(56.dp),
                    modifier = Modifier.fillMaxWidth().height(320.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(CATEGORY_ICON_CATALOG, key = { it.key }) { icon ->
                        Image(
                            painter = painterResource(icon.resId),
                            contentDescription = icon.label,
                            modifier = Modifier
                                .size(48.dp)
                                .selectable(
                                    selected = icon.key == selectedIconKey,
                                    onClick = { onPick(icon.key) },
                                ),
                        )
                    }
                }
            }
        },
    )
}
