package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.launcher.PcLauncherAdapters
import com.playfieldportal.feature.settings.viewmodel.ADD_CONSOLE_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.EmulatorOption
import com.playfieldportal.feature.settings.viewmodel.IMPORT_PC_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.PcLauncherRow
import com.playfieldportal.feature.settings.viewmodel.LibraryCardRow
import com.playfieldportal.feature.settings.viewmodel.LibraryManagerUiState
import com.playfieldportal.feature.settings.viewmodel.LibraryManagerViewModel
import com.playfieldportal.feature.settings.viewmodel.LibraryStep

@Composable
fun LibraryManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAddAndroidApps: () -> Unit = {},
    // Open directly into the Import PC Games section (the games context-menu entry point).
    startInImportPc: Boolean = false,
    viewModel: LibraryManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(startInImportPc) {
        if (startInImportPc) viewModel.openImportPcGames()
    }

    // Folder picker — drives both Add Console and Change Directory.
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) viewModel.onDirectoryPicked(uri) else viewModel.onDirectoryPickCancelled()
    }

    LaunchedEffect(state.awaitingDirectoryPick) {
        if (state.awaitingDirectoryPick != null) dirPicker.launch(null)
    }

    // Separate picker for ES-DE folder setup: creates the system-folder structure under the
    // chosen folder (which also becomes the ROM Root).
    val setupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onRomFolderSetupPicked(uri) }

    LaunchedEffect(state.awaitingRomRootSetup) {
        if (state.awaitingRomRootSetup) setupPicker.launch(null)
    }

    // Hierarchical back: collapse a sub-screen first, otherwise close the overlay.
    val handleBack: () -> Unit = { if (!viewModel.onBack()) onBack() }

    when (state.step) {
        LibraryStep.LIST          -> LibraryListContent(state, viewModel, handleBack, modifier)
        LibraryStep.PICK_PLATFORM -> PickPlatformContent(state, viewModel, handleBack, modifier)
        LibraryStep.PICK_EMULATOR -> PickEmulatorContent(state, viewModel, handleBack, modifier)
        LibraryStep.SCAN_PROMPT   -> ScanPromptContent(state, viewModel, handleBack, modifier)
        LibraryStep.CARD_DETAIL   -> CardDetailContent(state, viewModel, handleBack, onAddAndroidApps, modifier)
        LibraryStep.IMPORT_PC     -> ImportPcGamesContent(state, viewModel, handleBack, modifier)
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
                label    = "Set Up ROM Folders (ES-DE)",
                sublabel = "Pick an empty folder — PFP creates the standard ES-DE system folders " +
                    "(gba, snes, psx…) for you to copy games into. No guessing folder names",
                onClick  = { vm.requestRomFolderSetup() },
            )
            SettingsRow(
                label    = "Auto-Detect from ROM Root",
                sublabel = "One scan of your ROM Root: creates a console for every ES-DE system " +
                    "folder (gba, snes, psx…) and loads its games",
                onClick  = { vm.scanRomRoot() },
            )
            SettingsRow(
                label    = "Import PC Games",
                sublabel = "Bring games from Winlator, BannerHub, GameHub Lite & GameNative into " +
                    "your collections",
                focusKey = IMPORT_PC_FOCUS_KEY,
                onClick  = { vm.openImportPcGames() },
            )
            SettingsRow(
                label    = "Scan All Consoles",
                sublabel = when {
                    state.scanningPlatformIds.isNotEmpty() -> "Scanning ${state.scanningPlatformIds.size}…"
                    state.cards.none { it.treeUri != null || it.romDirectory != null } -> "Configure a ROM folder first"
                    else -> "Scan every enabled console's folder"
                },
                onClick  = if (state.cards.any { it.enabled && (it.treeUri != null || it.romDirectory != null) }) ({ vm.scanAllConsoles() }) else null,
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
    onAddAndroidApps: () -> Unit,
    modifier: Modifier,
) {
    val card = state.detailCard
    if (card == null) { LaunchedEffect(Unit) { vm.onBack() }; return }

    var showEmulatorDialog by remember { mutableStateOf(false) }
    var showRemoveConfirm  by remember { mutableStateOf(false) }
    var newExt             by remember(card.platformId) { mutableStateOf("") }
    val isScanning = card.platformId in state.scanningPlatformIds
    // The Android library is curated from installed apps — no ROM folder, emulator, extensions,
    // or scanning. It's managed by the app picker + a removable app list instead.
    val isAndroid = card.platformId == "android"

    SettingsScaffold(title = "Library Manager", subtitle = card.displayName, onBack = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            if (isAndroid) {
                SettingsGroup("Apps")
                SettingsRow(
                    label    = "Add Apps",
                    sublabel = "Pick installed apps to add to this library",
                    onClick  = onAddAndroidApps,
                )
                if (state.androidApps.isEmpty()) {
                    Hint("No apps yet — use Add Apps to pick installed apps for this library.")
                } else {
                    state.androidApps.forEach { app ->
                        SettingsRow(
                            label    = app.label,
                            trailing = { Text("Remove", color = SettingsAccent) },
                            onClick  = { vm.removeApp(app.gameId) },
                        )
                    }
                }
            } else {
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
                SettingsValueRow(label = "Games", value = card.gameCount.toString())

                // ── Supported scan extensions (user-managed) ──────────────────────
                SettingsGroup("Supported Files")
                if (card.extensions.isEmpty()) {
                    Hint("No extensions set — add at least one so scanning can match this console's ROMs.")
                } else {
                    card.extensions.forEach { ext ->
                        SettingsRow(
                            label    = ".$ext",
                            trailing = { Text("Remove", color = SettingsAccent) },
                            onClick  = { vm.removeExtension(card.platformId, ext) },
                        )
                    }
                }
                SettingsTextFieldRow(
                    label         = "Add Extension",
                    value         = newExt,
                    onValueChange = { newExt = it },
                    placeholder   = "e.g. iso, chd, zip",
                    helper        = "Matched case-insensitively when scanning. Press A to type.",
                )
                newExt.trim().lowercase().removePrefix(".").filter { it.isLetterOrDigit() }
                    .takeIf { it.isNotBlank() }
                    ?.let { clean ->
                        SettingsRow(
                            label   = "Add \".$clean\"",
                            onClick = { vm.addExtension(card.platformId, newExt); newExt = "" },
                        )
                    }

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
            }

            if (isAndroid) SettingsGroup("Actions")
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

// ── IMPORT PC GAMES ─────────────────────────────────────────────────────────────
//
// PFP as a PC-game frontend: shows which supported launchers are installed and imports games
// already captured from them (via their shortcut/export flows) into launcher-named collections.
// The Play button always launches back into the source app — PFP is never the PC runtime.
@Composable
private fun ImportPcGamesContent(
    state: LibraryManagerUiState,
    vm: LibraryManagerViewModel,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    // Row whose Add-game-by-ID dialog is open (null = closed).
    var addTarget by remember { mutableStateOf<PcLauncherRow?>(null) }

    // Home-app role / settings request; refresh the Home status when the user returns.
    val homeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshHomeStatus() }

    SettingsScaffold(title = "Library", subtitle = "Import PC Games", onBack = onBack, modifier = modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            state.message?.let { MessageRow(it) { vm.dismissMessage() } }

            // ── Optional Home mode ────────────────────────────────────────────
            SettingsGroup("Home App (optional)")
            SettingsValueRow(
                label    = "Play Field Portal as Home",
                value    = if (state.isHomeLauncher) "Active" else "Set…",
                sublabel = if (state.isHomeLauncher)
                    "Auto-import of games a launcher publishes is available below"
                else
                    "Optional. Set PFP as your Home app to auto-import every game a launcher publishes",
                onClick  = { runCatching { homeLauncher.launch(vm.homeRoleIntent()) } },
            )

            // ── Launchers ─────────────────────────────────────────────────────
            SettingsGroup("PC Launchers")
            state.pcLaunchers.forEach { launcher ->
                when {
                    !launcher.installed -> SettingsValueRow(label = launcher.name, value = "Not installed")
                    launcher.canAddById -> SettingsValueRow(
                        label    = launcher.name,
                        value    = "Add by ID…",
                        sublabel = "Add a game by its ID and launch it from PFP",
                        onClick  = { addTarget = launcher },
                    )
                    else -> SettingsValueRow(
                        label    = launcher.name,
                        value    = "Installed",
                        sublabel = "No add-by-ID support — use its export-to-launcher, or Home auto-import",
                    )
                }
                if (state.isHomeLauncher && launcher.installed) {
                    SettingsRow(
                        label    = "Auto-import all from ${launcher.name}",
                        sublabel = "Pull every game shortcut this launcher publishes",
                        onClick  = { vm.harvestLauncher(launcher) },
                    )
                }
            }

            // ── Captured games ────────────────────────────────────────────────
            SettingsGroup("Found Games (${state.pcGames.size})")
            if (state.pcGames.isEmpty()) {
                Hint(
                    "No PC games captured yet. Add one by ID above, or in your launcher use its " +
                    "\"add shortcut\" / \"export to launcher\" action — captured games appear here."
                )
            } else {
                state.pcGames.forEach { row ->
                    SettingsValueRow(
                        label    = row.title,
                        sublabel = row.launcherName,
                        value    = "Import",
                        onClick  = { vm.importPcGame(row) },
                    )
                }
                SettingsRow(
                    label    = "Import All",
                    sublabel = "Add every found game to a collection named after its launcher",
                    onClick  = { vm.importAllPcGames() },
                )
            }
        }
    }

    addTarget?.let { launcher ->
        AddPcGameDialog(
            launcher = launcher,
            onTest   = { id, source -> vm.testLaunchPcGame(launcher, id, source) },
            onAdd    = { id, title, source -> vm.addPcGameById(launcher, id, title, source); addTarget = null },
            onDismiss = { addTarget = null },
        )
    }
}

@Composable
private fun AddPcGameDialog(
    launcher: PcLauncherRow,
    onTest: (id: String, source: String?) -> Unit,
    onAdd: (id: String, title: String, source: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val adapter = PcLauncherAdapters.forType(launcher.type)
    var id by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(adapter?.sources?.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add ${launcher.name} game") },
        text = {
            Column {
                adapter?.idPrompt?.let { Text(it, color = SettingsSubtext, fontSize = 12.sp) }
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("Game ID") }, singleLine = true)
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title (optional)") }, singleLine = true)
                if (adapter != null && adapter.sources.isNotEmpty()) {
                    Text("Source", color = SettingsSubtext, fontSize = 12.sp)
                    Row {
                        adapter.sources.forEach { s ->
                            Text(
                                text = s,
                                color = if (s == source) SettingsAccent else SettingsSubtext,
                                modifier = Modifier
                                    .clickable { source = s }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(id, title, source) }) { Text("Add") } },
        dismissButton = {
            Row {
                TextButton(onClick = { onTest(id, source) }) { Text("Test Launch") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
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
