package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.data.repository.Xbox360Emulator
import com.playfieldportal.core.ui.components.PfpModalSpec
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import com.playfieldportal.feature.settings.viewmodel.WINDOWS_GAME_LIST_FOCUS_KEY

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
        onOpenWindowsGameList = { viewModel.openWindowsGameList() },
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
    onOpenWindowsGameList: () -> Unit,
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
        LibraryStep.CARD_DETAIL   -> CardDetailContent(state, onBack = handleBack, onAddAndroidApps = onAddAndroidApps, onLoadEmulatorOptions = onLoadEmulatorOptions, onRemoveExtension = onRemoveExtension, onAddExtension = onAddExtension, onScanConsole = onScanConsole, onBeginRename = onBeginRename, onCancelRename = onCancelRename, onConfirmRename = onConfirmRename, onToggleEnabled = onToggleEnabled, onTogglePinned = onTogglePinned, onMoveCard = onMoveCard, onRemoveCard = onRemoveCard, onSetEmulatorForDetail = onSetEmulatorForDetail, onOpenImportPcGames = onOpenImportPcGames, onOpenWindowsGameList = onOpenWindowsGameList, onSetVita3KFolder = onSetVita3KFolder, onSetPs3DataFolder = onSetPs3DataFolder, onSetXbox360DataFolder = onSetXbox360DataFolder, onScanVitaGames = onScanVitaGames, onRemoveApp = onRemoveApp, modifier = modifier)
        LibraryStep.GAME_LIST     -> WindowsGameListScreen(onBack = handleBack, modifier = modifier)
        LibraryStep.IMPORT_PC     -> ImportPcGamesContent(state, onBack = handleBack, onRefreshHomeStatus = onRefreshHomeStatus, onScanPcGamesFolder = onScanPcGamesFolder, onExportManualPcGames = onExportManualPcGames, onImportPcGame = onImportPcGame, onImportAllPcGames = onImportAllPcGames, onTestLaunchPcGame = onTestLaunchPcGame, onAddPcGameById = onAddPcGameById, onDismissMessage = onDismissMessage, convertPickerOpen = convertPicker != null, onConvertGamepadAction = onConvertGamepadAction, onBatchMatchLocalGames = onBatchMatchLocalGames, onForgetLocalSteamFolder = onForgetLocalSteamFolder, onSetGoldbergInstaller = onSetGoldbergInstaller, onCycleUnknownLauncher = onCycleUnknownLauncher, onShowHiddenUnknownLaunchers = onShowHiddenUnknownLaunchers, homeRoleIntentProvider = homeRoleIntentProvider, modifier = modifier)
    }

    // ── Convert-detected-games picker (the convertible pile of a batch match) ──
    convertPicker?.let { picker ->
        LocalSteamConvertPanel(
            rows = picker.rows.map {
                LocalSteamConvertRow(
                    folderName = it.folderName,
                    note = it.note.asString(),
                    selected = it.selected,
                    unselectable = it.unselectable,
                )
            },
            focus = picker.focus,
            loading = picker.loading,
            canConfirm = picker.canConfirm,
            focusFill = com.playfieldportal.core.ui.theme.menuCursorFill(),
            focusEdge = com.playfieldportal.core.ui.theme.menuCursorEdge(),
            // The panel sits above the scaffold, so it reads the host's input mode directly: touch
            // gets the Install / All-None / Skip pills, the controller gets the button hint line.
            showTouchControls = LocalSettingsLastInputWasTouch.current,
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

    // Removing a ROM root asks first.
    val removeGate = rememberRootRemoveGate()
    val removeModal = rememberSettingsModal(removeGate.spec)
    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.lib_lm_title),
        onBack = onBack,
        modifier = modifier,
        restoreFocusKey = state.returnFocusKey,
        modalOpen = removeModal.open,
        onInterceptAction = removeModal.intercept,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {

            // ── ROM Root Access ─────────────────────────────────────────────────
            RootAccessSection(
                groupTitle  = stringResource(R.string.lib_lm_root_group),
                rootKind    = "ROM",
                rootKindRes = R.string.sets_root_kind_rom,
                roots       = state.romRoots,
                addLabel    = stringResource(R.string.lib_lm_root_add),
                addSublabel = stringResource(R.string.lib_lm_root_add_sub),
                onAddRoot    = { addRootPicker.launch(null) },
                onRelinkRoot = { 
                    relinkTarget = it
                    relinkRootPicker.launch(onBeginRelink(it)) 
                },
                onRemoveRoot = { row -> removeGate.request(row.name) { onRemoveRomRoot(row) } },
                // The scan the ES-DE setup tells the user to run once their games are copied in.
                // The row was wired but never drawn, so nothing could pick those games up short
                // of re-adding the root (survey U13).
                autoDetectLabel = if (state.romRoots.isNotEmpty()) stringResource(R.string.lib_lm_root_scan) else null,
                autoDetectSublabel = stringResource(R.string.lib_lm_root_scan_sub),
                onAutoDetect = onScanRomRoot,
            )

            SettingsGroup(stringResource(R.string.lib_lm_group_consoles))

            val consoleCards = state.cards.filterNot { it.platformId == "windows" }
            if (consoleCards.isEmpty()) {
                Hint(stringResource(R.string.lib_lm_no_consoles))
            } else {
                consoleCards.forEach { card ->
                    SettingsRow(
                        label    = if (card.enabled) card.displayName else stringResource(R.string.lib_lm_card_hidden, card.displayName),
                        sublabel = cardSublabel(card),
                        focusKey = card.platformId,
                        trailing = { if (card.pinned) Text(stringResource(R.string.lib_lm_pinned), color = SettingsAccent) },
                        onClick  = { onOpenCardDetail(card.platformId) },
                    )
                }
            }

            SettingsGroup(stringResource(R.string.lib_lm_group_manage))

            SettingsRow(
                label    = stringResource(R.string.lib_lm_add_console),
                sublabel = stringResource(R.string.lib_lm_add_console_sub),
                focusKey = ADD_CONSOLE_FOCUS_KEY,
                onClick  = { onStartAddConsole() },
            )
            SettingsRow(
                label    = stringResource(R.string.lib_lm_setup_folders),
                sublabel = stringResource(R.string.lib_lm_setup_folders_sub),
                onClick  = { onRequestRomFolderSetup() },
            )
            val anyScannable = state.cards.any { it.enabled && (it.treeUri != null || it.romDirectory != null) }
            SettingsRow(
                label    = stringResource(R.string.lib_lm_scan_all),
                sublabel = when {
                    state.scanningPlatformIds.isNotEmpty() -> stringResource(R.string.lib_lm_scanning_count, state.scanningPlatformIds.size)
                    state.cards.none { it.treeUri != null || it.romDirectory != null } -> stringResource(R.string.lib_lm_configure_first)
                    else -> stringResource(R.string.lib_lm_scan_all_sub)
                },
                onClick  = if (anyScannable) ({ onScanAllConsoles(false) }) else null,
            )
            var confirmRescanAll by remember { mutableStateOf(false) }
            if (!confirmRescanAll) {
                SettingsRow(
                    label    = stringResource(R.string.lib_lm_rescan_all),
                    sublabel = stringResource(R.string.lib_lm_rescan_all_sub),
                    onClick  = if (anyScannable) ({ confirmRescanAll = true }) else null,
                )
            } else {
                SettingsRow(
                    label    = stringResource(R.string.lib_lm_rescan_confirm),
                    sublabel = stringResource(R.string.lib_lm_rescan_confirm_sub),
                    onClick  = {
                        confirmRescanAll = false
                        onScanAllConsoles(true)
                    },
                )
                SettingsRow(
                    label   = stringResource(R.string.lib_lm_cancel),
                    onClick = { confirmRescanAll = false },
                )
            }

            state.message?.let { MessageRow(it.asString()) { onDismissMessage() } }
        }
    }

    removeModal.Content()
}

