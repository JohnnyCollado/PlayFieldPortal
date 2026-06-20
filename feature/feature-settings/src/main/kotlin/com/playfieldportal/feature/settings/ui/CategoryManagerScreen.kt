package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.CREATE_CATEGORY_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerUiState
import com.playfieldportal.feature.settings.viewmodel.CategoryManagerViewModel
import com.playfieldportal.feature.settings.viewmodel.CategoryStep

@Composable
fun CategoryManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoryManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val handleBack: () -> Unit = { if (!viewModel.onBack()) onBack() }

    when (state.step) {
        CategoryStep.LIST      -> CategoryListContent(state, viewModel, handleBack, modifier)
        CategoryStep.PICK_ICON -> PickIconContent(state, viewModel, handleBack, modifier)
        CategoryStep.DETAIL    -> CategoryDetailContent(state, viewModel, handleBack, modifier)
    }

    // Create-name dialog
    if (state.showCreateNameDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.cancelCreateName() },
            title   = { Text("New Category") },
            text    = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.confirmCreateName(text) }) { Text("Next") } },
            dismissButton = { TextButton(onClick = { viewModel.cancelCreateName() }) { Text("Cancel") } },
        )
    }

    // Rename dialog
    state.renameTargetId?.let { id ->
        val current = state.categories.firstOrNull { it.id == id }?.name ?: ""
        var text by remember(id) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { viewModel.cancelRename() },
            title   = { Text("Rename Category") },
            text    = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { viewModel.confirmRename(text) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { viewModel.cancelRename() }) { Text("Cancel") } },
        )
    }
}

// ── LIST ────────────────────────────────────────────────────────────────────────

@Composable
private fun CategoryListContent(
    state: CategoryManagerUiState,
    vm: CategoryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(
        title = "Settings",
        subtitle = "Categories",
        onBack = onBack,
        modifier = modifier,
        restoreFocusKey = state.returnFocusKey,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsGroup("XMB Categories")
            state.categories.forEach { cat ->
                SettingsRow(
                    label    = cat.name + if (!cat.visible) "  (Hidden)" else "",
                    sublabel = "Icon: ${cat.iconKey}" + if (cat.protected) "  ·  Built-in" else "  ·  Custom",
                    focusKey = cat.id,
                    onClick  = { vm.openDetail(cat.id) },
                )
            }

            SettingsGroup("Manage")
            SettingsRow(
                label    = "Create Category",
                sublabel = "Add a new category to the XMB bar",
                focusKey = CREATE_CATEGORY_FOCUS_KEY,
                onClick  = { vm.startCreate() },
            )
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsGroup(state.pendingName ?: state.detail?.name ?: "Icon")
            state.iconOptions.forEach { option ->
                SettingsRow(label = option.label, onClick = { vm.chooseIcon(option.key) })
            }
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
) {
    val cat = state.detail
    if (cat == null) { LaunchedEffect(Unit) { vm.onBack() }; return }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    SettingsScaffold(title = "Categories", subtitle = cat.name, onBack = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsGroup("Edit")
            SettingsRow(label = "Rename Category", onClick = { vm.beginRename(cat.id) })
            SettingsValueRow(label = "Change Icon", value = cat.iconKey, onClick = { vm.startChangeIcon() })
            SettingsToggleRow(
                label    = "Show On Bar",
                sublabel = "Hide or show this category in the XMB",
                checked  = cat.visible,
                onToggle = { vm.toggleVisible(cat.id, it) },
            )

            SettingsGroup("Order")
            SettingsRow(label = "Move Left",  onClick = { vm.move(cat.id, up = true) })
            SettingsRow(label = "Move Right", onClick = { vm.move(cat.id, up = false) })

            if (!cat.protected) {
                SettingsGroup("Danger Zone")
                SettingsRow(
                    label    = "Delete Category",
                    sublabel = "Removes this custom category. Apps are not uninstalled.",
                    trailing = { Text("Delete", color = SettingsAccent) },
                    onClick  = { showDeleteConfirm = true },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title   = { Text("Delete ${cat.name}?") },
            text    = { Text("This removes the category and its app assignments. Apps are not uninstalled.") },
            confirmButton = { TextButton(onClick = { showDeleteConfirm = false; vm.delete(cat.id) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
        )
    }
}
