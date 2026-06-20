package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.ADD_CONSOLE_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.EmulatorOption
import com.playfieldportal.feature.settings.viewmodel.LibraryCardRow
import com.playfieldportal.feature.settings.viewmodel.LibraryManagerUiState
import com.playfieldportal.feature.settings.viewmodel.LibraryManagerViewModel
import com.playfieldportal.feature.settings.viewmodel.LibraryStep

@Composable
fun LibraryManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Folder picker — drives both Add Console and Change Directory.
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.onDirectoryPicked(uri) else viewModel.onDirectoryPickCancelled()
    }

    LaunchedEffect(state.awaitingDirectoryPick) {
        if (state.awaitingDirectoryPick != null) dirPicker.launch(null)
    }

    // Hierarchical back: collapse a sub-screen first, otherwise close the overlay.
    val handleBack: () -> Unit = { if (!viewModel.onBack()) onBack() }

    when (state.step) {
        LibraryStep.LIST          -> LibraryListContent(state, viewModel, handleBack, modifier)
        LibraryStep.PICK_PLATFORM -> PickPlatformContent(state, viewModel, handleBack, modifier)
        LibraryStep.PICK_EMULATOR -> PickEmulatorContent(state, viewModel, handleBack, modifier)
        LibraryStep.SCAN_PROMPT   -> ScanPromptContent(state, viewModel, handleBack, modifier)
        LibraryStep.CARD_DETAIL   -> CardDetailContent(state, viewModel, handleBack, modifier)
    }

    // ── Rename dialog ─────────────────────────────────────────────────────────
    state.renameTargetPlatformId?.let { targetId ->
        val current = state.cards.firstOrNull { it.platformId == targetId }?.displayName ?: ""
        var text by remember(targetId) { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { viewModel.cancelRename() },
            title   = { Text("Rename Memory Card") },
            text    = {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmRename(text) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { viewModel.cancelRename() }) { Text("Cancel") } },
        )
    }
}

// ── LIST ──────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryListContent(
    state: LibraryManagerUiState,
    vm: LibraryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(
        title = "Settings",
        subtitle = "Library Manager",
        onBack = onBack,
        modifier = modifier,
        restoreFocusKey = state.returnFocusKey,
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            SettingsGroup("Consoles")

            if (state.cards.isEmpty()) {
                Hint("No consoles configured. Add a console to create a Memory Card that appears inside Games.")
            } else {
                state.cards.forEach { card ->
                    SettingsRow(
                        label    = card.displayName + if (!card.enabled) "  (Hidden)" else "",
                        sublabel = cardSublabel(card),
                        focusKey = card.platformId,
                        trailing = { if (card.pinned) Text("PINNED", color = SettingsAccent) },
                        onClick  = { vm.openCardDetail(card.platformId) },
                    )
                }
            }

            SettingsGroup("Manage")

            SettingsRow(
                label    = "Add Console",
                sublabel = "Pick a platform, choose its ROM folder, assign an emulator",
                focusKey = ADD_CONSOLE_FOCUS_KEY,
                onClick  = { vm.startAddConsole() },
            )
            SettingsRow(
                label    = "Scan All Consoles",
                sublabel = when {
                    state.scanningPlatformIds.isNotEmpty() -> "Scanning ${state.scanningPlatformIds.size}…"
                    state.cards.none { it.romDirectory != null } -> "Configure a ROM directory first"
                    else -> "Scan every enabled console's directory"
                },
                onClick  = if (state.cards.any { it.enabled && it.romDirectory != null }) ({ vm.scanAllConsoles() }) else null,
            )

            state.message?.let { MessageRow(it) { vm.dismissMessage() } }
        }
    }
}

// ── PICK PLATFORM ───────────────────────────────────────────────────────────────

@Composable
private fun PickPlatformContent(
    state: LibraryManagerUiState,
    vm: LibraryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(title = "Add Console", subtitle = "Choose Platform", onBack = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsGroup("Supported Platforms")
            state.platformOptions.forEach { option ->
                SettingsRow(
                    label    = option.name,
                    sublabel = option.shortName,
                    onClick  = { vm.onPlatformChosen(option) },
                )
            }
        }
    }
}

// ── PICK EMULATOR ───────────────────────────────────────────────────────────────

@Composable
private fun PickEmulatorContent(
    state: LibraryManagerUiState,
    vm: LibraryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(title = "Add Console", subtitle = "Assign Emulator", onBack = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsGroup(state.pendingPlatformName ?: "Emulator")
            if (state.emulatorOptions.all { it.id == null }) {
                Hint("No installed emulators detected for this platform. You can assign one later from the console's detail screen.")
            }
            state.emulatorOptions.forEach { option ->
                SettingsRow(label = option.name, onClick = { vm.onEmulatorChosen(option) })
            }
        }
    }
}

// ── SCAN PROMPT ─────────────────────────────────────────────────────────────────

