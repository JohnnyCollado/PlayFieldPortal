package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PfpConfirmModal
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.components.PfpModalNav
import com.playfieldportal.core.ui.components.PfpTextEntryModal
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.icons.CATEGORY_ICON_CATALOG
import com.playfieldportal.core.ui.icons.categoryIconFor
import com.playfieldportal.feature.settings.viewmodel.CollectionsSettingsViewModel

// A two-level, fully controller-navigable manager:
//   • List step  — create a collection, or open one.
//   • Detail step — rename / reorder / delete the collection, and remove its games.
// Naming uses the shared text entry modal (a keyboard is unavoidable for free-text) and deleting
// asks first through the shared confirm modal; both are driven by the scaffold's interceptor, so
// D-Pad + A/B work inside them. Every other action is a focusable row.
@Composable
fun CollectionsSettingsScreen(
    onBack: () -> Unit,
    // A card game's menu ▸ View Game Details: the host opens Game Detail above Settings.
    onOpenGameDetail: (gameId: Long) -> Unit = {},
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
    // The name modal's text and cursor, and the collection a delete is being confirmed for.
    var nameText by remember { mutableStateOf("") }
    var modalFocus by remember { mutableStateOf(PfpModalFocus.FIELD) }
    var deleteConfirmFor by remember { mutableStateOf<Long?>(null) }

    val openCollection = collections.firstOrNull { it.id == openCollectionId }
    val deleteTarget = collections.firstOrNull { it.id == deleteConfirmFor }

    // The name modal is up until a name is confirmed; for a new collection the category picker
    // then takes over while the pending name waits in [dialog].
    val nameDialog = dialog?.takeIf { selectedCategoryForNewCollection == null }
    val nameConfirmEnabled = PfpModalNav.textEntryConfirmEnabled(nameText, error = null, maxLength = null)
    val showHints = LocalSettingsShowControllerHint.current
    val menuSounds = LocalMenuSounds.current
    val itemMenu = rememberSettingsItemMenu()

    val openNameDialog: (CollectionDialog) -> Unit = { d ->
        nameText = d.initial
        modalFocus = PfpModalFocus.FIELD
        dialog = d
    }
    val confirmName: () -> Unit = {
        nameDialog?.let { d ->
            val name = nameText.trim()
            if (d.renameId != null) {
                viewModel.rename(d.renameId, name)
                dialog = null
            } else {
                // Creating new collection — ask which category to add to
                d.pendingName = name
                selectedCategoryForNewCollection = gamingCategories.firstOrNull()?.id ?: "games"
            }
        }
    }
    val confirmDelete: () -> Unit = {
        deleteConfirmFor?.let { id ->
            viewModel.delete(id)
            deleteConfirmFor = null
            openCollectionId = null
        }
    }

    // A modal is a hard input boundary: while one is up, nothing behind it sees a press.
    val modalOpen = nameDialog != null || deleteTarget != null
    val interceptModal: (GamepadAction) -> Boolean = { action ->
        when {
            itemMenu.intercept(action) -> true
            nameDialog != null -> {
                PfpModalNav.handle(
                    action = action,
                    focus = modalFocus,
                    hasField = true,
                    confirmEnabled = nameConfirmEnabled,
                    sounds = menuSounds,
                    onFocusChange = { modalFocus = it },
                    onConfirm = confirmName,
                    onCancel = { dialog = null },
                )
                true
            }
            deleteTarget != null -> {
                PfpModalNav.handle(
                    action = action,
                    focus = modalFocus,
                    hasField = false,
                    confirmEnabled = true,
                    sounds = menuSounds,
                    onFocusChange = { modalFocus = it },
                    onConfirm = confirmDelete,
                    onCancel = { deleteConfirmFor = null },
                )
                true
            }
            else -> false
        }
    }

    // BACK collapses the current sub-step before leaving the screen.
    val handleBack: () -> Unit = {
        when {
            deleteConfirmFor != null  -> deleteConfirmFor = null
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
    Box(modifier = modifier) {
    if (openCollection == null) {
        CollectionListStep(
            collections = collections,
            onCreate    = { openNameDialog(CollectionDialog(title = "New Custom Memory Card")) },
            onOpen      = { openCollectionId = it.id },
            onBack      = handleBack,
            modalOpen   = modalOpen,
            onInterceptAction = interceptModal,
            modifier    = Modifier.fillMaxSize(),
        )
    } else {
        CollectionDetailStep(
            collection  = openCollection,
            gamesFlow   = { viewModel.gamesIn(openCollection.id) },
            onRename    = { openNameDialog(CollectionDialog(title = "Rename Custom Memory Card", renameId = openCollection.id, initial = openCollection.name)) },
            onChangeIcon = { iconPickerFor = openCollection.id },
            onMoveUp    = { viewModel.moveUp(openCollection.id) },
            onMoveDown  = { viewModel.moveDown(openCollection.id) },
            onDelete    = {
                // Opens on Cancel: a stray second press must not delete the collection.
                modalFocus = PfpModalNav.initialConfirmFocus(destructive = true)
                deleteConfirmFor = openCollection.id
            },
            onGameMenu  = { game ->
                itemMenu.show(
                    game.displayTitle,
                    collectionGameMenuRows(
                        gameId = game.id,
                        onViewDetails = onOpenGameDetail,
                        onRemove = { viewModel.removeGame(openCollection.id, it) },
                    ),
                )
            },
            onBack      = handleBack,
            modalOpen   = modalOpen,
            onInterceptAction = interceptModal,
            modifier    = Modifier.fillMaxSize(),
        )
    }
    itemMenu.Content()
    }

    // Name entry (new or rename)
    nameDialog?.let { d ->
        PfpTextEntryModal(
            title = d.title,
            value = nameText,
            onValueChange = { nameText = it },
            focus = modalFocus,
            onFocusChange = { modalFocus = it },
            onConfirm = confirmName,
            onCancel = { dialog = null },
            placeholder = "Card name",
            // A new collection still has its category to pick, so its name is not yet a save.
            confirmLabel = if (d.renameId != null) "Save" else "Next",
            showHints = showHints,
        )
    }

    deleteTarget?.let { target ->
        PfpConfirmModal(
            title = "Delete Custom Card",
            message = "\"${target.name}\" will be deleted. The games in it stay in your library.",
            confirmLabel = "Delete",
            focus = modalFocus,
            destructive = true,
            onConfirm = confirmDelete,
            onCancel = { deleteConfirmFor = null },
            showHints = showHints,
        )
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

/** A card game's menu. Remove from Card is reversible (add it back from the game's menu), so it is plain and unconfirmed. */
internal fun collectionGameMenuRows(
    gameId: Long,
    onViewDetails: (Long) -> Unit,
    onRemove: (Long) -> Unit,
): List<SettingsMenuItem> = listOf(
    SettingsMenuItem("View Game Details") { onViewDetails(gameId) },
    SettingsMenuItem("Remove from Card") { onRemove(gameId) },
)

internal fun collectionGameSublabel(platformId: String): String = platformId.uppercase()

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
    modalOpen: Boolean,
    onInterceptAction: (GamepadAction) -> Boolean,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = "Settings",
        subtitle = "Custom Memory Cards",
        onBack = onBack,
        modifier = modifier,
        modalOpen = modalOpen,
        onInterceptAction = onInterceptAction,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup("Manage")
            SettingsRow(
                label    = "New Custom Memory Card",
                sublabel = "e.g. RPGs, Currently Playing, Best PSP Games",
                onClick  = onCreate,
            )

            SettingsGroup("Your Custom Memory Cards")
            if (collections.isEmpty()) {
                SettingsRow(
                    label    = "No custom memory cards yet",
                    sublabel = "Create one above, or add a game from its Options menu.",
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
    onGameMenu: (Game) -> Unit,
    onBack: () -> Unit,
    modalOpen: Boolean,
    onInterceptAction: (GamepadAction) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val games by remember(collection.id) { gamesFlow() }.collectAsState(initial = emptyList())

    SettingsScaffold(
        title = "Settings",
        subtitle = collection.name,
        onBack = onBack,
        modifier = modifier,
        modalOpen = modalOpen,
        onInterceptAction = onInterceptAction,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup("Custom Memory Card")
            SettingsRow(label = "Rename", onClick = onRename)
            SettingsRow(
                label    = "Change Icon",
                sublabel = collection.iconKey?.let { categoryIconFor(it).label } ?: "Default (Memory Card)",
                onClick  = onChangeIcon,
            )
            SettingsRow(label = "Move Up", onClick = onMoveUp)
            SettingsRow(label = "Move Down", onClick = onMoveDown)
            SettingsRow(label = "Delete Custom Card", sublabel = "Removes the card; its games stay in your library", onClick = onDelete)

            SettingsGroup("Games (${games.size})")
            if (games.isEmpty()) {
                SettingsRow(
                    label    = "No games on this card",
                    sublabel = "Add games from their Options menu.",
                )
            } else {
                games.forEach { game ->
                    SettingsRow(
                        label    = game.displayTitle,
                        sublabel = collectionGameSublabel(game.platformId),
                        onClick  = { onGameMenu(game) },
                        onLongPress = { onGameMenu(game) },
                    )
                }
            }
        }
    }
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
        title = { Text("Card Icon") },
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