// ── PICK PLATFORM ───────────────────────────────────────────────────────────────

@Composable
private fun PickPlatformContent(
    state: LibraryManagerUiState,
    onBack: () -> Unit,
    onPlatformChosen: (PlatformOption) -> Unit,
    modifier: Modifier,
) {
    SettingsScaffold(title = stringResource(R.string.lib_lm_add_console_title), subtitle = stringResource(R.string.lib_lm_choose_platform), onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(stringResource(R.string.lib_lm_group_platforms))
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
    SettingsScaffold(title = stringResource(R.string.lib_lm_add_console_title), subtitle = stringResource(R.string.lib_lm_assign_emulator), onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(state.pendingPlatformName ?: stringResource(R.string.lib_lm_emulator))
            if (state.emulatorOptions.all { it.id == null }) {
                Hint(stringResource(R.string.lib_lm_no_emulators))
            }
            state.emulatorOptions.forEach { option ->
                SettingsRow(
                    label = if (option.id == null) stringResource(R.string.lib_lm_decide_later) else option.name,
                    onClick = { onEmulatorChosen(option) },
                )
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
    SettingsScaffold(title = stringResource(R.string.lib_lm_add_console_title), subtitle = stringResource(R.string.lib_lm_scan_now_title), onBack = onBack, modifier = modifier) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            SettingsGroup(state.pendingPlatformName ?: stringResource(R.string.lib_lm_new_console))
            SettingsValueRow(
                label = stringResource(R.string.lib_lm_rom_directory),
                value = state.pendingDirectory?.substringAfterLast('/') ?: stringResource(R.string.lib_lm_not_set),
                sublabel = state.pendingDirectory,
            )
            SettingsRow(
                label    = stringResource(R.string.lib_lm_scan_now),
                sublabel = stringResource(R.string.lib_lm_scan_now_sub),
                onClick  = { onConfirmAddConsole(true) },
            )
            SettingsRow(
                label    = stringResource(R.string.lib_lm_add_no_scan),
                sublabel = stringResource(R.string.lib_lm_add_no_scan_sub),
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
    onOpenWindowsGameList: () -> Unit,
    onSetVita3KFolder: (Uri) -> Unit,
    onSetPs3DataFolder: (Uri) -> Unit,
    onSetXbox360DataFolder: (Xbox360Emulator, Uri) -> Unit,
    onScanVitaGames: () -> Unit,
    onRemoveApp: (Long) -> Unit,
    modifier: Modifier,
) {
    val card = state.detailCard ?: return
    // Read here: the per-item menus are built in click handlers, which cannot read resources.
    val removeFromLibraryLabel = stringResource(R.string.lib_lm_remove_from_library)
    val removeExtensionLabel = stringResource(R.string.lib_lm_remove_extension)
    val currentLabel = stringResource(R.string.lib_lm_current)
    val setEmulatorLabel = stringResource(R.string.lib_lm_set_emulator)
    val decideLaterLabel = stringResource(R.string.lib_lm_decide_later)

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
                title = stringResource(R.string.lib_lm_rename_title),
                initial = state.cards.firstOrNull { it.platformId == renameTargetId }?.displayName.orEmpty(),
                placeholder = stringResource(R.string.lib_lm_rename_placeholder),
                onConfirm = onConfirmRename,
                onCancel = onCancelRename,
            )
            showRemoveConfirm -> PfpModalSpec.Confirm(
                key = "remove:${card.platformId}",
                title = stringResource(R.string.lib_lm_remove_card_title, card.displayName),
                message = stringResource(R.string.lib_lm_remove_card_message),
                confirmLabel = stringResource(R.string.lib_lm_remove),
                destructive = true,
                onConfirm = { showRemoveConfirm = false; onRemoveCard(card.platformId) },
                onCancel = { showRemoveConfirm = false },
            )
            appToRemove != null -> appToRemove?.let { app ->
                PfpModalSpec.Confirm(
                    key = "removeApp:${app.gameId}",
                    title = stringResource(R.string.lib_lm_remove_card_title, app.label),
                    message = stringResource(R.string.lib_lm_remove_app_message),
                    confirmLabel = stringResource(R.string.lib_lm_remove),
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
        title = stringResource(R.string.lib_lm_title),
        subtitle = card.displayName,
        onBack = onBack,
        modifier = Modifier.fillMaxSize(),
        // Scoped to the Game List's own key so other card details keep first-row focus.
        restoreFocusKey = state.returnFocusKey?.takeIf { it == WINDOWS_GAME_LIST_FOCUS_KEY },
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
                SettingsGroup(stringResource(R.string.lib_lm_group_library))
                SettingsValueRow(
                    label    = stringResource(R.string.lib_lm_games_directory),
                    value    = card.romDirectory?.substringAfterLast('/') ?: stringResource(R.string.lib_lm_not_set),
                    sublabel = card.romDirectory
                        ?: stringResource(R.string.lib_lm_windows_no_root),
                )
                SettingsValueRow(label = stringResource(R.string.lib_lm_games), value = card.gameCount.toString())

                SettingsGroup(stringResource(R.string.lib_lm_group_actions))
                SettingsRow(
                    label    = stringResource(R.string.lib_lm_game_list),
                    sublabel = stringResource(R.string.lib_lm_game_list_sub),
                    focusKey = WINDOWS_GAME_LIST_FOCUS_KEY,
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = SettingsText,
                        )
                    },
                    onClick  = onOpenWindowsGameList,
                )
                SettingsRow(
                    label    = stringResource(R.string.lib_lm_import_pc),
                    sublabel = stringResource(R.string.lib_lm_import_pc_sub),
                    focusKey = IMPORT_PC_FOCUS_KEY,
                    onClick  = onOpenImportPcGames,
                )
            } else if (isVita) {
                SettingsGroup(stringResource(R.string.lib_lm_group_library))
                SettingsValueRow(
                    label    = stringResource(R.string.lib_lm_vita_folder),
                    value    = state.vita3KFolderLabel ?: stringResource(R.string.lib_lm_not_set),
                    sublabel = state.vita3KFolderLabel
                        ?.let { stringResource(R.string.lib_lm_vita_folder_set) }
                        ?: stringResource(R.string.lib_lm_vita_folder_unset),
                    onClick  = { vitaFolderPicker.launch(null) },
                )
                SettingsValueRow(label = stringResource(R.string.lib_lm_games), value = card.gameCount.toString())

                SettingsGroup(stringResource(R.string.lib_lm_group_actions))
                SettingsRow(
                    label    = stringResource(R.string.lib_lm_scan_vita),
                    sublabel = stringResource(R.string.lib_lm_scan_vita_sub),
                    onClick  = if (!isScanning && state.vita3KFolderLabel != null) ({ onScanVitaGames() }) else null,
                )
            } else if (isAndroid) {
                SettingsGroup(stringResource(R.string.lib_lm_group_apps))
                SettingsRow(
                    label    = stringResource(R.string.lib_lm_add_apps),
                    sublabel = stringResource(R.string.lib_lm_add_apps_sub),
                    onClick  = onAddAndroidApps,
                )
                if (state.androidApps.isEmpty()) {
                    Hint(stringResource(R.string.lib_lm_no_apps))
                } else {
                    state.androidApps.forEach { app ->
                        val openMenu = {
                            itemMenu.show(app.label, libraryAppMenuRows(app.gameId, removeFromLibraryLabel) { appToRemove = app })
                        }
                        SettingsRow(
                            label       = app.label,
                            onClick     = openMenu,
                            onLongPress = openMenu,
                        )
                    }
                }
            } else {
                SettingsGroup(stringResource(R.string.lib_lm_group_library))
                SettingsValueRow(
                    label    = stringResource(R.string.lib_lm_rom_directory),
                    value    = card.romDirectory?.substringAfterLast('/') ?: stringResource(R.string.lib_lm_not_set),
                    sublabel = card.romDirectory
                        ?: stringResource(R.string.lib_lm_rom_directory_default),
                )
                SettingsValueRow(
                    label   = stringResource(R.string.lib_lm_emulator),
                    value   = card.emulatorName ?: stringResource(R.string.lib_lm_none),
                    // The shared item menu: the pad drives it through itemMenu.intercept, like
                    // every other menu on this screen.
                    onClick = {
                        onLoadEmulatorOptions { options ->
                            itemMenu.show(
                                setEmulatorLabel,
                                options.map { option ->
                                    SettingsMenuItem(
                                        if (option.id == null) decideLaterLabel else option.name,
                                        value = currentLabel.takeIf {
                                            option.id != null && option.name == card.emulatorName
                                        },
                                    ) { onSetEmulatorForDetail(option) }
                                },
                            )
                        }
                    },
                )
                SettingsValueRow(label = stringResource(R.string.lib_lm_games), value = card.gameCount.toString())

                SettingsGroup(stringResource(R.string.lib_lm_group_files))
                if (card.extensions.isEmpty()) {
                    Hint(stringResource(R.string.lib_lm_no_extensions))
                } else {
                    card.extensions.forEach { ext ->
                        val openMenu = {
                            itemMenu.show(".$ext", libraryExtensionMenuRows(card.platformId, ext, removeExtensionLabel, onRemoveExtension))
                        }
                        SettingsRow(
                            label       = ".$ext",
                            onClick     = openMenu,
                            onLongPress = openMenu,
                        )
                    }
                }
                SettingsTextFieldRow(
                    label         = stringResource(R.string.lib_lm_add_extension),
                    value         = newExt,
                    onValueChange = { newExt = it },
                    placeholder   = stringResource(R.string.lib_lm_extension_hint),
                    helper        = stringResource(R.string.lib_lm_extension_helper),
                    helperPrompt  = ControllerPromptItem(GamepadAction.SELECT, stringResource(R.string.lib_lm_prompt_type)),
                )
                newExt.trim().lowercase().removePrefix(".").filter { it.isLetterOrDigit() }
                    .takeIf { it.isNotBlank() }
                    ?.let { clean ->
                        SettingsRow(
                            label   = stringResource(R.string.lib_lm_add_extension_named, clean),
                            onClick = { onAddExtension(card.platformId, newExt); newExt = "" },
                        )
                    }

                SettingsGroup(stringResource(R.string.lib_lm_group_actions))
                SettingsRow(
                    label    = stringResource(R.string.lib_lm_scan_console),
                    sublabel = when {
                        isScanning -> stringResource(R.string.lib_lm_scanning_ellipsis)
                        card.romDirectory == null -> stringResource(R.string.lib_lm_rom_dir_unconfigured)
                        else -> stringResource(R.string.lib_lm_scan_console_sub)
                    },
                    onClick  = if (!isScanning && card.romDirectory != null) ({ onScanConsole(card.platformId) }) else null,
                )
            }

            // PS3 games scan as ordinary ROMs; this grant only unlocks trophy reading from
            // ARMSX3's own data folder. Nothing is ever written into it.
            if (isPs3) {
                SettingsGroup(stringResource(R.string.lib_lm_group_achievements))
                SettingsValueRow(
                    label    = stringResource(R.string.lib_lm_ps3_folder),
                    value    = state.ps3FolderLabel ?: stringResource(R.string.lib_lm_not_set),
                    sublabel = state.ps3FolderLabel
                        ?.let { stringResource(R.string.lib_lm_ps3_folder_set_sub) }
                        ?: stringResource(R.string.lib_lm_ps3_folder_unset),
                    onClick  = { ps3FolderPicker.launch(null) },
                )
                if (state.ps3FolderLabel == null) {
                    Hint(stringResource(R.string.lib_lm_ps3_hint))
                }
            }

            // Xbox 360 games scan as ordinary ROMs too; each grant only unlocks achievement reading
            // from that emulator's Xenia profiles. Both may be set — an unlock in either counts.
            if (isX360) {
                SettingsGroup(stringResource(R.string.lib_lm_group_achievements))
                SettingsValueRow(
                    label    = stringResource(R.string.lib_lm_x360_folder),
                    value    = state.x360MobileFolderLabel ?: stringResource(R.string.lib_lm_not_set),
                    sublabel = state.x360MobileFolderLabel
                        ?.let { stringResource(R.string.lib_lm_x360_folder_set_sub) }
                        ?: stringResource(R.string.lib_lm_x360_folder_unset),
                    onClick  = { x360MobileFolderPicker.launch(Xbox360Emulator.X360_MOBILE.pickerStartUri) },
                )
                SettingsValueRow(
                    label    = stringResource(R.string.lib_lm_xendroid_folder),
                    value    = state.xenDroidFolderLabel ?: stringResource(R.string.lib_lm_not_set),
                    sublabel = state.xenDroidFolderLabel
                        ?.let { stringResource(R.string.lib_lm_xendroid_folder_set_sub) }
                        ?: stringResource(R.string.lib_lm_xendroid_folder_unset),
                    onClick  = { xenDroidFolderPicker.launch(Xbox360Emulator.XENDROID.pickerStartUri) },
                )
                if (state.x360MobileFolderLabel == null && state.xenDroidFolderLabel == null) {
                    Hint(stringResource(R.string.lib_lm_x360_hint))
                }
            }

            if (isAndroid) SettingsGroup(stringResource(R.string.lib_lm_group_actions))
            SettingsRow(label = stringResource(R.string.lib_lm_rename_card), onClick = { onBeginRename(card.platformId) })
            SettingsToggleRow(
                label    = stringResource(SettingsLabels.SHOW_IN_GAMES_RES),
                sublabel = stringResource(R.string.lib_lm_show_in_games_sub),
                checked  = card.enabled,
                onToggle = { onToggleEnabled(card.platformId, it) },
            )
            SettingsToggleRow(
                label    = stringResource(SettingsLabels.PIN_TO_TOP_RES),
                checked  = card.pinned,
                onToggle = { onTogglePinned(card.platformId, it) },
            )
            SettingsRow(label = stringResource(R.string.lib_lm_move_up),   onClick = { onMoveCard(card.platformId, true) })
            SettingsRow(label = stringResource(R.string.lib_lm_move_down), onClick = { onMoveCard(card.platformId, false) })

            // The Windows Memory Card is managed by the PC import system and cannot be removed.
            if (!isWindows) {
                SettingsGroup(stringResource(R.string.lib_lm_group_danger))
                SettingsRow(
                    label    = stringResource(R.string.lib_lm_remove_card),
                    sublabel = stringResource(R.string.lib_lm_remove_card_sub),
                    trailing = { Text(stringResource(R.string.lib_lm_remove), color = SettingsAccent) },
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
internal fun libraryAppMenuRows(gameId: Long, removeLabel: String, onRequestRemove: (Long) -> Unit): List<SettingsMenuItem> = listOf(
    SettingsMenuItem(removeLabel, destructive = true) { onRequestRemove(gameId) },
)

/** An extension row's menu. Removing is reversible (add it back), so the row is plain and unconfirmed. */
internal fun libraryExtensionMenuRows(
    platformId: String,
    ext: String,
    removeLabel: String,
    onRemove: (platformId: String, ext: String) -> Unit,
): List<SettingsMenuItem> = listOf(
    SettingsMenuItem(removeLabel) { onRemove(platformId, ext) },
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

    addTarget?.let { launcher ->
        AddPcGameStep(
            launcher = launcher,
            onTest = { id, source -> onTestLaunchPcGame(launcher, id, source) },
            onAdd = { id, title, source -> onAddPcGameById(launcher, id, title, source); addTarget = null },
            onBack = { addTarget = null },
            modifier = modifier,
        )
        return
    }

    // Turning the Goldberg installer on asks for a save backup first, as it does in
    // Achievements ▸ Local Windows — this row is the same switch (survey U32).
    var showGoldbergWarning by remember { mutableStateOf(false) }
    val goldbergModal = rememberSettingsModal(
        if (!showGoldbergWarning) null else goldbergWarningSpec(
            onConfirm = { onSetGoldbergInstaller(true); showGoldbergWarning = false },
            onCancel = { showGoldbergWarning = false },
        ),
    )

    SettingsScaffold(
        title = stringResource(R.string.lib_lm_library_title),
        subtitle = stringResource(R.string.lib_lm_import_pc_title),
        onBack = onBack,
        modalOpen = goldbergModal.open,
        // The convert panel is modal: it takes Up/Down/Select before the rows behind it see them.
        onInterceptAction = { action ->
            goldbergModal.intercept(action) || (convertPickerOpen && onConvertGamepadAction(action))
        },
        modifier = modifier,
    ) {
        // Registered like the list screens: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view. Registering is the whole fix;
        // the body itself is unchanged.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {

            state.message?.let { MessageRow(it.asString()) { onDismissMessage() } }

            SettingsGroup(stringResource(R.string.lib_lm_group_home))
            SettingsValueRow(
                label    = stringResource(R.string.lib_lm_home_label),
                value    = stringResource(if (state.isHomeLauncher) R.string.lib_lm_home_active else R.string.lib_lm_home_set),
                sublabel = stringResource(if (state.isHomeLauncher) R.string.lib_lm_home_active_sub else R.string.lib_lm_home_set_sub),
                onClick  = { runCatching { homeLauncher.launch(homeRoleIntentProvider()) } },
            )

            SettingsGroup(stringResource(R.string.lib_lm_group_exported))
            SettingsRow(
                label    = stringResource(R.string.lib_lm_scan_import),
                sublabel = stringResource(R.string.lib_lm_scan_import_sub),
                onClick  = { importPicker.launch(null) },
            )
            SettingsRow(
                label    = stringResource(R.string.lib_lm_export_manual),
                sublabel = stringResource(R.string.lib_lm_export_manual_sub),
                onClick  = onExportManualPcGames,
            )

            // ── Local Windows ────────────────────────────────────────────────────
            // Achievement tracking for Windows games run through a Steam emulator. Separate from
            // the groups above because it is about where the GAME FOLDERS are, not about how a game
            // launches — and because those folders can live anywhere, so PFP has to be told.
            SettingsGroup(stringResource(R.string.lib_lm_group_local))
            SettingsRow(
                label    = stringResource(R.string.lib_lm_batch_match),
                sublabel = stringResource(R.string.lib_lm_batch_match_sub),
                onClick  = { batchMatchPicker.launch(null) },
            )
            SettingsValueRow(
                label    = stringResource(R.string.lib_lm_matched_local, state.localSteamFolders.size),
                value    = if (state.localSteamFolders.isEmpty()) stringResource(R.string.lib_lm_none_yet) else "",
                sublabel = stringResource(
                    if (state.localSteamFolders.isEmpty()) R.string.lib_lm_matched_local_empty else R.string.lib_lm_matched_local_forget_note,
                ),
            )
            state.localSteamFolders.forEach { row ->
                LocalSteamFolderSettingsRow(row, onForget = { onForgetLocalSteamFolder(row.appId) })
            }
            SettingsValueRow(
                label    = stringResource(R.string.lib_lm_goldberg),
                value    = stringResource(if (state.goldbergInstallerEnabled) R.string.lib_lm_on else R.string.lib_lm_off),
                sublabel = stringResource(R.string.lib_lm_goldberg_sub),
                onClick  = {
                    if (state.goldbergInstallerEnabled) onSetGoldbergInstaller(false) else showGoldbergWarning = true
                },
            )
            Hint(stringResource(R.string.lib_lm_goldberg_backup))

            SettingsGroup(stringResource(R.string.lib_lm_group_launchers))
            state.pcLaunchers.forEach { launcher ->
                when {
                    !launcher.installed -> SettingsValueRow(label = launcher.name, value = stringResource(R.string.lib_lm_not_installed))
                    launcher.canAddById -> SettingsValueRow(
                        label    = launcher.name,
                        value    = stringResource(R.string.lib_lm_add_by_id),
                        sublabel = stringResource(R.string.lib_lm_add_by_id_sub),
                        onClick  = { addTarget = launcher },
                    )
                    else -> SettingsValueRow(
                        label    = launcher.name,
                        value    = stringResource(R.string.lib_lm_installed),
                        sublabel = stringResource(R.string.lib_lm_no_add_by_id),
                    )
                }
            }

            // Apps PFP cannot identify with confidence: the user says what each one is. A choice
            // is stored per install and outranks every automatic rule.
            if (state.unknownLaunchers.isNotEmpty() || state.hiddenUnknownLaunchers.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.lib_lm_group_unknown))
                state.unknownLaunchers.forEach { app ->
                    SettingsRow(
                        label    = app.label,
                        sublabel = app.sublabel.asString(),
                        focusKey = "unknown_${app.packageName}",
                        leading  = { AppIcon(app.packageName) },
                        trailing = {
                            Text(
                                text = "◀  ${app.value.asString()}  ▶",
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
                        label    = stringResource(R.string.lib_lm_show_hidden),
                        value    = hidden.toString(),
                        sublabel = pluralStringResource(R.plurals.lib_lm_show_hidden_sub, hidden, hidden),
                        onClick  = onShowHiddenUnknownLaunchers,
                    )
                }
            }

            SettingsGroup(stringResource(R.string.lib_lm_group_found, state.pcGames.size))
            if (state.pcGames.isEmpty()) {
                Hint(stringResource(R.string.lib_lm_no_found))
            } else {
                state.pcGames.forEach { row ->
                    SettingsValueRow(
                        label    = row.title,
                        sublabel = row.launcherName,
                        value    = stringResource(R.string.lib_lm_import),
                        onClick  = { onImportPcGame(row) },
                    )
                }
                SettingsRow(
                    label    = stringResource(R.string.lib_lm_import_all),
                    sublabel = stringResource(R.string.lib_lm_import_all_sub),
                    onClick  = onImportAllPcGames,
                )
            }
        }
    }
    goldbergModal.Content()
}

/** The next of a launcher's [sources] after [current], wrapping; null when it has none. */
internal fun nextPcSource(sources: List<String>, current: String?): String? {
    if (sources.isEmpty()) return null
    val at = sources.indexOf(current)
    return sources[(at + 1) % sources.size]
}

internal fun canAddPcGame(id: String, title: String): Boolean = id.isNotBlank() && title.isNotBlank()

internal fun canTestPcGame(id: String): Boolean = id.isNotBlank()

/**
 * Add a game to a PC launcher by its ID, as a settings step: Game ID and Game name open the shared
 * text entry, Source cycles, and Test Launch / Add Game are rows. It was a Material dialog whose
 * text fields and Source chips a pad could not reach (survey P6).
 */
@Composable
private fun AddPcGameStep(
    launcher: PcLauncherRow,
    onTest: (id: String, source: String?) -> Unit,
    onAdd: (id: String, title: String, source: String?) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val adapter = PcLauncherAdapters.forType(launcher.type)
    val sources = adapter?.sources.orEmpty()
    var id by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(sources.firstOrNull()) }
    // Which field the shared text entry is editing, or null.
    var editing by remember { mutableStateOf<String?>(null) }

    val modal = rememberSettingsModal(
        when (editing) {
            "id" -> PfpModalSpec.TextEntry(
                key = "pc_add_id", title = stringResource(R.string.lib_lm_game_id), initial = id, label = stringResource(R.string.lib_lm_game_id),
                onConfirm = { id = it; editing = null }, onCancel = { editing = null },
            )
            "title" -> PfpModalSpec.TextEntry(
                key = "pc_add_title", title = stringResource(R.string.lib_lm_game_name), initial = title, label = stringResource(R.string.lib_lm_game_name),
                onConfirm = { title = it; editing = null }, onCancel = { editing = null },
            )
            else -> null
        },
    )

    SettingsScaffold(
        title = stringResource(R.string.lib_lm_library_title),
        subtitle = stringResource(R.string.lib_lm_add_game_title, launcher.name),
        onBack = onBack,
        modalOpen = modal.open,
        onInterceptAction = modal.intercept,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
            adapter?.idPrompt?.let { Hint(it.asString()) }
            SettingsGroup(stringResource(R.string.lib_lm_group_game))
            SettingsValueRow(label = stringResource(R.string.lib_lm_game_id), value = id.ifBlank { stringResource(R.string.lib_lm_not_set) }, onClick = { editing = "id" })
            SettingsValueRow(label = stringResource(R.string.lib_lm_game_name), value = title.ifBlank { stringResource(R.string.lib_lm_not_set) }, onClick = { editing = "title" })
            if (sources.isNotEmpty()) {
                SettingsValueRow(
                    label = stringResource(R.string.lib_lm_source),
                    value = source.orEmpty(),
                    sublabel = stringResource(R.string.lib_lm_source_sub),
                    onClick = { source = nextPcSource(sources, source) },
                )
            }
            SettingsGroup(stringResource(R.string.lib_lm_group_add))
            SettingsRow(
                label = stringResource(R.string.lib_lm_test_launch),
                sublabel = stringResource(R.string.lib_lm_test_launch_sub, launcher.name),
                onClick = if (canTestPcGame(id)) ({ onTest(id.trim(), source) }) else null,
            )
            SettingsRow(
                label = stringResource(R.string.lib_lm_add_game),
                sublabel = stringResource(if (canAddPcGame(id, title)) R.string.lib_lm_add_game_sub else R.string.lib_lm_add_game_blocked),
                onClick = if (canAddPcGame(id, title)) ({ onAdd(id.trim(), title.trim(), source) }) else null,
            )
        }
    }
    modal.Content()
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
        value    = stringResource(R.string.lib_lm_forget),
        sublabel = row.detail.asString(),
        onClick  = onForget,
    )
}

// ── Shared bits ─────────────────────────────────────────────────────────────────

@Composable
private fun cardSublabel(card: LibraryCardRow): String = stringResource(
    R.string.lib_lm_card_sub,
    card.romDirectory ?: stringResource(R.string.lib_lm_no_rom_directory),
    pluralStringResource(R.plurals.lib_lm_game_count, card.gameCount, card.gameCount),
)

@Composable
private fun Hint(text: String) {
    Text(text = text, color = SettingsSubtext, modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp))
}

@Composable
private fun MessageRow(message: String, onDismiss: () -> Unit) {
    SettingsRow(
        label    = message,
        sublabel = stringResource(R.string.lib_lm_tap_dismiss),
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
            onOpenWindowsGameList = {},
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
