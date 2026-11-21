package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.data.repository.CollectionsOnDelete
import com.playfieldportal.core.ui.components.PfpChoiceModal
import com.playfieldportal.core.ui.components.PfpChoiceOption
import com.playfieldportal.core.ui.components.PfpConfirmModal
import com.playfieldportal.core.ui.components.PfpModalFocus
import com.playfieldportal.core.ui.components.PfpModalNav
import com.playfieldportal.core.ui.components.PfpTextEntryModal
import com.playfieldportal.core.ui.icons.CategoryIconGlyph
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.CustomIconSurface
import com.playfieldportal.core.ui.icons.FALLBACK_CATEGORY_ICON
import com.playfieldportal.core.ui.icons.LocalXmbIcons
import com.playfieldportal.core.ui.icons.UserCategoryIconKeys
import com.playfieldportal.core.ui.icons.categoryIconFor
import com.playfieldportal.core.ui.motion.LocalIconFocused
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.CREATE_CATEGORY_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerUiState
import com.playfieldportal.feature.settings.viewmodel.CategoryRow
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerViewModel
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerTarget
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerTargetAction
import com.playfieldportal.feature.settings.viewmodel.CategoryStep

@Composable
fun CategoryManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Closes Settings and lifts the category on the XMB's crossbar, where it is moved live.
    onMoveOnBar: (categoryId: String) -> Unit = {},
    // The XMB's category menu: open on one category with its rename or icon picker already up.
    initialTarget: CategoryManagerTarget? = null,
    onTargetConsumed: () -> Unit = {},
    viewModel: CategoryManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    // The shared modals' state: the name being typed, the cursor inside the modal, and the
    // category a delete is being confirmed for. Which name modal is up stays the view model's.
    var nameText by remember { mutableStateOf("") }
    var modalFocus by remember { mutableStateOf(PfpModalFocus.FIELD) }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }
    val deleteTarget = state.categories.firstOrNull { it.id == deleteConfirmId }
    // The answer for the category's custom memory cards: 0 keeps them (the default), 1 deletes
    // them too. Only asked when the category has some.
    var deleteCardsChoice by remember { mutableStateOf(0) }

    val handleBack: () -> Unit = {
        if (deleteConfirmId != null) deleteConfirmId = null else if (!viewModel.onBack()) onBack()
    }

    val renaming = state.renameTargetId != null
    val nameModalOpen = state.showCreateNameDialog || renaming
    val nameConfirmEnabled = PfpModalNav.textEntryConfirmEnabled(nameText, error = null, maxLength = null)
    val showHints = LocalSettingsShowControllerHint.current
    val menuSounds = LocalMenuSounds.current

    val startCreate: () -> Unit = {
        nameText = ""
        modalFocus = PfpModalFocus.FIELD
        viewModel.startCreate()
    }
    val beginRename: (CategoryRow) -> Unit = { cat ->
        nameText = cat.name
        modalFocus = PfpModalFocus.FIELD
        viewModel.beginRename(cat.id)
    }
    val askDelete: (CategoryRow) -> Unit = { cat ->
        // Opens on Cancel: a stray second press must not delete the category.
        modalFocus = PfpModalNav.initialConfirmFocus(destructive = true)
        deleteCardsChoice = 0
        deleteConfirmId = cat.id
    }
    // The categories arrive a beat after the screen does; the target is applied once its row is there.
    val targetRow = initialTarget?.let { target -> state.categories.firstOrNull { it.id == target.categoryId } }
    LaunchedEffect(initialTarget, targetRow != null) {
        val target = initialTarget ?: return@LaunchedEffect
        val row = targetRow ?: return@LaunchedEffect
        if (target.action == CategoryManagerTargetAction.RENAME) beginRename(row)
        viewModel.openTarget(target)
        onTargetConsumed()
    }
    val confirmName: () -> Unit = {
        if (renaming) viewModel.confirmRename(nameText) else viewModel.confirmCreateName(nameText)
    }
    val cancelName: () -> Unit = {
        if (renaming) viewModel.cancelRename() else viewModel.cancelCreateName()
    }
    val confirmDelete: () -> Unit = {
        deleteConfirmId?.let { id ->
            deleteConfirmId = null
            viewModel.delete(
                id,
                if (deleteCardsChoice == 1) CollectionsOnDelete.DELETE else CollectionsOnDelete.MOVE,
            )
        }
    }

    // A modal is a hard input boundary: while one is up, nothing behind it sees a press.
    val modalOpen = nameModalOpen || deleteTarget != null
    val interceptModal: (GamepadAction) -> Boolean = { action ->
        when {
            nameModalOpen -> {
                PfpModalNav.handle(
                    action = action,
                    focus = modalFocus,
                    hasField = true,
                    confirmEnabled = nameConfirmEnabled,
                    sounds = menuSounds,
                    onFocusChange = { modalFocus = it },
                    onConfirm = confirmName,
                    onCancel = cancelName,
                )
                true
            }
            deleteTarget != null -> {
                PfpModalNav.handleChoice(
                    action = action,
                    focus = modalFocus,
                    selected = deleteCardsChoice,
                    // No custom cards means no question: up / down then have nothing to move.
                    optionCount = if (deleteTarget.customCardCount > 0) 2 else 1,
                    sounds = menuSounds,
                    onFocusChange = { modalFocus = it },
                    onSelectedChange = { deleteCardsChoice = it },
                    onConfirm = confirmDelete,
                    onCancel = { deleteConfirmId = null },
                )
                true
            }
            else -> false
        }
    }

    when (state.step) {
        CategoryStep.LIST      -> CategoryListContent(state, viewModel, handleBack, modifier, startCreate, modalOpen, interceptModal)
        CategoryStep.PICK_ICON -> PickIconContent(state, viewModel, handleBack, modifier)
        CategoryStep.PICK_TYPE -> PickTypeContent(state, viewModel, handleBack, modifier)
        CategoryStep.DETAIL    ->
            CategoryDetailContent(state, viewModel, handleBack, modifier, beginRename, askDelete, modalOpen, interceptModal, onMoveOnBar)
    }

    // Name entry (new or rename)
    if (nameModalOpen) {
        PfpTextEntryModal(
            title = stringResource(if (renaming) R.string.lib_cat_rename_title else R.string.lib_cat_new_title),
            value = nameText,
            onValueChange = { nameText = it },
            focus = modalFocus,
            onFocusChange = { modalFocus = it },
            onConfirm = confirmName,
            onCancel = cancelName,
            placeholder = stringResource(R.string.lib_cat_name_placeholder),
            // A new category still has its icon and type to pick, so its name is not yet a save.
            confirmLabel = stringResource(if (renaming) R.string.lib_cat_save else R.string.lib_cat_next),
            showHints = showHints,
        )
    }

    deleteTarget?.let { target ->
        if (target.customCardCount > 0) {
            // The category's custom memory cards are not deleted with it by the database, so the
            // user decides: keep them in the home column of the same kind, or delete them too.
            PfpChoiceModal(
                title = stringResource(R.string.lib_cat_delete_title),
                message = pluralStringResource(
                    if (target.isGamingCategory) R.plurals.lib_cat_delete_cards_games_message else R.plurals.lib_cat_delete_cards_apps_message,
                    target.customCardCount, target.name, target.customCardCount,
                ),
                options = listOf(
                    PfpChoiceOption(stringResource(R.string.lib_cat_delete_move_cards, target.cardHomeName.asString())),
                    PfpChoiceOption(stringResource(R.string.lib_cat_delete_cards_too)),
                ),
                selected = deleteCardsChoice,
                onSelectedChange = { deleteCardsChoice = it },
                confirmLabel = stringResource(R.string.lib_cat_delete),
                focus = modalFocus,
                destructive = true,
                onConfirm = confirmDelete,
                onCancel = { deleteConfirmId = null },
                showHints = showHints,
            )
        } else {
            PfpConfirmModal(
                title = stringResource(R.string.lib_cat_delete_title),
                message = stringResource(
                    if (target.isGamingCategory) R.string.lib_cat_delete_games_message else R.string.lib_cat_delete_apps_message,
                    target.name,
                ),
                confirmLabel = stringResource(R.string.lib_cat_delete),
                focus = modalFocus,
                destructive = true,
                onConfirm = confirmDelete,
                onCancel = { deleteConfirmId = null },
                showHints = showHints,
            )
        }
    }
}

