package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.feature.settings.viewmodel.CREATE_CATEGORY_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerUiState
import com.playfieldportal.feature.settings.viewmodel.CategoryRow
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerViewModel
import com.playfieldportal.feature.settings.viewmodel.CategoryStep

@Composable
fun CategoryManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Closes Settings and lifts the category on the XMB's crossbar, where it is moved live.
    onMoveOnBar: (categoryId: String) -> Unit = {},
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
            title = if (renaming) "Rename Category" else "New Category",
            value = nameText,
            onValueChange = { nameText = it },
            focus = modalFocus,
            onFocusChange = { modalFocus = it },
            onConfirm = confirmName,
            onCancel = cancelName,
            placeholder = "Category name",
            // A new category still has its icon and type to pick, so its name is not yet a save.
            confirmLabel = if (renaming) "Save" else "Next",
            showHints = showHints,
        )
    }

    deleteTarget?.let { target ->
        val kept = if (target.isGamingCategory) "Its games stay in your library." else "Its apps are not uninstalled."
        if (target.customCardCount > 0) {
            // The category's custom memory cards are not deleted with it by the database, so the
            // user decides: keep them in the home column of the same kind, or delete them too.
            val cards = if (target.customCardCount == 1) "1 custom memory card" else "${target.customCardCount} custom memory cards"
            PfpChoiceModal(
                title = "Delete Category",
                message = "\"${target.name}\" will be deleted. $kept What should happen to its $cards?",
                options = listOf(
                    PfpChoiceOption("Move Custom Cards to ${target.cardHomeName}"),
                    PfpChoiceOption("Delete Custom Cards Too"),
                ),
                selected = deleteCardsChoice,
                onSelectedChange = { deleteCardsChoice = it },
                confirmLabel = "Delete",
                focus = modalFocus,
                destructive = true,
                onConfirm = confirmDelete,
                onCancel = { deleteConfirmId = null },
                showHints = showHints,
            )
        } else {
            PfpConfirmModal(
                title = "Delete Category",
                message = "\"${target.name}\" will be deleted. $kept",
                confirmLabel = "Delete",
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
        title = "Settings",
        subtitle = "Categories",
        onBack = onBack,
        modifier = modifier,
        restoreFocusKey = state.returnFocusKey,
        modalOpen = modalOpen,
        onInterceptAction = onInterceptAction,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup("Manage")
            SettingsRow(
                label    = "Create Category",
                sublabel = "Add a new category to the XMB bar",
                focusKey = CREATE_CATEGORY_FOCUS_KEY,
                onClick  = onStartCreate,
            )

            SettingsGroup("XMB Categories")
            state.categories.forEach { cat ->
                SettingsRow(
                    label    = cat.name + if (!cat.visible) "  (Hidden)" else "",
                    sublabel = "Icon: ${cat.iconKey}" + if (cat.protected) "  ·  Built-in" else "  ·  Custom",
                    focusKey = cat.id,
                    onClick  = { vm.openDetail(cat.id) },
                )
            }

        }
    }
}

// ── PICK ICON ─────────────────────────────────────────────────────────────────────

@Composable
private fun PickIconContent(
    state: CategoryManagerUiState,
    vm: CategoryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val subtitle = if (state.pickingIconForCreate) "Choose Icon" else "Change Icon"
    SettingsScaffold(title = "Category", subtitle = subtitle, onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(state.pendingName ?: state.detail?.name ?: "Icon")
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
    SettingsScaffold(title = "Category", subtitle = "Content Type", onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(state.pendingName ?: "Category Type")
            SettingsRow(
                label    = "Gaming",
                sublabel = "For games and custom memory cards",
                onClick  = { vm.chooseType(isGaming = true) },
            )
            SettingsRow(
                label    = "Non-Gaming",
                sublabel = "For apps like Video, Music, Photos",
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
        title = "Categories",
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
            SettingsGroup("Edit")
            SettingsRow(label = "Rename Category", onClick = { onBeginRename(cat) })
            SettingsValueRow(label = "Change Icon", value = cat.iconKey, onClick = { vm.startChangeIcon() })
            if (cat.canHide) {
                SettingsToggleRow(
                    label    = "Show On Bar",
                    sublabel = "Hide or show this category in the XMB",
                    checked  = cat.visible,
                    onToggle = { vm.toggleVisible(cat.id, it) },
                )
            }
            // Read-only: the kind is chosen when the category is created. Flipping it later left
            // games in an app column (and the reverse) with nothing to migrate them.
            SettingsValueRow(
                label    = "Type",
                value    = if (cat.isGamingCategory) "Gaming" else "Apps",
                sublabel = if (cat.isGamingCategory) {
                    "Holds games and custom memory cards. Set when the category is created."
                } else {
                    "Holds apps and custom memory cards. Set when the category is created."
                },
            )

            SettingsGroup("Order")
            // Moved on the crossbar itself, so the user sees the bar they are arranging; a hidden
            // category has no slot there to move. Settings always shows, hidden or not.
            if (cat.visible || cat.id == com.playfieldportal.core.domain.model.BuiltInCategory.SETTINGS) {
                SettingsRow(
                    label    = "Move",
                    sublabel = "Slide it along the XMB with left and right, then confirm to place it",
                    onClick  = { onMoveOnBar(cat.id) },
                )
            } else {
                SettingsRow(
                    label    = "Move",
                    sublabel = "Turn on Show On Bar to move this category",
                )
            }

            if (!cat.protected) {
                SettingsGroup("Danger Zone")
                SettingsRow(
                    label    = "Delete Category",
                    sublabel = "Removes this custom category. Games and apps stay in your library.",
                    trailing = { Text("Delete", color = SettingsAccent) },
                    onClick  = { onAskDelete(cat) },
                )
            }
        }
    }
}