@Composable
private fun ScanPromptContent(
    state: LibraryManagerUiState,
    vm: LibraryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(title = "Add Console", subtitle = "Scan Now?", onBack = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsGroup(state.pendingPlatformName ?: "New Console")
            SettingsValueRow(
                label = "ROM Directory",
                value = state.pendingDirectory?.substringAfterLast('/') ?: "Not set",
                sublabel = state.pendingDirectory,
            )
            SettingsRow(
                label    = "Scan Now",
                sublabel = "Create the Memory Card and scan its folder immediately",
                onClick  = { vm.confirmAddConsole(scanNow = true) },
            )
            SettingsRow(
                label    = "Add Without Scanning",
                sublabel = "Create the Memory Card now, scan later",
                onClick  = { vm.confirmAddConsole(scanNow = false) },
            )
        }
    }
}

// ── CARD DETAIL ─────────────────────────────────────────────────────────────────

@Composable
private fun CardDetailContent(
    state: LibraryManagerUiState,
    vm: LibraryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val card = state.detailCard
    if (card == null) { LaunchedEffect(Unit) { vm.onBack() }; return }

    var showEmulatorDialog by remember { mutableStateOf(false) }
    var showRemoveConfirm  by remember { mutableStateOf(false) }
    val isScanning = card.platformId in state.scanningPlatformIds

    SettingsScaffold(title = "Library Manager", subtitle = card.displayName, onBack = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            SettingsGroup("Library")
            SettingsValueRow(
                label    = "ROM Directory",
                value    = card.romDirectory?.substringAfterLast('/') ?: "Not set",
                sublabel = card.romDirectory ?: "Choose the folder this console scans",
                onClick  = { vm.requestChangeDirectory(card.platformId) },
            )
            SettingsValueRow(
                label   = "Emulator",
                value   = card.emulatorName ?: "None",
                onClick = { vm.loadEmulatorOptionsForDetail(); showEmulatorDialog = true },
            )
            SettingsValueRow(
                label    = "Supported Files",
                value    = card.extensions.joinToString(", ").ifBlank { "—" },
            )
            SettingsValueRow(label = "Games", value = card.gameCount.toString())

            SettingsGroup("Actions")
            SettingsRow(
                label    = "Scan This Console",
                sublabel = when {
                    isScanning -> "Scanning…"
                    card.romDirectory == null -> "ROM directory not configured"
                    else -> "Scan only this console's folder"
                },
                onClick  = if (!isScanning && card.romDirectory != null) ({ vm.scanConsole(card.platformId) }) else null,
            )
            SettingsRow(label = "Rename Memory Card", onClick = { vm.beginRename(card.platformId) })
            SettingsToggleRow(
                label    = "Show In Games",
                sublabel = "Enable or hide this Memory Card",
                checked  = card.enabled,
                onToggle = { vm.toggleEnabled(card.platformId, it) },
            )
            SettingsToggleRow(
                label    = "Pin To Top",
                checked  = card.pinned,
                onToggle = { vm.togglePinned(card.platformId, it) },
            )
            SettingsRow(label = "Move Up",   onClick = { vm.moveCard(card.platformId, up = true) })
            SettingsRow(label = "Move Down", onClick = { vm.moveCard(card.platformId, up = false) })

            SettingsGroup("Danger Zone")
            SettingsRow(
                label    = "Remove Memory Card",
                sublabel = "Removes this console and its games. ROM files are not deleted.",
                trailing = { Text("Remove", color = SettingsAccent) },
                onClick  = { showRemoveConfirm = true },
            )
        }
    }

    if (showEmulatorDialog) {
        EmulatorPickerDialog(
            options    = state.emulatorOptions,
            onSelect   = { vm.setEmulatorForDetail(it); showEmulatorDialog = false },
            onDismiss  = { showEmulatorDialog = false },
        )
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            title   = { Text("Remove ${card.displayName}?") },
            text    = { Text("This removes the console and its scanned games from the library. ROM files on disk are not deleted.") },
            confirmButton = { TextButton(onClick = { showRemoveConfirm = false; vm.removeCard(card.platformId) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { showRemoveConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmulatorPickerDialog(
    options: List<EmulatorOption>,
    onSelect: (EmulatorOption) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title   = { Text("Set Emulator") },
        text    = {
            Column {
                options.forEach { option ->
                    SettingsRow(label = option.name, onClick = { onSelect(option) })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ── Shared bits ─────────────────────────────────────────────────────────────────

private fun cardSublabel(card: LibraryCardRow): String = buildString {
    append(card.romDirectory ?: "No ROM directory")
    append("  ·  ${card.gameCount} game${if (card.gameCount == 1) "" else "s"}")
}

@Composable
private fun Hint(text: String) {
    Text(text = text, color = SettingsSubtext, modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp))
}

@Composable
private fun MessageRow(message: String, onDismiss: () -> Unit) {
    SettingsRow(
        label    = message,
        sublabel = "Tap to dismiss",
        trailing = { Text("✕", color = SettingsAccent, fontWeight = FontWeight.Bold) },
        onClick  = onDismiss,
    )
}