// ── LIST ────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryListContent(
    state: CategoryManagerUiState,
    vm: CategoryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
    onStartCreate: () -> Unit,
    modalOpen: Boolean,
    onInterceptAction: (GamepadAction) -> Boolean,
) {
    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.lib_cat_title),
        onBack = onBack,
        modifier = modifier,
        restoreFocusKey = state.returnFocusKey,
        modalOpen = modalOpen,
        onInterceptAction = onInterceptAction,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(stringResource(R.string.lib_cat_group_manage))
            SettingsRow(
                label    = stringResource(R.string.lib_cat_create),
                sublabel = stringResource(R.string.lib_cat_create_sub),
                focusKey = CREATE_CATEGORY_FOCUS_KEY,
                onClick  = onStartCreate,
            )

            // Set after a create whose image could not be kept (the category itself exists).
            state.message?.let { message ->
                Text(
                    text = message.asString(),
                    color = SettingsText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                )
            }

            SettingsGroup(stringResource(R.string.lib_cat_group_xmb))
            state.categories.forEach { cat ->
                SettingsRow(
                    label    = if (cat.visible) cat.name else stringResource(R.string.lib_cat_row_hidden, cat.name),
                    sublabel = stringResource(
                        if (cat.protected) R.string.lib_cat_row_sub_builtin else R.string.lib_cat_row_sub_custom,
                        cat.iconLabel.asString(),
                    ),
                    focusKey = cat.id,
                    onClick  = { vm.openDetail(cat.id) },
                )
            }

        }
    }
}

