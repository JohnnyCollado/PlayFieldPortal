package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.icons.UserCardIconKeys
import com.playfieldportal.core.ui.icons.LocalXmbIcons
import com.playfieldportal.core.ui.motion.LocalIconFocused
import com.playfieldportal.core.ui.icons.CustomIconSurface
import com.playfieldportal.core.ui.icons.CustomIcon
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import com.playfieldportal.core.ui.icons.CategoryIconGlyph
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.components.PfpChoiceOption
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    val cardCategories by viewModel.cardCategories.collectAsState()
    // A card in an app category holds apps, so its text says so (survey L28).
    val holdsApps: (GameCollection) -> Boolean = { c ->
        cardCategories.firstOrNull { it.id == c.categoryId }?.isGamingCategory == false
    }

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
                selectedCategoryForNewCollection = cardCategories.firstOrNull()?.id ?: "games"
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

    // The new card's category, as the shared Choice modal so a pad can pick it (survey P6).
    val pendingName = dialog?.pendingName?.takeIf { selectedCategoryForNewCollection != null }
    val categoryModal = rememberSettingsModal(
        pendingName?.let { name ->
            cardCategoryChoiceSpec(
                cardName = name,
                categories = cardCategories,
                onPick = { categoryId ->
                    viewModel.create(name, categoryId)
                    selectedCategoryForNewCollection = null
                    dialog = null
                },
                onCancel = { selectedCategoryForNewCollection = null },
            )
        },
    )

    // A modal is a hard input boundary: while one is up, nothing behind it sees a press.
    val modalOpen = nameDialog != null || deleteTarget != null || categoryModal.open
    val interceptModal: (GamepadAction) -> Boolean = { action ->
        when {
            categoryModal.intercept(action) -> true
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
    val iconTarget = collections.firstOrNull { it.id == iconPickerFor }
    if (iconTarget != null) {
        CollectionIconStep(
            collection = iconTarget,
            viewModel = viewModel,
            onPick = { key -> viewModel.setIcon(iconTarget.id, key); iconPickerFor = null },
            onBack = handleBack,
            modifier = Modifier.fillMaxSize(),
        )
    } else if (openCollection == null) {
        CollectionListStep(
            collections = collections,
            onCreate    = { openNameDialog(CollectionDialog(title = "New Custom Memory Card")) },
            onOpen      = { openCollectionId = it.id },
            holdsApps   = holdsApps,
            onBack      = handleBack,
            modalOpen   = modalOpen,
            onInterceptAction = interceptModal,
            modifier    = Modifier.fillMaxSize(),
        )
    } else {
        CollectionDetailStep(
            collection  = openCollection,
            holdsApps   = holdsApps(openCollection),
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
            message = "\"${target.name}\" will be deleted. " +
                if (holdsApps(target)) "The apps on it stay installed." else "The games on it stay in your library.",
            confirmLabel = "Delete",
            focus = modalFocus,
            destructive = true,
            onConfirm = confirmDelete,
            onCancel = { deleteConfirmFor = null },
            showHints = showHints,
        )
    }

    categoryModal.Content()
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
    holdsApps: (GameCollection) -> Boolean,
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
                sublabel = "For games or apps, e.g. RPGs, Currently Playing, Work Apps",
                onClick  = onCreate,
            )

            SettingsGroup("Your Custom Memory Cards")
            if (collections.isEmpty()) {
                SettingsRow(
                    label    = "No custom memory cards yet",
                    sublabel = "Create one above, or use Add to Card in a game's or app's Options menu.",
                )
            } else {
                collections.forEach { collection ->
                    SettingsRow(
                        label    = collection.name,
                        sublabel = cardContentsLabel(collection.gameCount, holdsApps(collection)),
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
    holdsApps: Boolean,
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
            val noun = if (holdsApps) "apps" else "games"
            SettingsRow(
                label = "Delete Custom Card",
                sublabel = if (holdsApps) "Removes the card; its apps stay installed" else "Removes the card; its games stay in your library",
                onClick = onDelete,
            )

            SettingsGroup("${noun.replaceFirstChar { it.uppercase() }} (${games.size})")
            if (games.isEmpty()) {
                SettingsRow(
                    label    = "No $noun on this card",
                    sublabel = "Add $noun with Add to Card in their Options menu.",
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

/** "3 games" / "1 app": what a custom card holds, worded by its kind (survey L28). */
internal fun cardContentsLabel(count: Int, holdsApps: Boolean): String {
    val noun = if (holdsApps) "app" else "game"
    return "$count ${if (count == 1) noun else noun + "s"}"
}

/** The kind of card a category makes, shown under its name in the picker. */
internal fun cardCategoryDetail(category: Category): String =
    if (category.isGamingCategory) "Game card" else "App card"

/** Where a new card goes: every card category, as the shared Choice modal (survey L28, P6). */
internal fun cardCategoryChoiceSpec(
    cardName: String,
    categories: List<Category>,
    onPick: (String) -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec.Choice = PfpModalSpec.Choice(
    key = "card_category:$cardName",
    title = "Add to Category",
    message = "Which column should \"$cardName\" appear in?",
    options = categories.map { PfpChoiceOption(it.name, cardCategoryDetail(it)) },
    confirmLabel = "Create",
    onConfirm = { index -> categories.getOrNull(index)?.let { onPick(it.id) } },
    onCancel = onCancel,
)

/** One row of the card icon step: a catalog key (null = the default memory-card art) and its name. */
internal data class CardIconRow(val key: String?, val label: String)

/** The card icon step's rows: the default first, then the shared category icon catalog. */
internal fun cardIconRows(): List<CardIconRow> =
    listOf(CardIconRow(null, "Default (Memory Card)")) +
        CATEGORY_ICON_CATALOG.map { CardIconRow(it.key, it.label) }

/**
 * Change Icon for a card: the user's own image (still or animated GIF), one of the applied theme's
 * icons, or a built-in, the same choices a category has. A settings page of rows, so pad and touch
 * both reach everything. Only one choice is active: picking a built-in clears the card's own image,
 * and an image (yours or a theme's) shows over the built-in until it is removed.
 */
@Composable
private fun CollectionIconStep(
    collection: GameCollection,
    viewModel: CollectionsSettingsViewModel,
    onPick: (String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageKey = UserCardIconKeys.keyFor(collection.id)
    val storedKeys by viewModel.storedIconKeys.collectAsState()
    val themeIcons by viewModel.themeIcons.collectAsState()
    val message by viewModel.iconMessage.collectAsState()
    val hasImage = imageKey != null && imageKey in storedKeys
    LaunchedEffect(collection.id) {
        viewModel.dismissIconMessage()
        viewModel.loadThemeIcons()
    }
    // The store's gate decides by the resolver's MIME, not by the file name.
    val contentResolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importCardImage(collection.id, it, contentResolver.getType(it)) }
    }
    var previewFocused by remember { mutableStateOf(false) }
    val builtInLabel = collection.iconKey?.let { categoryIconFor(it).label } ?: "Default (Memory Card)"

    SettingsScaffold(
        title = "Settings",
        subtitle = "Card Icon · ${collection.name}",
        onBack = onBack,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            if (imageKey != null) {
                SettingsGroup("From Your Device")
                if (!hasImage) {
                    SettingsRow(
                        label = "Choose Image…",
                        sublabel = "PNG, JPG, WEBP, BMP, HEIC or GIF · animated GIFs play",
                        onClick = { picker.launch(DEVICE_IMAGE_MIME) },
                    )
                } else {
                    val icon = LocalXmbIcons.current[imageKey]
                    // Read-only preview row: it plays under Animated Images while the cursor is on it.
                    SettingsRow(
                        label = "Your Image",
                        sublabel = if (icon is CustomIcon.Animated) "Animated · in use" else "In use",
                        onFocusChangedExternal = { previewFocused = it },
                        trailing = {
                            if (icon != null) {
                                CompositionLocalProvider(LocalIconFocused provides previewFocused) {
                                    CustomIconSurface(icon, contentDescription = "Your Image", modifier = Modifier.size(40.dp))
                                }
                            }
                        },
                    )
                    SettingsRow(
                        label = "Replace Image…",
                        sublabel = "Pick a different file",
                        onClick = { picker.launch(DEVICE_IMAGE_MIME) },
                    )
                    SettingsRow(
                        label = "Remove Image",
                        sublabel = "Go back to the built-in icon ($builtInLabel)",
                        onClick = { viewModel.removeCardImage(collection.id) },
                    )
                }
                // A rejected file: the store's own message; the previous icon stays.
                message?.let { text ->
                    Text(
                        text = text,
                        color = SettingsText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    )
                }

                if (themeIcons.isNotEmpty()) {
                    SettingsGroup("From the Applied Theme")
                    themeIcons.forEach { choice ->
                        var rowFocused by remember(choice.file) { mutableStateOf(false) }
                        SettingsRow(
                            label = choice.label,
                            sublabel = if (choice.icon is CustomIcon.Animated) "Animated" else null,
                            onFocusChangedExternal = { rowFocused = it },
                            trailing = {
                                CompositionLocalProvider(LocalIconFocused provides rowFocused) {
                                    CustomIconSurface(choice.icon, contentDescription = choice.label, modifier = Modifier.size(40.dp))
                                }
                            },
                            onClick = { viewModel.chooseThemeIcon(collection.id, choice.file) },
                        )
                    }
                }
            }

            SettingsGroup("Built-in")
            cardIconRows().forEach { row ->
                SettingsRow(
                    label = row.label,
                    // A card's own image draws over its built-in choice, so that choice reads as in
                    // use only when there is no image.
                    sublabel = if (!hasImage && row.key == collection.iconKey) "In use" else null,
                    trailing = row.key?.let { key ->
                        { CategoryIconGlyph(key, contentDescription = row.label, modifier = Modifier.size(40.dp)) }
                    },
                    onClick = { onPick(row.key) },
                )
            }
        }
    }
}
