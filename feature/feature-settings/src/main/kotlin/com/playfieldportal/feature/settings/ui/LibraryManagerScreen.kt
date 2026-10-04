package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.data.repository.Xbox360Emulator
import com.playfieldportal.core.ui.components.PfpModalSpec
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.achievement.LocalSteamConvertPanel
import com.playfieldportal.core.ui.achievement.LocalSteamConvertRow
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamConvertPickerController
import com.playfieldportal.feature.launcher.PcLauncherAdapters
import com.playfieldportal.feature.settings.viewmodel.ADD_CONSOLE_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.EmulatorOption
import com.playfieldportal.feature.settings.viewmodel.IMPORT_PC_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.LibraryAppRow
import com.playfieldportal.feature.settings.viewmodel.LibraryCardRow
import com.playfieldportal.feature.settings.viewmodel.LibraryManagerUiState
import com.playfieldportal.feature.settings.viewmodel.LibraryManagerViewModel
import com.playfieldportal.feature.settings.viewmodel.LibraryStep
import com.playfieldportal.feature.settings.viewmodel.LocalSteamFolderRow
import com.playfieldportal.feature.settings.viewmodel.PcGameRow
import com.playfieldportal.feature.settings.viewmodel.PcLauncherRow
import com.playfieldportal.feature.settings.viewmodel.PlatformOption
import com.playfieldportal.feature.settings.viewmodel.RootFolderRow

@Composable
fun LibraryManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAddAndroidApps: () -> Unit = {},
    // Open directly into the standalone Windows Games screen.
    startInImportPc: Boolean = false,
    startAtWindowsCard: Boolean = false,
    viewModel: LibraryManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val convertPicker by viewModel.convertPicker.collectAsState()

    LaunchedEffect(startInImportPc, startAtWindowsCard) {
        when {
            startInImportPc -> viewModel.openImportPcGames()
            startAtWindowsCard -> viewModel.openWindowsGamesRoot()
        }
    }

    // Picker for ES-DE folder setup: creates the system-folder structure under the
    // chosen folder (which also becomes the ROM Root).
    val setupPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.onRomFolderSetupPicked(uri) }

    LaunchedEffect(state.awaitingRomRootSetup) {
        if (state.awaitingRomRootSetup) setupPicker.launch(null)
    }

    LibraryManagerContent(
        state = state,
        convertPicker = convertPicker,
        onBack = { if (!viewModel.onBack()) onBack() },
        onAddAndroidApps = onAddAndroidApps,
        onAddRomRoot = { it?.let { viewModel.addRomRoot(it) } },
        onRelinkRomRoot = { _, uri ->
            if (uri != null) viewModel.onRomRootRelinkPicked(uri)
        },
        onBeginRelink = { viewModel.beginRelinkRomRoot(it.treeUri) ?: Uri.EMPTY },
        onRemoveRomRoot = { viewModel.removeRomRoot(it.treeUri) },
        onScanRomRoot = { viewModel.scanRomRoot() },
        onOpenCardDetail = { viewModel.openCardDetail(it) },
        onStartAddConsole = { viewModel.startAddConsole() },
        onRequestRomFolderSetup = { viewModel.requestRomFolderSetup() },
        onScanAllConsoles = { viewModel.scanAllConsoles(it) },
        onDismissMessage = { viewModel.dismissMessage() },
        onPlatformChosen = { viewModel.onPlatformChosen(it) },
        onEmulatorChosen = { viewModel.onEmulatorChosen(it) },
        onConfirmAddConsole = { viewModel.confirmAddConsole(it) },
        onLoadEmulatorOptions = { onLoaded -> viewModel.loadEmulatorOptionsForDetail(onLoaded) },
        onRemoveExtension = { p, e -> viewModel.removeExtension(p, e) },
        onAddExtension = { p, e -> viewModel.addExtension(p, e) },
        onScanConsole = { viewModel.scanConsole(it) },
        onBeginRename = { viewModel.beginRename(it) },
        onCancelRename = { viewModel.cancelRename() },
        onConfirmRename = { viewModel.confirmRename(it) },
        onToggleEnabled = { p, e -> viewModel.toggleEnabled(p, e) },
        onTogglePinned = { p, pin -> viewModel.togglePinned(p, pin) },
        onMoveCard = { p, up -> viewModel.moveCard(p, up) },
        onRemoveCard = { viewModel.removeCard(it) },
        onSetEmulatorForDetail = { viewModel.setEmulatorForDetail(it) },
        onOpenImportPcGames = { viewModel.openImportPcGames() },
        onSetVita3KFolder = { viewModel.setVita3KFolder(it) },
        onSetPs3DataFolder = { viewModel.setPs3DataFolder(it) },
        onSetXbox360DataFolder = { emulator, uri -> viewModel.setXbox360DataFolder(emulator, uri) },
        onScanVitaGames = { viewModel.scanVitaGames() },
        onRemoveApp = { viewModel.removeApp(it) },
        onRefreshHomeStatus = { viewModel.refreshHomeStatus() },
        onScanPcGamesFolder = { viewModel.scanPcGamesFolder(it) },
        onExportManualPcGames = { viewModel.exportManualPcGames() },
        onImportPcGame = { viewModel.importPcGame(it) },
        onImportAllPcGames = { viewModel.importAllPcGames() },
        onTestLaunchPcGame = { l, id, s -> viewModel.testLaunchPcGame(l, id, s) },
        onAddPcGameById = { l, id, t, s -> viewModel.addPcGameById(l, id, t, s) },
        onConvertToggle = { viewModel.onConvertToggle(it) },
        onConvertSelectAllNone = { viewModel.onConvertSelectAllNone() },
        onConvertConfirm = { viewModel.onConvertConfirm() },
        onConvertSkip = { viewModel.onConvertSkip() },
        onConvertCancel = { viewModel.onConvertCancel() },
        onConvertGamepadAction = { viewModel.onConvertGamepadAction(it) },
        onBatchMatchLocalGames = { viewModel.batchMatchLocalGames(it) },
        onForgetLocalSteamFolder = { viewModel.forgetLocalSteamFolder(it) },
        onSetGoldbergInstaller = { viewModel.setGoldbergInstallerEnabled(it) },
        onCycleUnknownLauncher = { viewModel.cycleUnknownLauncher(it) },
        onShowHiddenUnknownLaunchers = { viewModel.showHiddenUnknownLaunchers() },
        homeRoleIntentProvider = { viewModel.homeRoleIntent() },
        modifier = modifier
    )
}