// ── PICK ICON ─────────────────────────────────────────────────────────────────────

// The Customize XMB Icons overlay's accepted set: the import gate's stills plus GIF.
internal val DEVICE_IMAGE_MIME = arrayOf(
    "image/png",
    "image/jpeg",
    "image/webp",
    "image/gif",
    "image/bmp",
    "image/heif",
    "image/heic",
)

@Composable
private fun PickIconContent(
    state: CategoryManagerUiState,
    vm: CategoryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val subtitle = stringResource(if (state.pickingIconForCreate) R.string.lib_cat_choose_icon else R.string.lib_cat_change_icon_title)
    // The create flow stores to the draft key until the category has an id; Change Icon uses the
    // category's own key, which is null for built-ins and ids outside the key pattern — those are
    // never offered a device image.
    val detail = state.detail
    val imageKey = if (state.pickingIconForCreate) UserCategoryIconKeys.DRAFT_KEY else detail?.deviceImageKey
    val hasImage = if (state.pickingIconForCreate) state.pendingHasImage else detail?.hasImage == true
    val fallbackLabel = categoryIconFor(
        (if (state.pickingIconForCreate) state.pendingIconKey else detail?.iconKey) ?: FALLBACK_CATEGORY_ICON.key,
    ).label

    // The store's gate decides by the resolver's MIME, not by file extension.
    val contentResolver = LocalContext.current.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.onDeviceImagePicked(it, contentResolver.getType(it)) }
    }
    var previewFocused by remember { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.lib_cat_scaffold_title), subtitle = subtitle, onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            if (imageKey != null) {
                SettingsGroup(stringResource(R.string.lib_cat_group_device))
                if (!hasImage) {
                    SettingsRow(
                        label    = stringResource(R.string.lib_cat_choose_image),
                        sublabel = stringResource(R.string.lib_cat_choose_image_sub),
                        onClick  = { picker.launch(DEVICE_IMAGE_MIME) },
                    )
                } else {
                    val icon = LocalXmbIcons.current[imageKey]
                    // Read-only preview row: it plays under Animated Images while the cursor is on it.
                    SettingsRow(
                        label    = stringResource(R.string.lib_cat_your_image_row),
                        sublabel = stringResource(if (icon is CustomIcon.Animated) R.string.lib_cat_image_animated_in_use else R.string.lib_cat_image_in_use),
                        onFocusChangedExternal = { previewFocused = it },
                        trailing = {
                            if (icon != null) {
                                CompositionLocalProvider(LocalIconFocused provides previewFocused) {
                                    CustomIconSurface(icon, contentDescription = stringResource(R.string.lib_cat_your_image), modifier = Modifier.size(40.dp))
                                }
                            }
                        },
                    )
                    SettingsRow(
                        label    = stringResource(R.string.lib_cat_replace_image),
                        sublabel = stringResource(R.string.lib_cat_replace_image_sub),
                        onClick  = { picker.launch(DEVICE_IMAGE_MIME) },
                    )
                    SettingsRow(
                        label    = stringResource(R.string.lib_cat_remove_image),
                        sublabel = stringResource(R.string.lib_cat_remove_image_sub, fallbackLabel),
                        onClick  = { vm.removeDeviceImage() },
                    )
                }
                // A rejected file: the store's own message; the previous icon stays.
                state.message?.let { message ->
                    Text(
                        text = message.asString(),
                        color = SettingsText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                    )
                }
            }
            SettingsGroup(
                stringResource(
                    R.string.lib_cat_group_builtin,
                    state.pendingName ?: detail?.name ?: stringResource(R.string.lib_cat_icon_fallback),
                ),
            )
            state.iconOptions.forEach { option ->
                SettingsRow(
                    label    = option.label,
                    trailing = { CategoryIconGlyph(option.key, contentDescription = option.label, modifier = Modifier.size(40.dp)) },
                    onClick  = { vm.chooseIcon(option.key) },
                )
            }
        }
    }
}

// ── PICK TYPE ──────────────────────────────────────────────────────────────────────

@Composable
private fun PickTypeContent(
    state: CategoryManagerUiState,
    vm: CategoryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(title = stringResource(R.string.lib_cat_scaffold_title), subtitle = stringResource(R.string.lib_cat_content_type), onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(state.pendingName ?: stringResource(R.string.lib_cat_type_group))
            SettingsRow(
                label    = stringResource(R.string.lib_cat_type_gaming),
                sublabel = stringResource(R.string.lib_cat_type_gaming_sub),
                onClick  = { vm.chooseType(isGaming = true) },
            )
            SettingsRow(
                label    = stringResource(R.string.lib_cat_type_non_gaming),
                sublabel = stringResource(R.string.lib_cat_type_non_gaming_sub),
                onClick  = { vm.chooseType(isGaming = false) },
            )
        }
    }
}

// ── DETAIL ──────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryDetailContent(
    state: CategoryManagerUiState,
    vm: CategoryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
    onBeginRename: (CategoryRow) -> Unit,
    onAskDelete: (CategoryRow) -> Unit,
    modalOpen: Boolean,
    onInterceptAction: (GamepadAction) -> Boolean,
    onMoveOnBar: (categoryId: String) -> Unit,
) {
    val cat = state.detail
    if (cat == null) { LaunchedEffect(Unit) { vm.onBack() }; return }

    SettingsScaffold(
        title = stringResource(R.string.lib_cat_title),
        subtitle = cat.name,
        onBack = onBack,
        modifier = modifier,
        modalOpen = modalOpen,
        onInterceptAction = onInterceptAction,
    ) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(stringResource(R.string.lib_cat_group_edit))
            SettingsRow(label = stringResource(R.string.lib_cat_rename), onClick = { onBeginRename(cat) })
            // SettingsValueRow has no slot for a thumbnail, so the value text and the icon share
            // SettingsRow's trailing. The image plays only while the cursor is on this row.
            var iconRowFocused by remember { mutableStateOf(false) }
            SettingsRow(
                label    = stringResource(R.string.lib_cat_change_icon),
                onFocusChangedExternal = { iconRowFocused = it },
                onClick  = { vm.startChangeIcon() },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(cat.iconLabel.asString(), color = SettingsText, fontSize = 13.sp)
                        val image = cat.deviceImageKey?.takeIf { cat.hasImage }?.let { LocalXmbIcons.current[it] }
                        if (image != null) {
                            CompositionLocalProvider(LocalIconFocused provides iconRowFocused) {
                                CustomIconSurface(image, contentDescription = cat.iconLabel.asString(), modifier = Modifier.size(32.dp))
                            }
                        } else {
                            CategoryIconGlyph(cat.iconKey, contentDescription = cat.iconLabel.asString(), modifier = Modifier.size(32.dp))
                        }
                    }
                },
            )
            if (cat.canHide) {
                SettingsToggleRow(
                    label    = stringResource(SettingsLabels.SHOW_ON_BAR_RES),
                    sublabel = stringResource(R.string.lib_cat_show_on_bar_sub),
                    checked  = cat.visible,
                    onToggle = { vm.toggleVisible(cat.id, it) },
                )
            }
            // Read-only: the kind is chosen when the category is created. Flipping it later left
            // games in an app column (and the reverse) with nothing to migrate them.
            SettingsValueRow(
                label    = stringResource(R.string.lib_cat_type),
                value    = stringResource(if (cat.isGamingCategory) R.string.lib_cat_type_gaming else R.string.lib_cat_type_apps),
                sublabel = stringResource(if (cat.isGamingCategory) R.string.lib_cat_type_gaming_note else R.string.lib_cat_type_apps_note),
            )

            SettingsGroup(stringResource(R.string.lib_cat_group_order))
            // Moved on the crossbar itself, so the user sees the bar they are arranging; a hidden
            // category has no slot there to move. Settings always shows, hidden or not.
            if (cat.visible || cat.id == com.playfieldportal.core.domain.model.BuiltInCategory.SETTINGS) {
                SettingsRow(
                    label    = stringResource(R.string.lib_cat_move),
                    sublabel = stringResource(R.string.lib_cat_move_sub),
                    onClick  = { onMoveOnBar(cat.id) },
                )
            } else {
                SettingsRow(
                    label    = stringResource(R.string.lib_cat_move),
                    sublabel = stringResource(R.string.lib_cat_move_disabled_sub),
                )
            }

            if (!cat.protected) {
                SettingsGroup(stringResource(R.string.lib_cat_group_danger))
                SettingsRow(
                    label    = stringResource(R.string.lib_cat_delete_row),
                    sublabel = stringResource(R.string.lib_cat_delete_row_sub),
                    trailing = { Text(stringResource(R.string.lib_cat_delete), color = SettingsAccent) },
                    onClick  = { onAskDelete(cat) },
                )
            }
        }
    }
}