@Composable
private fun LibraryManagerContent(
    state: LibraryManagerUiState,
    convertPicker: LocalSteamConvertPickerController.Picker?,
    onBack: () -> Unit,
    onAddAndroidApps: () -> Unit,
    onAddRomRoot: (Uri?) -> Unit,
    onRelinkRomRoot: (RootFolderRow, Uri?) -> Unit,
    onBeginRelink: (RootFolderRow) -> Uri,
    onRemoveRomRoot: (RootFolderRow) -> Unit,
    onScanRomRoot: () -> Unit,
    onOpenCardDetail: (String) -> Unit,
    onStartAddConsole: () -> Unit,
    onRequestRomFolderSetup: () -> Unit,
    onScanAllConsoles: (removeMissing: Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    onPlatformChosen: (PlatformOption) -> Unit,
    onEmulatorChosen: (EmulatorOption) -> Unit,
    onConfirmAddConsole: (scanNow: Boolean) -> Unit,
    onLoadEmulatorOptions: (onLoaded: (List<EmulatorOption>) -> Unit) -> Unit,
    onRemoveExtension: (platformId: String, ext: String) -> Unit,
    onAddExtension: (platformId: String, ext: String) -> Unit,
    onScanConsole: (platformId: String) -> Unit,
    onBeginRename: (platformId: String) -> Unit,
    onCancelRename: () -> Unit,
    onConfirmRename: (String) -> Unit,
    onToggleEnabled: (platformId: String, enabled: Boolean) -> Unit,
    onTogglePinned: (platformId: String, pinned: Boolean) -> Unit,
    onMoveCard: (platformId: String, up: Boolean) -> Unit,
    onRemoveCard: (platformId: String) -> Unit,
    onSetEmulatorForDetail: (EmulatorOption) -> Unit,
    onOpenImportPcGames: () -> Unit,
    onSetVita3KFolder: (Uri) -> Unit,
    onSetPs3DataFolder: (Uri) -> Unit,
    onSetXbox360DataFolder: (Xbox360Emulator, Uri) -> Unit,
    onScanVitaGames: () -> Unit,
    onRemoveApp: (Long) -> Unit,
    onRefreshHomeStatus: () -> Unit,
    onScanPcGamesFolder: (Uri) -> Unit,
    onExportManualPcGames: () -> Unit,
    onImportPcGame: (PcGameRow) -> Unit,
    onImportAllPcGames: () -> Unit,
    onTestLaunchPcGame: (PcLauncherRow, String, String?) -> Unit,
    onAddPcGameById: (PcLauncherRow, String, String, String?) -> Unit,
    onConvertToggle: (Int) -> Unit,
    onConvertSelectAllNone: () -> Unit,
    onConvertConfirm: () -> Unit,
    onConvertSkip: () -> Unit,
    onConvertCancel: () -> Unit,
    onConvertGamepadAction: (GamepadAction) -> Boolean,
    onBatchMatchLocalGames: (Uri) -> Unit,
    onForgetLocalSteamFolder: (appId: String) -> Unit,
    onSetGoldbergInstaller: (Boolean) -> Unit,
    onCycleUnknownLauncher: (packageName: String) -> Unit,
    onShowHiddenUnknownLaunchers: () -> Unit,
    homeRoleIntentProvider: () -> android.content.Intent?,
    modifier: Modifier = Modifier,
) {
    val handleBack: () -> Unit = onBack

    when (state.step) {
        LibraryStep.LIST          -> LibraryListContent(state, handleBack, onAddRomRoot, onRelinkRomRoot, onBeginRelink, onRemoveRomRoot, onScanRomRoot, onOpenCardDetail, onStartAddConsole, onRequestRomFolderSetup, onScanAllConsoles, onDismissMessage, modifier)
        LibraryStep.PICK_PLATFORM -> PickPlatformContent(state, onBack = handleBack, onPlatformChosen = onPlatformChosen, modifier = modifier)
        LibraryStep.PICK_EMULATOR -> PickEmulatorContent(state, onBack = handleBack, onEmulatorChosen = onEmulatorChosen, modifier = modifier)
        LibraryStep.SCAN_PROMPT   -> ScanPromptContent(state, onBack = handleBack, onConfirmAddConsole = onConfirmAddConsole, modifier = modifier)
        LibraryStep.CARD_DETAIL   -> CardDetailContent(state, onBack = handleBack, onAddAndroidApps = onAddAndroidApps, onLoadEmulatorOptions = onLoadEmulatorOptions, onRemoveExtension = onRemoveExtension, onAddExtension = onAddExtension, onScanConsole = onScanConsole, onBeginRename = onBeginRename, onCancelRename = onCancelRename, onConfirmRename = onConfirmRename, onToggleEnabled = onToggleEnabled, onTogglePinned = onTogglePinned, onMoveCard = onMoveCard, onRemoveCard = onRemoveCard, onSetEmulatorForDetail = onSetEmulatorForDetail, onOpenImportPcGames = onOpenImportPcGames, onSetVita3KFolder = onSetVita3KFolder, onSetPs3DataFolder = onSetPs3DataFolder, onSetXbox360DataFolder = onSetXbox360DataFolder, onScanVitaGames = onScanVitaGames, onRemoveApp = onRemoveApp, modifier = modifier)
        LibraryStep.IMPORT_PC     -> ImportPcGamesContent(state, onBack = handleBack, onRefreshHomeStatus = onRefreshHomeStatus, onScanPcGamesFolder = onScanPcGamesFolder, onExportManualPcGames = onExportManualPcGames, onImportPcGame = onImportPcGame, onImportAllPcGames = onImportAllPcGames, onTestLaunchPcGame = onTestLaunchPcGame, onAddPcGameById = onAddPcGameById, onDismissMessage = onDismissMessage, convertPickerOpen = convertPicker != null, onConvertGamepadAction = onConvertGamepadAction, onBatchMatchLocalGames = onBatchMatchLocalGames, onForgetLocalSteamFolder = onForgetLocalSteamFolder, onSetGoldbergInstaller = onSetGoldbergInstaller, onCycleUnknownLauncher = onCycleUnknownLauncher, onShowHiddenUnknownLaunchers = onShowHiddenUnknownLaunchers, homeRoleIntentProvider = homeRoleIntentProvider, modifier = modifier)
    }

    // ── Convert-detected-games picker (the convertible pile of a batch match) ──
    convertPicker?.let { picker ->
        LocalSteamConvertPanel(
            rows = picker.rows.map {
                LocalSteamConvertRow(
                    folderName = it.folderName,
                    note = it.note,
                    selected = it.selected,
                    unselectable = it.unselectable,
                )
            },
            focus = picker.focus,
            loading = picker.loading,
            canConfirm = picker.canConfirm,
            focusFill = com.playfieldportal.core.ui.theme.menuCursorFill(),
            focusEdge = com.playfieldportal.core.ui.theme.menuCursorEdge(),
            // Settings is a controller-first surface; the panel's own touch row is for the XMB.
            showTouchControls = false,
            onRowClick = onConvertToggle,
            onSelectAllNone = onConvertSelectAllNone,
            onConfirm = onConvertConfirm,
            onSkip = onConvertSkip,
            onCancel = onConvertCancel,
        )
    }
}

// ── LIST ──────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryListContent(
    state: LibraryManagerUiState,
    onBack: () -> Unit,
    onAddRomRoot: (Uri?) -> Unit,
    onRelinkRomRoot: (RootFolderRow, Uri?) -> Unit,
    onBeginRelink: (RootFolderRow) -> Uri,
    onRemoveRomRoot: (RootFolderRow) -> Unit,
    onScanRomRoot: () -> Unit,
    onOpenCardDetail: (String) -> Unit,
    onStartAddConsole: () -> Unit,
    onRequestRomFolderSetup: () -> Unit,
    onScanAllConsoles: (removeMissing: Boolean) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier,
) {
    val addRootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> onAddRomRoot(uri) }
    
    var relinkTarget by remember { mutableStateOf<RootFolderRow?>(null) }
    val relinkRootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> relinkTarget?.let { onRelinkRomRoot(it, uri) }; relinkTarget = null }

    SettingsScaffold(
        title = "Settings",
        subtitle = "Library Manager",
        onBack = onBack,
        modifier = modifier,
        restoreFocusKey = state.returnFocusKey,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {

            // ── ROM Root Access ─────────────────────────────────────────────────
            RootAccessSection(
                groupTitle  = "ROM Root Access",
                roots       = state.romRoots,
                addLabel    = "Add ROM Root",
                addSublabel = "Grant a root folder (e.g. /Roms) — or a second location like an SD card",
                onAddRoot    = { addRootPicker.launch(null) },
                onRelinkRoot = { 
                    relinkTarget = it
                    relinkRootPicker.launch(onBeginRelink(it)) 
                },
                onRemoveRoot = { onRemoveRomRoot(it) },
            )

            SettingsGroup("Consoles")

            val consoleCards = state.cards.filterNot { it.platformId == "windows" }
            if (consoleCards.isEmpty()) {
                Hint("No consoles configured. Add a console to create a Memory Card that appears inside Games.")
            } else {
                consoleCards.forEach { card ->
                    SettingsRow(
                        label    = card.displayName + if (!card.enabled) "  (Hidden)" else "",
                        sublabel = cardSublabel(card),
                        focusKey = card.platformId,
                        trailing = { if (card.pinned) Text("PINNED", color = SettingsAccent) },
                        onClick  = { onOpenCardDetail(card.platformId) },
                    )
                }
            }

            SettingsGroup("Manage")

            SettingsRow(
                label    = "Add Console",
                sublabel = "Pick a platform — its games live in the matching folder under your ROM Root",
                focusKey = ADD_CONSOLE_FOCUS_KEY,
                onClick  = { onStartAddConsole() },
            )
            SettingsRow(
                label    = "Set Up ROM Folders (ES-DE)",
                sublabel = "Pick an empty folder — PFP creates the standard ES-DE system folders " +
                    "(gba, snes, psx…) for you to copy games into. No guessing folder names",
                onClick  = { onRequestRomFolderSetup() },
            )
            val anyScannable = state.cards.any { it.enabled && (it.treeUri != null || it.romDirectory != null) }
            SettingsRow(
                label    = "Scan All Consoles",
                sublabel = when {
                    state.scanningPlatformIds.isNotEmpty() -> "Scanning ${state.scanningPlatformIds.size}…"
                    state.cards.none { it.treeUri != null || it.romDirectory != null } -> "Configure a ROM folder first"
                    else -> "Scan every enabled console's folder"
                },
                onClick  = if (anyScannable) ({ onScanAllConsoles(false) }) else null,
            )
            var confirmRescanAll by remember { mutableStateOf(false) }
            if (!confirmRescanAll) {
                SettingsRow(
                    label    = "Re-Scan All (Remove Missing)",
                    sublabel = "Also removes games whose ROM file no longer exists",
                    onClick  = if (anyScannable) ({ confirmRescanAll = true }) else null,
                )
            } else {
                SettingsRow(
                    label    = "Confirm: Re-Scan and Remove Missing Games?",
                    sublabel = "Removes library entries whose ROM file is gone. This can take a while with a large library",
                    onClick  = {
                        confirmRescanAll = false
                        onScanAllConsoles(true)
                    },
                )
                SettingsRow(
                    label   = "Cancel",
                    onClick = { confirmRescanAll = false },
                )
            }

            state.message?.let { MessageRow(it) { onDismissMessage() } }
        }
    }
}

// ── PICK PLATFORM ───────────────────────────────────────────────────────────────

@Composable
private fun PickPlatformContent(
    state: LibraryManagerUiState,
    onBack: () -> Unit,
    onPlatformChosen: (PlatformOption) -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(title = "Add Console", subtitle = "Choose Platform", onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup("Supported Platforms")
            state.platformOptions.forEach { option ->
                SettingsRow(
                    label    = option.name,
                    sublabel = option.shortName,
                    onClick  = { onPlatformChosen(option) },
                )
            }
        }
    }
}

// ── PICK EMULATOR ───────────────────────────────────────────────────────────────

@Composable
private fun PickEmulatorContent(
    state: LibraryManagerUiState,
    onBack: () -> Unit,
    onEmulatorChosen: (EmulatorOption) -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(title = "Add Console", subtitle = "Assign Emulator", onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(state.pendingPlatformName ?: "Emulator")
            if (state.emulatorOptions.all { it.id == null }) {
                Hint("No installed emulators detected for this platform. You can assign one later from the console's detail screen.")
            }
            state.emulatorOptions.forEach { option ->
                SettingsRow(label = option.name, onClick = { onEmulatorChosen(option) })
            }
        }
    }
}

// ── SCAN PROMPT ─────────────────────────────────────────────────────────────────

@Composable
private fun ScanPromptContent(
    state: LibraryManagerUiState,
    onBack: () -> Unit,
    onConfirmAddConsole: (Boolean) -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(title = "Add Console", subtitle = "Scan Now?", onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(state.pendingPlatformName ?: "New Console")
            SettingsValueRow(
                label = "ROM Directory",
                value = state.pendingDirectory?.substringAfterLast('/') ?: "Not set",
                sublabel = state.pendingDirectory,
            )
            SettingsRow(
                label    = "Scan Now",
                sublabel = "Create the Memory Card and scan its folder immediately",
                onClick  = { onConfirmAddConsole(true) },
            )
            SettingsRow(
                label    = "Add Without Scanning",
                sublabel = "Create the Memory Card now, scan later",
                onClick  = { onConfirmAddConsole(false) },
            )
        }
    }
}

// ── CARD DETAIL ─────────────────────────────────────────────────────────────────

@Composable
private fun CardDetailContent(
    state: LibraryManagerUiState,
    onBack: () -> Unit,
    onAddAndroidApps: () -> Unit,
    onLoadEmulatorOptions: (onLoaded: (List<EmulatorOption>) -> Unit) -> Unit,
    onRemoveExtension: (String, String) -> Unit,
    onAddExtension: (String, String) -> Unit,
    onScanConsole: (String) -> Unit,
    onBeginRename: (String) -> Unit,
    onCancelRename: () -> Unit,
    onConfirmRename: (String) -> Unit,
    onToggleEnabled: (String, Boolean) -> Unit,
    onTogglePinned: (String, Boolean) -> Unit,
    onMoveCard: (String, Boolean) -> Unit,
    onRemoveCard: (String) -> Unit,
    onSetEmulatorForDetail: (EmulatorOption) -> Unit,
    onOpenImportPcGames: () -> Unit,
    onSetVita3KFolder: (Uri) -> Unit,
    onSetPs3DataFolder: (Uri) -> Unit,
    onSetXbox360DataFolder: (Xbox360Emulator, Uri) -> Unit,
    onScanVitaGames: () -> Unit,
    onRemoveApp: (Long) -> Unit,
    modifier: Modifier,
) {
    val card = state.detailCard ?: return

    var showRemoveConfirm  by remember { mutableStateOf(false) }
    var appToRemove        by remember { mutableStateOf<LibraryAppRow?>(null) }
    val itemMenu = rememberSettingsItemMenu()
    var newExt             by remember(card.platformId) { mutableStateOf("") }
    val isScanning = card.platformId in state.scanningPlatformIds
    val isAndroid = card.platformId == "android"
    val isWindows = card.platformId == "windows"
    val isVita    = card.platformId == "psvita"
    val isPs3     = card.platformId == "ps3"
    val isX360    = card.platformId == "x360"
    val vitaFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onSetVita3KFolder(it) } }
    val ps3FolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onSetPs3DataFolder(it) } }
    val x360MobileFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onSetXbox360DataFolder(Xbox360Emulator.X360_MOBILE, it) } }
    val xenDroidFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onSetXbox360DataFolder(Xbox360Emulator.XENDROID, it) } }

    // Renaming and removing both start from this step, so its scaffold hosts both modals.
    val renameTargetId = state.renameTargetPlatformId
    val modal = rememberSettingsModal(
        when {
            renameTargetId != null -> PfpModalSpec.TextEntry(
                key = "rename:$renameTargetId",
                title = "Rename Memory Card",
                initial = state.cards.firstOrNull { it.platformId == renameTargetId }?.displayName.orEmpty(),
                placeholder = "Memory Card name",
                onConfirm = onConfirmRename,
                onCancel = onCancelRename,
            )
            showRemoveConfirm -> PfpModalSpec.Confirm(
                key = "remove:${card.platformId}",
                title = "Remove ${card.displayName}?",
                message = "This removes the console and its scanned games from the library. " +
                    "ROM files on disk are not deleted.",
                confirmLabel = "Remove",
                destructive = true,
                onConfirm = { showRemoveConfirm = false; onRemoveCard(card.platformId) },
                onCancel = { showRemoveConfirm = false },
            )
            appToRemove != null -> appToRemove?.let { app ->
                PfpModalSpec.Confirm(
                    key = "removeApp:${app.gameId}",
                    title = "Remove ${app.label}?",
                    message = "This removes the app from this library. The app itself stays installed.",
                    confirmLabel = "Remove",
                    destructive = true,
                    onConfirm = { appToRemove = null; onRemoveApp(app.gameId) },
                    onCancel = { appToRemove = null },
                )
            }
            else -> null
        },
    )

    Box(modifier = modifier) {
    SettingsScaffold(
        title = "Library Manager",
        subtitle = card.displayName,
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
        modalOpen = modal.open,
        onInterceptAction = { modal.intercept(it) || itemMenu.intercept(it) },
    ) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {

            if (isWindows) {
                SettingsGroup("Library")
                SettingsValueRow(
                    label    = "Games Directory",
                    value    = card.romDirectory?.substringAfterLast('/') ?: "Not set",
                    sublabel = card.romDirectory
                        ?: "Add a ROM Root — PFP creates and uses <root>/windows automatically",
                )
                SettingsValueRow(label = "Games", value = card.gameCount.toString())

                SettingsGroup("Actions")
                SettingsRow(
                    label    = "Import PC Games",
                    sublabel = "Exported games, Add by ID, and launcher status",
                    focusKey = IMPORT_PC_FOCUS_KEY,
                    onClick  = onOpenImportPcGames,
                )
            } else if (isVita) {
                SettingsGroup("Library")
                SettingsValueRow(
                    label    = "Vita3K Data Folder",
                    value    = state.vita3KFolderLabel ?: "Not set",
                    sublabel = state.vita3KFolderLabel
                        ?.let { "Reading installed titles from this ux0 folder" }
                        ?: "Pick your Vita3K ux0 folder (e.g. Roms/vita/ux0) so PFP can find games",
                    onClick  = { vitaFolderPicker.launch(null) },
                )
                SettingsValueRow(label = "Games", value = card.gameCount.toString())

                SettingsGroup("Actions")
                SettingsRow(
                    label    = "Scan For Vita Games",
                    sublabel = "Reads installed titles from ux0/app in your Vita3K data folder",
                    onClick  = if (!isScanning && state.vita3KFolderLabel != null) ({ onScanVitaGames() }) else null,
                )
            } else if (isAndroid) {
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
                        val openMenu = {
                            itemMenu.show(app.label, libraryAppMenuRows(app.gameId) { appToRemove = app })
                        }
                        SettingsRow(
                            label       = app.label,
                            onClick     = openMenu,
                            onLongPress = openMenu,
                        )
                    }
                }
            } else {
                SettingsGroup("Library")
                SettingsValueRow(
                    label    = "ROM Directory",
                    value    = card.romDirectory?.substringAfterLast('/') ?: "Not set",
                    sublabel = card.romDirectory
                        ?: "Scans this console's folder under your ROM Root",
                )
                SettingsValueRow(
                    label   = "Emulator",
                    value   = card.emulatorName ?: "None",
                    // The shared item menu: the pad drives it through itemMenu.intercept, like
                    // every other menu on this screen.
                    onClick = {
                        onLoadEmulatorOptions { options ->
                            itemMenu.show(
                                "Set Emulator",
                                options.map { option ->
                                    SettingsMenuItem(
                                        option.name,
                                        value = "Current".takeIf {
                                            option.id != null && option.name == card.emulatorName
                                        },
                                    ) { onSetEmulatorForDetail(option) }
                                },
                            )
                        }
                    },
                )
                SettingsValueRow(label = "Games", value = card.gameCount.toString())

                SettingsGroup("Supported Files")
                if (card.extensions.isEmpty()) {
                    Hint("No extensions set — add at least one so scanning can match this console's ROMs.")
                } else {
                    card.extensions.forEach { ext ->
                        val openMenu = {
                            itemMenu.show(".$ext", libraryExtensionMenuRows(card.platformId, ext, onRemoveExtension))
                        }
                        SettingsRow(
                            label       = ".$ext",
                            onClick     = openMenu,
                            onLongPress = openMenu,
                        )
                    }
                }
                SettingsTextFieldRow(
                    label         = "Add Extension",
                    value         = newExt,
                    onValueChange = { newExt = it },
                    placeholder   = "e.g. iso, chd, zip",
                    helper        = "Matched case-insensitively when scanning.",
                    helperPrompt  = ControllerPromptItem(GamepadAction.SELECT, "Type"),
                )
                newExt.trim().lowercase().removePrefix(".").filter { it.isLetterOrDigit() }
                    .takeIf { it.isNotBlank() }
                    ?.let { clean ->
                        SettingsRow(
                            label   = "Add \".$clean\"",
                            onClick = { onAddExtension(card.platformId, newExt); newExt = "" },
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
                    onClick  = if (!isScanning && card.romDirectory != null) ({ onScanConsole(card.platformId) }) else null,
                )
            }

            // PS3 games scan as ordinary ROMs; this grant only unlocks trophy reading from
            // ARMSX3's own data folder. Nothing is ever written into it.
            if (isPs3) {
                SettingsGroup("Achievements")
                SettingsValueRow(
                    label    = "PS3 Data Folder",
                    value    = state.ps3FolderLabel ?: "Not set",
                    sublabel = state.ps3FolderLabel
                        ?.let { "Reading trophies from this ARMSX3 PS3 folder" }
                        ?: "Pick your ARMSX3 PS3 folder so PFP can read trophies",
                    onClick  = { ps3FolderPicker.launch(null) },
                )
                if (state.ps3FolderLabel == null) {
                    Hint(
                        "Grant the ARMSX3 PS3 folder (the one holding config/dev_hdd0), then run " +
                            "Auto-Match in Settings ▸ Shiba Coins. Trophy ids come from each game's " +
                            "own disc, so a game can link before you have ever booted it.",
                    )
                }
            }

            // Xbox 360 games scan as ordinary ROMs too; each grant only unlocks achievement reading
            // from that emulator's Xenia profiles. Both may be set — an unlock in either counts.
            if (isX360) {
                SettingsGroup("Achievements")
                SettingsValueRow(
                    label    = "X360 Mobile Data Folder",
                    value    = state.x360MobileFolderLabel ?: "Not set",
                    sublabel = state.x360MobileFolderLabel
                        ?.let { "Reading achievements from X360 Mobile's profiles" }
                        ?: "Pick X360 Mobile in the folder picker's side menu",
                    onClick  = { x360MobileFolderPicker.launch(Xbox360Emulator.X360_MOBILE.pickerStartUri) },
                )
                SettingsValueRow(
                    label    = "XenDroid Data Folder",
                    value    = state.xenDroidFolderLabel ?: "Not set",
                    sublabel = state.xenDroidFolderLabel
                        ?.let { "Reading achievements from XenDroid's profiles" }
                        ?: "Pick Android/data/xendroid.compose",
                    onClick  = { xenDroidFolderPicker.launch(Xbox360Emulator.XENDROID.pickerStartUri) },
                )
                if (state.x360MobileFolderLabel == null && state.xenDroidFolderLabel == null) {
                    Hint(
                        "Grant the data folder of the emulator you play in, then run Auto-Match in " +
                            "Settings ▸ Shiba Coins. Title IDs come from each game's own file, so a " +
                            "game can link before you have ever booted it.",
                    )
                }
            }

            if (isAndroid) SettingsGroup("Actions")
            SettingsRow(label = "Rename Memory Card", onClick = { onBeginRename(card.platformId) })
            SettingsToggleRow(
                label    = SettingsLabels.SHOW_IN_GAMES,
                sublabel = "Enable or hide this Memory Card",
                checked  = card.enabled,
                onToggle = { onToggleEnabled(card.platformId, it) },
            )
            SettingsToggleRow(
                label    = SettingsLabels.PIN_TO_TOP,
                checked  = card.pinned,
                onToggle = { onTogglePinned(card.platformId, it) },
            )
            SettingsRow(label = "Move Up",   onClick = { onMoveCard(card.platformId, true) })
            SettingsRow(label = "Move Down", onClick = { onMoveCard(card.platformId, false) })

            // The Windows Memory Card is managed by the PC import system and cannot be removed.
            if (!isWindows) {
                SettingsGroup("Danger Zone")
                SettingsRow(
                    label    = "Remove Memory Card",
                    sublabel = "Removes this console and its games. ROM files are not deleted.",
                    trailing = { Text("Remove", color = SettingsAccent) },
                    onClick  = { showRemoveConfirm = true },
                )
            }
        }
    }
    itemMenu.Content()
    }

    modal.Content()
}

/** An Android app row's menu. Removing drops the game from the library, so the row is red and the screen confirms it. */
internal fun libraryAppMenuRows(gameId: Long, onRequestRemove: (Long) -> Unit): List<SettingsMenuItem> = listOf(
    SettingsMenuItem("Remove from Library", destructive = true) { onRequestRemove(gameId) },
)

/** An extension row's menu. Removing is reversible (add it back), so the row is plain and unconfirmed. */
internal fun libraryExtensionMenuRows(
    platformId: String,
    ext: String,
    onRemove: (platformId: String, ext: String) -> Unit,
): List<SettingsMenuItem> = listOf(
    SettingsMenuItem("Remove Extension") { onRemove(platformId, ext) },
)

// ── IMPORT PC GAMES ─────────────────────────────────────────────────────────────

@Composable
private fun ImportPcGamesContent(
    state: LibraryManagerUiState,
    onBack: () -> Unit,
    onRefreshHomeStatus: () -> Unit,
    onScanPcGamesFolder: (Uri) -> Unit,
    onExportManualPcGames: () -> Unit,
    onImportPcGame: (PcGameRow) -> Unit,
    onImportAllPcGames: () -> Unit,
    onTestLaunchPcGame: (PcLauncherRow, String, String?) -> Unit,
    onAddPcGameById: (PcLauncherRow, String, String, String?) -> Unit,
    onDismissMessage: () -> Unit,
    convertPickerOpen: Boolean,
    onConvertGamepadAction: (GamepadAction) -> Boolean,
    onBatchMatchLocalGames: (Uri) -> Unit,
    onForgetLocalSteamFolder: (appId: String) -> Unit,
    onSetGoldbergInstaller: (Boolean) -> Unit,
    onCycleUnknownLauncher: (packageName: String) -> Unit,
    onShowHiddenUnknownLaunchers: () -> Unit,
    homeRoleIntentProvider: () -> android.content.Intent?,
    modifier: Modifier,
) {
    var addTarget by remember { mutableStateOf<PcLauncherRow?>(null) }

    // Picking a folder IS the scan trigger: on pick, scan that folder for exports one-shot.
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onScanPcGamesFolder(it) } }

    // The parent folder your game folders live in — one pick, every game inside it.
    val batchMatchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onBatchMatchLocalGames(it) } }

    val homeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { onRefreshHomeStatus() }

    SettingsScaffold(
        title = "Library",
        subtitle = "Import PC Games",
        onBack = onBack,
        // The convert panel is modal: it takes Up/Down/Select before the rows behind it see them.
        onInterceptAction = { action -> convertPickerOpen && onConvertGamepadAction(action) },
        modifier = modifier,
    ) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {

            state.message?.let { MessageRow(it) { onDismissMessage() } }

            SettingsGroup("Add To Home Capture")
            SettingsValueRow(
                label    = "Play Field Portal as Home",
                value    = if (state.isHomeLauncher) "Active" else "Set…",
                sublabel = if (state.isHomeLauncher)
                    "Using \"Add to home\" inside a supported launcher imports the game here automatically"
                else
                    "Set PFP as your Home app so a launcher's \"Add to home\" option imports the game into PFP",
                onClick  = { runCatching { homeLauncher.launch(homeRoleIntentProvider()) } },
            )

            SettingsGroup("Exported Games")
            SettingsRow(
                label    = "Scan Import Folder",
                sublabel = "Every scan already reads <ROM Root>/windows/import on its own. This row is " +
                    "a one-time pick for a folder somewhere else — PFP scans it for GameNative / " +
                    "Winlator exports (.steam · .epic · .gog · .amazon · .pcgame · .desktop) and PFP's " +
                    "own .pfpgame exports, and imports them",
                onClick  = { importPicker.launch(null) },
            )
            SettingsRow(
                label    = "Export Manual Games",
                sublabel = "Writes a .pfpgame file into <windows>/import for each game added by ID or captured, " +
                    "and each pin with artwork, so a fresh install can bring them back with their artwork",
                onClick  = onExportManualPcGames,
            )

            // ── Local Windows ────────────────────────────────────────────────────
            // Achievement tracking for Windows games run through a Steam emulator. Separate from
            // the groups above because it is about where the GAME FOLDERS are, not about how a game
            // launches — and because those folders can live anywhere, so PFP has to be told.
            SettingsGroup("Local Windows")
            SettingsRow(
                label    = "Batch Match Local Games",
                sublabel = "Pick the folder that CONTAINS your game folders. Each one that is already " +
                    "in your Windows library is identified, remembered and linked; the rest are left " +
                    "untouched",
                onClick  = { batchMatchPicker.launch(null) },
            )
            SettingsValueRow(
                label    = "Matched Local Games (${state.localSteamFolders.size})",
                value    = if (state.localSteamFolders.isEmpty()) "None yet" else "",
                sublabel = if (state.localSteamFolders.isEmpty()) {
                    "Nothing pointed at yet — Batch Match above, or Auto-Match one game from its Shiba Coins page"
                } else {
                    "Forget removes only PFP's note of where a folder is; earned coins stay"
                },
            )
            state.localSteamFolders.forEach { row ->
                LocalSteamFolderSettingsRow(row, onForget = { onForgetLocalSteamFolder(row.appId) })
            }
            SettingsValueRow(
                label    = "Goldberg Installer",
                value    = if (state.goldbergInstallerEnabled) "On" else "Off",
                sublabel = "Lets PFP write an achievement list into a game folder and swap in the " +
                    "bundled Steam emulator. The same switch as Settings ▸ Shiba Coins",
                onClick  = { onSetGoldbergInstaller(!state.goldbergInstallerEnabled) },
            )
            Hint(
                "Back up the save files of any game you already played before converting it — the " +
                    "emulator starts reading from a new save location, and PFP cannot put the old " +
                    "one back for you."
            )

            SettingsGroup("PC Launchers")
            state.pcLaunchers.forEach { launcher ->
                when {
                    !launcher.installed -> SettingsValueRow(label = launcher.name, value = "Not installed")
                    launcher.canAddById -> SettingsValueRow(
                        label    = launcher.name,
                        value    = "Add by ID…",
                        sublabel = "Add a game by its ID — or use the app's own Add-to-home option",
                        onClick  = { addTarget = launcher },
                    )
                    else -> SettingsValueRow(
                        label    = launcher.name,
                        value    = "Installed",
                        sublabel = "No add-by-ID support — export the game to <windows>/import instead",
                    )
                }
            }

            // Apps PFP cannot identify with confidence: the user says what each one is. A choice
            // is stored per install and outranks every automatic rule.
            if (state.unknownLaunchers.isNotEmpty() || state.hiddenUnknownLaunchers.isNotEmpty()) {
                SettingsGroup("Unknown Windows Emulators")
                state.unknownLaunchers.forEach { app ->
                    SettingsRow(
                        label    = app.label,
                        sublabel = app.sublabel,
                        focusKey = "unknown_${app.packageName}",
                        leading  = { AppIcon(app.packageName) },
                        trailing = {
                            Text(
                                text = "◀  ${app.value}  ▶",
                                color = SettingsText,
                                fontSize = 13.sp,
                                style = TextStyle(shadow = SettingsTextShadow),
                            )
                        },
                        onClick  = { onCycleUnknownLauncher(app.packageName) },
                    )
                }
                val hidden = state.hiddenUnknownLaunchers.size
                if (hidden > 0) {
                    SettingsValueRow(
                        label    = "Show Hidden Apps",
                        value    = hidden.toString(),
                        sublabel = (if (hidden == 1) "1 app" else "$hidden apps") +
                            " marked Not a launcher — bring them back to choose again",
                        onClick  = onShowHiddenUnknownLaunchers,
                    )
                }
            }

            SettingsGroup("Found Games (${state.pcGames.size})")
            if (state.pcGames.isEmpty()) {
                Hint("No PC games captured yet.")
            } else {
                state.pcGames.forEach { row ->
                    SettingsValueRow(
                        label    = row.title,
                        sublabel = row.launcherName,
                        value    = "Import",
                        onClick  = { onImportPcGame(row) },
                    )
                }
                SettingsRow(
                    label    = "Import All",
                    sublabel = "Add every found game to a custom memory card named after its launcher",
                    onClick  = onImportAllPcGames,
                )
            }
        }
    }

    addTarget?.let { launcher ->
        AddPcGameDialog(
            launcher = launcher,
            onTest   = { id, source -> onTestLaunchPcGame(launcher, id, source) },
            onAdd    = { id, title, source -> onAddPcGameById(launcher, id, title, source); addTarget = null },
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
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Game name") }, singleLine = true)
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
        confirmButton = {
            TextButton(
                onClick = { onAdd(id, title, source) },
                enabled = id.isNotBlank() && title.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onTest(id, source) }) { Text("Test Launch") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * One registered folder, with its own Forget.
 *
 * Forget is deliberately narrow: it removes PFP's note of WHERE the folder is and nothing else. The
 * game keeps its provider link, its coin list and every coin it earned, because forgetting a path
 * must never read as "you did not earn those".
 */
@Composable
private fun LocalSteamFolderSettingsRow(row: LocalSteamFolderRow, onForget: () -> Unit) {
    SettingsValueRow(
        label    = row.folderName,
        value    = "Forget",
        sublabel = row.detail,
        onClick  = onForget,
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

// ── Previews ──────────────────────────────────────────────────────────────────

@CombinedPreviews
@Composable
fun LibraryManagerScreenPreview() {
    PfpPreview {
        LibraryManagerContent(
            state = SettingsPreviewData.libraryListState,
            convertPicker = null,
            onBack = {},
            onAddAndroidApps = {},
            onAddRomRoot = {},
            onRelinkRomRoot = { _, _ -> },
            onBeginRelink = { Uri.EMPTY },
            onRemoveRomRoot = {},
            onScanRomRoot = {},
            onOpenCardDetail = {},
            onStartAddConsole = {},
            onRequestRomFolderSetup = {},
            onScanAllConsoles = {},
            onDismissMessage = {},
            onPlatformChosen = {},
            onEmulatorChosen = {},
            onConfirmAddConsole = {},
            onLoadEmulatorOptions = { _ -> },
            onRemoveExtension = { _, _ -> },
            onAddExtension = { _, _ -> },
            onScanConsole = {},
            onBeginRename = {},
            onCancelRename = {},
            onConfirmRename = {},
            onToggleEnabled = { _, _ -> },
            onTogglePinned = { _, _ -> },
            onMoveCard = { _, _ -> },
            onRemoveCard = {},
            onSetEmulatorForDetail = {},
            onOpenImportPcGames = {},
            onSetVita3KFolder = {},
            onSetPs3DataFolder = {},
            onSetXbox360DataFolder = { _, _ -> },
            onScanVitaGames = {},
            onRemoveApp = {},
            onRefreshHomeStatus = {},
            onScanPcGamesFolder = {},
            onExportManualPcGames = {},
            onImportPcGame = {},
            onImportAllPcGames = {},
            onTestLaunchPcGame = { _, _, _ -> },
            onAddPcGameById = { _, _, _, _ -> },
            onConvertToggle = {},
            onConvertSelectAllNone = {},
            onConvertConfirm = {},
            onConvertSkip = {},
            onConvertCancel = {},
            onConvertGamepadAction = { false },
            onBatchMatchLocalGames = {},
            onForgetLocalSteamFolder = {},
            onSetGoldbergInstaller = {},
            onCycleUnknownLauncher = {},
            onShowHiddenUnknownLaunchers = {},
            homeRoleIntentProvider = { null }
        )
    }
}

// Rendered once per row at a size that stays sharp at 32dp on high-density screens.
private const val APP_ICON_PX = 128

/** An installed app's own launcher icon, at the size of a settings row's leading slot. */
@Composable
private fun AppIcon(packageName: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val icon = remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = android.graphics.Bitmap.createBitmap(APP_ICON_PX, APP_ICON_PX, android.graphics.Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, APP_ICON_PX, APP_ICON_PX)
            drawable.draw(android.graphics.Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    }
    if (icon != null) {
        Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(32.dp))
    } else {
        Spacer(Modifier.size(32.dp))
    }
}
