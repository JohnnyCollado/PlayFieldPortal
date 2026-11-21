package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.domain.text.labelRes
import android.net.Uri
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.playfieldportal.core.data.repository.Xbox360Emulator
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.ui.wizard.WizardAmber
import com.playfieldportal.feature.settings.ui.wizard.WizardCheckboxRow
import com.playfieldportal.feature.settings.ui.wizard.WizardInfoText
import com.playfieldportal.feature.settings.ui.wizard.WizardRootRow
import com.playfieldportal.feature.settings.ui.wizard.WizardRow
import com.playfieldportal.feature.settings.ui.wizard.WizardScaffold
import com.playfieldportal.feature.settings.ui.wizard.WizardSectionHeader
import com.playfieldportal.feature.settings.ui.wizard.WizardTextField
import com.playfieldportal.feature.settings.ui.wizard.WizardValueRow
import com.playfieldportal.feature.settings.viewmodel.rootDisplayName
import com.playfieldportal.feature.settings.viewmodel.ArtworkSourceUi
import com.playfieldportal.feature.settings.viewmodel.ControllerSettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.InitialSetupUiState
import com.playfieldportal.feature.settings.viewmodel.InitialSetupViewModel
import com.playfieldportal.feature.settings.viewmodel.InterfaceHints
import com.playfieldportal.feature.settings.viewmodel.wizardOffersSkip
import com.playfieldportal.feature.settings.viewmodel.RootFolderRow
import com.playfieldportal.feature.settings.viewmodel.SetupAvailability
import com.playfieldportal.feature.settings.viewmodel.SetupEmulatorRow
import com.playfieldportal.feature.settings.viewmodel.SetupPagesUiState
import com.playfieldportal.feature.settings.viewmodel.SetupPagesViewModel
import com.playfieldportal.feature.settings.viewmodel.SetupStep
import com.playfieldportal.feature.settings.viewmodel.confirmSwapSublabel
import com.playfieldportal.feature.settings.viewmodel.controllerSummary
import com.playfieldportal.feature.settings.viewmodel.folderSummary
import com.playfieldportal.feature.settings.viewmodel.hintDelayLabel
import com.playfieldportal.feature.settings.viewmodel.hintsSummary
import com.playfieldportal.feature.settings.viewmodel.homeAppSummary
import com.playfieldportal.feature.settings.viewmodel.touchButtonLabel

// Which root-kind the single "add" SAF picker is currently serving.
private enum class AddSlot { ROM, MUSIC, VIDEO, PHOTO }

/**
 * First-run setup wizard, one task per page (per the approved plans): Welcome → Controller → ROM
 * Roots → Music → Video → Photo → Artwork (with import offer) → Online Services → Achievements →
 * Local Achievements* → RetroArch* → Emulators* → Windows Games* → Hints & Touch → Home App* → Finish
 * (* only when it applies). Channels the mockup's PSP skin via [WizardScaffold] — strongly
 * controller driven (Back steps out, Confirm activates the focused row, RB skips the page), touch
 * everywhere (rows, fields tap to edit). Everything is written through the same
 * stores as Settings, so this is a guided front door, not a second configuration system. Every page
 * can be skipped except Artwork, which needs a ready folder first.
 */
@Composable
fun InitialSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // First (automatic) run: Back cannot exit — leaving is explicit (Skip Setup or Finish).
    firstRun: Boolean = false,
    onOpenLibraryManager: () -> Unit = {},
    // B3: FINISH "Go to your library" — lands on the All Games folder, first playable game.
    onGoToLibrary: () -> Unit = {},
    viewModel: InitialSetupViewModel = hiltViewModel(),
    pagesViewModel: SetupPagesViewModel = hiltViewModel(),
    // The Controller page drives Settings ▸ Controller's own view model: identical writes.
    controllerViewModel: ControllerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val pages by pagesViewModel.uiState.collectAsState()
    val controller by controllerViewModel.uiState.collectAsState()

    // The ViewModel outlives this overlay — snap back to page one when the wizard closes, so a
    // later re-run from Settings starts at the beginning instead of resuming mid-flow.
    DisposableEffect(Unit) {
        onDispose { viewModel.resetWizard() }
    }

    // ── SAF pickers ─────────────────────────────────────────────────────────────
    var pendingAdd by remember { mutableStateOf<AddSlot?>(null) }
    val addPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val slot = pendingAdd
        pendingAdd = null
        if (uri != null && slot != null) when (slot) {
            AddSlot.ROM   -> viewModel.addRomRoot(uri)
            AddSlot.MUSIC -> viewModel.addMediaRoot(MediaRootKind.MUSIC, uri)
            AddSlot.VIDEO -> viewModel.addMediaRoot(MediaRootKind.VIDEO, uri)
            AddSlot.PHOTO -> viewModel.addMediaRoot(MediaRootKind.PHOTO, uri)
        }
    }

    var pendingRelinkKind by remember { mutableStateOf<Pair<MediaRootKind, String>?>(null) }
    val relinkMediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val (kind, oldUri) = pendingRelinkKind ?: (null to null)
        pendingRelinkKind = null
        if (uri != null && kind != null && oldUri != null) viewModel.relinkMediaRoot(kind, oldUri, uri)
    }

    var pendingRelinkRom by remember { mutableStateOf<String?>(null) }
    val relinkRomPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val old = pendingRelinkRom
        pendingRelinkRom = null
        if (uri != null && old != null) viewModel.relinkRomRoot(old, uri)
    }

    val artworkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.onArtworkFolderPicked(uri) }

    val retroPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.linkRetroArch(uri) }

    val vitaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.linkVitaFolder(uri) }

    val ps3Picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.linkPs3Folder(uri) }

    val x360MobilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.linkXbox360Folder(Xbox360Emulator.X360_MOBILE, uri) }

    val xenDroidPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.linkXbox360Folder(Xbox360Emulator.XENDROID, uri) }

    val windowsPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) pagesViewModel.linkWindowsFolder(uri) }

    // Android's Home chooser: re-read the role when it returns, and on any resume besides.
    val homePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { pagesViewModel.refreshHomeApp() }
    LifecycleResumeEffect(Unit) {
        pagesViewModel.refreshHomeApp()
        onPauseOrDispose { }
    }

    // ── Page chrome driven by the current step ─────────────────────────────────
    val step = state.step
    val stepNumber = state.stepNumber
    val canGoBack = step != SetupStep.WELCOME
    // Every Continue row names the page after this one in THIS run's flow.
    val nextLabel = state.nextStep?.let { stringResource(stepTitleRes(it)) } ?: stringResource(R.string.setup_step_finish)

    // Removing a root asks first, in the wizard as in Settings.
    val removeGate = rememberRootRemoveGate()
    val removeModal = rememberSettingsModal(removeGate.spec)
    WizardScaffold(
        stepNumber = stepNumber,
        stepCount = state.steps.size,
        modalOpen = removeModal.open,
        onInterceptModal = removeModal.intercept,
        title = stringResource(R.string.setup_title),
        onBack = { if (!viewModel.previousStep() && !firstRun) onBack() },
        backEnabled = canGoBack,
        message = state.message?.asString(),
        onDismissMessage = viewModel::dismissMessage,
        heading = stringResource(headingRes(step)),
        hint = hintRes(step)?.let { stringResource(it) },
        onSkip = if (wizardOffersSkip(step, state.artworkReady)) viewModel::skipStep else null,
        confirmLabel = stringResource(if (step == SetupStep.EMULATORS) R.string.setup_prompt_change else R.string.setup_prompt_enter),
        contentKey = step,
        modifier = modifier,
    ) {
        when (step) {
            SetupStep.WELCOME -> WelcomePage(
                onStart = { viewModel.nextStep() },
                onSkip = onBack,
            )
            SetupStep.CONTROLLER -> ControllerPage(
                prefs = controller.layoutPrefs,
                onCycleType = controllerViewModel::cycleDisplayType,
                onCycleConfirm = controllerViewModel::cycleConfirmBackLayout,
                onCycleXY = controllerViewModel::cycleXYLayout,
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.ROM_ROOTS -> RootsPage(
                roots = state.romRoots,
                emptyText = stringResource(R.string.setup_rom_empty),
                addLabel = stringResource(R.string.setup_rom_add),
                addSublabel = stringResource(R.string.setup_rom_add_sub),
                rescanLabel = stringResource(R.string.setup_rom_rescan),
                rescanSublabel = stringResource(R.string.setup_rom_rescan_sub),
                // B3: "where do I put my ROMs?" — scaffold one ES-DE folder per platform.
                onCreateFolders = viewModel::createStandardRomFolders,
                onAdd = { pendingAdd = AddSlot.ROM; addPicker.launch(null) },
                onRelink = { row -> pendingRelinkRom = row.treeUri; relinkRomPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull()) },
                onRemove = { row ->
                    removeGate.request(rootDisplayName(row.treeUri)) { viewModel.removeRomRoot(row.treeUri) }
                },
                onRescan = viewModel::rescanRomRoots,
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.MUSIC -> MediaRootsPage(
                roots = state.musicRoots,
                kind = MediaRootKind.MUSIC,
                emptyText = stringResource(R.string.setup_music_empty),
                addLabel = stringResource(R.string.setup_music_add),
                addSublabel = stringResource(R.string.setup_music_add_sub),
                onAdd = { pendingAdd = AddSlot.MUSIC; addPicker.launch(null) },
                onRelink = { row ->
                    pendingRelinkKind = MediaRootKind.MUSIC to row.treeUri
                    relinkMediaPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull())
                },
                onRemove = { row ->
                    removeGate.request(rootDisplayName(row.treeUri)) {
                        viewModel.removeMediaRoot(MediaRootKind.MUSIC, row.treeUri)
                    }
                },
                onRescan = { viewModel.rescanMediaRoot(MediaRootKind.MUSIC) },
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.VIDEO -> MediaRootsPage(
                roots = state.videoRoots,
                kind = MediaRootKind.VIDEO,
                emptyText = stringResource(R.string.setup_video_empty),
                addLabel = stringResource(R.string.setup_video_add),
                addSublabel = stringResource(R.string.setup_video_add_sub),
                onAdd = { pendingAdd = AddSlot.VIDEO; addPicker.launch(null) },
                onRelink = { row ->
                    pendingRelinkKind = MediaRootKind.VIDEO to row.treeUri
                    relinkMediaPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull())
                },
                onRemove = { row ->
                    removeGate.request(rootDisplayName(row.treeUri)) {
                        viewModel.removeMediaRoot(MediaRootKind.VIDEO, row.treeUri)
                    }
                },
                onRescan = { viewModel.rescanMediaRoot(MediaRootKind.VIDEO) },
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.PHOTO -> MediaRootsPage(
                roots = state.photoRoots,
                kind = MediaRootKind.PHOTO,
                emptyText = stringResource(R.string.setup_photo_empty),
                addLabel = stringResource(R.string.setup_photo_add),
                addSublabel = stringResource(R.string.setup_photo_add_sub),
                onAdd = { pendingAdd = AddSlot.PHOTO; addPicker.launch(null) },
                onRelink = { row ->
                    pendingRelinkKind = MediaRootKind.PHOTO to row.treeUri
                    relinkMediaPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull())
                },
                onRemove = { row ->
                    removeGate.request(rootDisplayName(row.treeUri)) {
                        viewModel.removeMediaRoot(MediaRootKind.PHOTO, row.treeUri)
                    }
                },
                onRescan = { viewModel.rescanMediaRoot(MediaRootKind.PHOTO) },
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.ARTWORK -> ArtworkPage(
                state = state,
                onPickFolder = { artworkPicker.launch(viewModel.artworkPickerStart()) },
                onImportNow = viewModel::importArtworkNow,
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.SERVICES -> ServicesPage(
                state = state,
                onConnectSgdb = viewModel::connectSgdb,
                onConnectTgdb = viewModel::connectTgdb,
                onTestIgdb = viewModel::testIgdbCredentials,
                onConnectIgdb = viewModel::connectIgdb,
                onTestSs = viewModel::testSsCredentials,
                onConnectSs = viewModel::connectScreenScraper,
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.ACHIEVEMENTS -> AchievementsPage(
                state = state,
                onConnectRa = viewModel::connectRetroAchievements,
                onConnectSteam = viewModel::connectSteam,
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.LOCAL_ACHIEVEMENTS -> LocalAchievementsPage(
                state = state,
                onLinkVita = { vitaPicker.launch(null) },
                onForgetVita = viewModel::forgetVitaFolder,
                onLinkPs3 = { ps3Picker.launch(null) },
                onForgetPs3 = viewModel::forgetPs3Folder,
                onLinkX360Mobile = { x360MobilePicker.launch(Xbox360Emulator.X360_MOBILE.pickerStartUri) },
                onForgetX360Mobile = { viewModel.forgetXbox360Folder(Xbox360Emulator.X360_MOBILE) },
                onLinkXenDroid = { xenDroidPicker.launch(Xbox360Emulator.XENDROID.pickerStartUri) },
                onForgetXenDroid = { viewModel.forgetXbox360Folder(Xbox360Emulator.XENDROID) },
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.RETROARCH -> RetroArchPage(
                state = state,
                onLink = { retroPicker.launch(null) },
                onRedetect = viewModel::redetectRetroArchCores,
                onUnlink = viewModel::unlinkRetroArch,
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.EMULATORS -> EmulatorsPage(
                rows = pages.emulatorRows,
                onCycle = pagesViewModel::cycleEmulator,
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.WINDOWS -> WindowsGamesPage(
                pages = pages,
                onPickFolder = { windowsPicker.launch(null) },
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.HINTS -> HintsPage(
                hints = pages.hints,
                onToggleHints = pagesViewModel::toggleHints,
                onCycleDelay = pagesViewModel::cycleHintDelay,
                onCycleTouch = pagesViewModel::cycleTouchButton,
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.HOME_APP -> HomeAppPage(
                isHomeApp = pages.isHomeApp,
                onSetHome = { runCatching { homePicker.launch(pagesViewModel.homeRoleIntent()) } },
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.FINISH -> FinishPage(
                state = state,
                pages = pages,
                controller = controller.layoutPrefs,
                onToggleAutoFit = viewModel::toggleAutoFitXmbLayout,
                // Every way out of Finish saves its choices, Auto-fit included; leaving by one of
                // these two rows used to drop a ticked Auto-fit (survey U12).
                onOpenLibraryManager = { viewModel.finishSetup(); onOpenLibraryManager() },
                onGoToLibrary = { viewModel.finishSetup(); onGoToLibrary() },
                onFinish = {
                    viewModel.finishSetup()
                    onBack()
                },
            )
        }
    }

    removeModal.Content()
}

/** A page's short name, for the previous page's "Next: …" sublabel. */
@StringRes
private fun stepTitleRes(step: SetupStep): Int = when (step) {
    SetupStep.WELCOME            -> R.string.setup_step_welcome
    SetupStep.CONTROLLER         -> R.string.setup_step_controller
    SetupStep.ROM_ROOTS          -> R.string.setup_step_rom_roots
    SetupStep.MUSIC              -> R.string.setup_step_music
    SetupStep.VIDEO              -> R.string.setup_step_video
    SetupStep.PHOTO              -> R.string.setup_step_photo
    SetupStep.ARTWORK            -> R.string.setup_step_artwork
    SetupStep.SERVICES           -> R.string.setup_step_services
    SetupStep.ACHIEVEMENTS       -> R.string.setup_step_achievements
    SetupStep.LOCAL_ACHIEVEMENTS -> R.string.setup_step_local_achievements
    SetupStep.RETROARCH          -> R.string.setup_step_retroarch
    SetupStep.EMULATORS          -> R.string.setup_step_emulators
    SetupStep.WINDOWS            -> R.string.setup_step_windows
    SetupStep.HINTS              -> R.string.setup_step_hints
    SetupStep.HOME_APP           -> R.string.setup_step_home_app
    SetupStep.FINISH             -> R.string.setup_step_finish
}

@StringRes
private fun headingRes(step: SetupStep): Int = when (step) {
    SetupStep.WELCOME            -> R.string.setup_heading_welcome
    SetupStep.CONTROLLER         -> R.string.setup_heading_controller
    SetupStep.ROM_ROOTS          -> R.string.setup_heading_rom_roots
    SetupStep.MUSIC              -> R.string.setup_heading_music
    SetupStep.VIDEO              -> R.string.setup_heading_video
    SetupStep.PHOTO              -> R.string.setup_heading_photo
    SetupStep.ARTWORK            -> R.string.setup_heading_artwork
    SetupStep.SERVICES           -> R.string.setup_heading_services
    SetupStep.ACHIEVEMENTS       -> R.string.setup_heading_achievements
    SetupStep.LOCAL_ACHIEVEMENTS -> R.string.setup_heading_local_achievements
    SetupStep.RETROARCH          -> R.string.setup_heading_retroarch
    SetupStep.EMULATORS          -> R.string.setup_heading_emulators
    SetupStep.WINDOWS            -> R.string.setup_heading_windows
    SetupStep.HINTS              -> R.string.setup_heading_hints
    SetupStep.HOME_APP           -> R.string.setup_heading_home_app
    SetupStep.FINISH             -> R.string.setup_heading_finish
}

@StringRes
private fun hintRes(step: SetupStep): Int? = when (step) {
    SetupStep.WELCOME            -> R.string.setup_hint_welcome
    SetupStep.CONTROLLER         -> R.string.setup_hint_controller
    SetupStep.ROM_ROOTS          -> R.string.setup_hint_rom_roots
    SetupStep.MUSIC              -> R.string.setup_hint_media_roots
    SetupStep.VIDEO              -> R.string.setup_hint_media_roots
    SetupStep.PHOTO              -> R.string.setup_hint_media_roots
    SetupStep.ARTWORK            -> R.string.setup_hint_artwork
    SetupStep.SERVICES           -> R.string.setup_hint_services
    SetupStep.ACHIEVEMENTS       -> R.string.setup_hint_achievements
    SetupStep.LOCAL_ACHIEVEMENTS -> R.string.setup_hint_local_achievements
    SetupStep.RETROARCH          -> R.string.setup_hint_retroarch
    SetupStep.EMULATORS          -> R.string.setup_hint_emulators
    SetupStep.WINDOWS            -> R.string.setup_hint_windows
    SetupStep.HINTS              -> R.string.setup_hint_hints
    SetupStep.HOME_APP           -> R.string.setup_hint_home_app
    SetupStep.FINISH             -> R.string.setup_hint_finish
}

@Composable
private fun WizardContinueRow(label: String, onClick: (() -> Unit)?) {
    Spacer(Modifier.height(4.dp))
    // A null onClick is the inert form: no activation for pad or touch, dimmed, saying why.
    WizardRow(
        label = stringResource(R.string.setup_continue),
        sublabel = if (onClick != null) stringResource(R.string.setup_continue_next, label) else stringResource(R.string.setup_continue_blocked),
        modifier = if (onClick != null) Modifier else Modifier.alpha(0.45f),
        onClick = onClick,
    )
}

@Composable
private fun WelcomePage(onStart: () -> Unit, onSkip: () -> Unit) {
    WizardInfoText(
        stringResource(R.string.setup_welcome_intro)
    )
    WizardRow(
        label = stringResource(R.string.setup_welcome_start),
        sublabel = stringResource(R.string.setup_welcome_start_sub),
        focusKey = "welcome_start",
        onClick = onStart,
    )
    WizardRow(
        label = stringResource(R.string.setup_welcome_skip),
        sublabel = stringResource(R.string.setup_welcome_skip_sub),
        onClick = onSkip,
    )
}

@Composable
private fun RootsPage(
    roots: List<RootFolderRow>,
    emptyText: String,
    addLabel: String,
    addSublabel: String,
    rescanLabel: String,
    rescanSublabel: String,
    onCreateFolders: () -> Unit,
    onAdd: () -> Unit,
    onRelink: (RootFolderRow) -> Unit,
    onRemove: (RootFolderRow) -> Unit,
    onRescan: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    if (roots.isEmpty()) {
        WizardInfoText(emptyText)
    } else {
        roots.forEach { row ->
            WizardRootRow(
                name = row.name,
                sublabel = if (row.linked) stringResource(R.string.setup_rom_root_linked)
                           else stringResource(R.string.setup_root_access_lost),
                onEdit = { onRelink(row) },
                onRemove = { onRemove(row) },
            )
        }
    }
    WizardRow(label = addLabel, sublabel = addSublabel, onClick = onAdd)
    if (roots.isNotEmpty()) {
        // B3: answer "where do I put my ROMs?" — one folder per supported console, ready to fill.
        WizardRow(
            label = stringResource(R.string.setup_rom_create_folders),
            sublabel = stringResource(R.string.setup_rom_create_folders_sub),
            onClick = onCreateFolders,
        )
    }
    WizardRow(label = rescanLabel, sublabel = rescanSublabel, onClick = onRescan)
    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun MediaRootsPage(
    roots: List<RootFolderRow>,
    kind: MediaRootKind,
    emptyText: String,
    addLabel: String,
    addSublabel: String,
    onAdd: () -> Unit,
    onRelink: (RootFolderRow) -> Unit,
    onRemove: (RootFolderRow) -> Unit,
    onRescan: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    if (roots.isEmpty()) {
        WizardInfoText(emptyText)
    } else {
        roots.forEach { row ->
            WizardRootRow(
                name = row.name,
                sublabel = if (row.linked) stringResource(
                    when (kind) {
                        MediaRootKind.MUSIC -> R.string.setup_music_root_linked
                        MediaRootKind.VIDEO -> R.string.setup_video_root_linked
                        MediaRootKind.PHOTO -> R.string.setup_photo_root_linked
                    },
                ) else stringResource(R.string.setup_root_access_lost),
                onEdit = { onRelink(row) },
                onRemove = { onRemove(row) },
            )
        }
    }
    WizardRow(label = addLabel, sublabel = addSublabel, onClick = onAdd)
    WizardRow(
        label = stringResource(
            when (kind) {
                MediaRootKind.MUSIC -> R.string.setup_music_rescan
                MediaRootKind.VIDEO -> R.string.setup_video_rescan
                MediaRootKind.PHOTO -> R.string.setup_photo_rescan
            },
        ),
        sublabel = stringResource(R.string.setup_media_rescan_sub),
        onClick = onRescan,
    )
    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun ArtworkPage(
    state: InitialSetupUiState,
    onPickFolder: () -> Unit,
    onImportNow: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    val folder = state.artworkFolderName
    if (folder == null) {
        WizardInfoText(
            stringResource(R.string.setup_artwork_empty)
        )
    } else {
        // Edit only: the artwork folder is required, so there is no unlink.
        WizardRootRow(
            name = folder,
            sublabel = if (state.artworkUnavailable) stringResource(R.string.setup_artwork_unavailable)
                       else stringResource(R.string.setup_artwork_folder_sub),
            sublabelColor = if (state.artworkUnavailable) WizardAmber else null,
            onEdit = onPickFolder,
        )
    }
    if (folder == null) {
        WizardRow(
            label = stringResource(R.string.setup_artwork_choose),
            sublabel = stringResource(R.string.setup_artwork_choose_sub),
            onClick = onPickFolder,
        )
    }
    // The import offer needs a reachable folder.
    if (state.artworkReady && state.artworkSources.isNotEmpty()) {
        WizardRow(
            label = stringResource(R.string.setup_artwork_import_title),
            sublabel = state.importSublabel(),
            focusKey = "artwork_import_now",
            onClick = onImportNow,
        )
    } else if (state.artworkReady) {
        WizardRow(
            label = stringResource(R.string.setup_artwork_import_row),
            sublabel = stringResource(R.string.setup_artwork_import_none_sub),
            onClick = onImportNow,
        )
    }
    WizardContinueRow(nextLabel, if (state.artworkReady) onContinue else null)
}

/** "Copy <source> from the folder's import/ into the library" — one named source, or a count of them. */
@Composable
private fun InitialSetupUiState.importSublabel(): String {
    val n = artworkSources.size
    return if (n == 1) {
        stringResource(R.string.setup_artwork_import_sub_one, artworkSources.first().label)
    } else {
        pluralStringResource(R.plurals.setup_artwork_import_sub_many, n, n)
    }
}

@Composable
private fun ServicesPage(
    state: InitialSetupUiState,
    onConnectSgdb: (String) -> Unit,
    onConnectTgdb: (String) -> Unit,
    onTestIgdb: (String, String) -> Unit,
    onConnectIgdb: (String, String) -> Unit,
    onTestSs: (String, String) -> Unit,
    onConnectSs: (String, String) -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    var sgdbKeyDraft by remember(state.hasSgdb) { mutableStateOf("") }
    var tgdbKeyDraft by remember(state.hasTgdb) { mutableStateOf("") }
    var igdbIdDraft by remember(state.hasIgdb) { mutableStateOf("") }
    var igdbSecretDraft by remember(state.hasIgdb) { mutableStateOf("") }
    var ssUserDraft by remember(state.hasScreenScraper) { mutableStateOf("") }
    var ssPassDraft by remember(state.hasScreenScraper) { mutableStateOf("") }

    WizardInfoText(
        stringResource(R.string.setup_services_intro)
    )

    // ── SteamGridDB ───────────────────────────────────────────────────────────
    WizardSectionHeader("SteamGridDB")
    WizardTextField(
        label = if (state.hasSgdb) stringResource(R.string.setup_field_api_key_saved) else stringResource(R.string.setup_field_api_key),
        value = sgdbKeyDraft,
        onValueChange = { sgdbKeyDraft = it },
        placeholder = if (state.hasSgdb) stringResource(R.string.setup_placeholder_secret_saved) else stringResource(R.string.setup_placeholder_sgdb_key),
        isPassword = true,
    )
    if (sgdbKeyDraft.isNotBlank()) {
        WizardRow(label = stringResource(R.string.setup_connect_sgdb), onClick = { onConnectSgdb(sgdbKeyDraft) })
    }

    // ── TheGamesDB ────────────────────────────────────────────────────────────
    // Same shape as SteamGridDB: one free API key. Mirrors the card in Settings ▸ Artwork, and
    // writes through the same MetadataApiKeyProvider, so either place configures the other.
    WizardSectionHeader("TheGamesDB")
    WizardTextField(
        label = if (state.hasTgdb) stringResource(R.string.setup_field_api_key_saved) else stringResource(R.string.setup_field_api_key),
        value = tgdbKeyDraft,
        onValueChange = { tgdbKeyDraft = it },
        placeholder = if (state.hasTgdb) stringResource(R.string.setup_placeholder_secret_saved) else stringResource(R.string.setup_placeholder_tgdb_key),
        isPassword = true,
    )
    if (tgdbKeyDraft.isNotBlank()) {
        WizardRow(label = stringResource(R.string.setup_connect_tgdb), onClick = { onConnectTgdb(tgdbKeyDraft) })
    }

    // ── IGDB (Twitch) ─────────────────────────────────────────────────────────
    WizardSectionHeader("IGDB (Twitch)")
    WizardTextField(
        label = if (state.hasIgdb) stringResource(R.string.setup_field_client_id_saved) else stringResource(R.string.setup_field_client_id),
        value = igdbIdDraft,
        onValueChange = { igdbIdDraft = it },
        placeholder = if (state.hasIgdb) stringResource(R.string.setup_placeholder_tap_to_replace) else stringResource(R.string.setup_placeholder_twitch_client_id),
    )
    WizardTextField(
        label = if (state.hasIgdb) stringResource(R.string.setup_field_client_secret_saved) else stringResource(R.string.setup_field_client_secret),
        value = igdbSecretDraft,
        onValueChange = { igdbSecretDraft = it },
        placeholder = if (state.hasIgdb) stringResource(R.string.setup_placeholder_secret_saved) else stringResource(R.string.setup_placeholder_twitch_client_secret),
        isPassword = true,
    )
    state.igdbStatus?.let {
        WizardInfoText(it.asString())  // transient validation result
    }
    if (igdbIdDraft.isNotBlank() && igdbSecretDraft.isNotBlank()) {
        WizardRow(label = stringResource(R.string.setup_test_credentials), onClick = { onTestIgdb(igdbIdDraft, igdbSecretDraft) })
        WizardRow(label = stringResource(R.string.setup_connect_igdb), onClick = { onConnectIgdb(igdbIdDraft, igdbSecretDraft) })
    }

    // ── ScreenScraper (only when the build ships dev credentials) ─────────────
    if (state.ssEnabled) {
        WizardSectionHeader("ScreenScraper")
        if (state.hasScreenScraper) {
            WizardValueRow(label = stringResource(R.string.setup_connected_as), value = state.ssUsername)
        }
        WizardTextField(
            label = if (state.hasScreenScraper) stringResource(R.string.setup_field_username_saved) else stringResource(R.string.setup_field_username),
            value = ssUserDraft,
            onValueChange = { ssUserDraft = it },
            placeholder = if (state.hasScreenScraper) stringResource(R.string.setup_placeholder_tap_to_replace) else stringResource(R.string.setup_placeholder_ss_username),
        )
        WizardTextField(
            label = if (state.hasScreenScraper) stringResource(R.string.setup_field_password_saved) else stringResource(R.string.setup_field_password),
            value = ssPassDraft,
            onValueChange = { ssPassDraft = it },
            placeholder = if (state.hasScreenScraper) stringResource(R.string.setup_placeholder_secret_saved) else stringResource(R.string.setup_placeholder_ss_password),
            isPassword = true,
        )
        state.ssStatus?.let { WizardInfoText(it.asString()) }
        if (ssUserDraft.isNotBlank() && ssPassDraft.isNotBlank()) {
            WizardRow(label = stringResource(R.string.setup_test_account), onClick = { onTestSs(ssUserDraft, ssPassDraft) })
            WizardRow(label = stringResource(R.string.setup_connect_ss), onClick = { onConnectSs(ssUserDraft, ssPassDraft) })
        }
    }

    WizardContinueRow(nextLabel, onContinue)
}

/** Achievement services — RetroAchievements and Steam. A separate page from [ServicesPage]. */
@Composable
private fun AchievementsPage(
    state: InitialSetupUiState,
    onConnectRa: (String, String) -> Unit,
    onConnectSteam: (String, String) -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    var raUserDraft by remember(state.hasRetroAchievements) { mutableStateOf("") }
    var raKeyDraft by remember(state.hasRetroAchievements) { mutableStateOf("") }
    var steamIdDraft by remember(state.hasSteam) { mutableStateOf("") }
    var steamKeyDraft by remember(state.hasSteam) { mutableStateOf("") }

    WizardInfoText(
        stringResource(R.string.setup_achievements_intro)
    )

    // ── RetroAchievements ─────────────────────────────────────────────────────
    WizardSectionHeader("RetroAchievements")
    WizardTextField(
        label = if (state.hasRetroAchievements) stringResource(R.string.setup_field_username_saved) else stringResource(R.string.setup_field_username),
        value = raUserDraft,
        onValueChange = { raUserDraft = it },
        placeholder = if (state.hasRetroAchievements) stringResource(R.string.setup_placeholder_tap_to_replace) else stringResource(R.string.setup_placeholder_ra_username),
    )
    WizardTextField(
        label = if (state.hasRetroAchievements) stringResource(R.string.setup_field_web_api_key_saved) else stringResource(R.string.setup_field_web_api_key),
        value = raKeyDraft,
        onValueChange = { raKeyDraft = it },
        placeholder = if (state.hasRetroAchievements) stringResource(R.string.setup_placeholder_secret_saved) else stringResource(R.string.setup_placeholder_ra_key),
        isPassword = true,
    )
    if (raUserDraft.isNotBlank() && raKeyDraft.isNotBlank()) {
        WizardRow(label = stringResource(R.string.setup_connect_ra), onClick = { onConnectRa(raUserDraft, raKeyDraft) })
    }

    // ── Steam ─────────────────────────────────────────────────────────────────
    WizardSectionHeader("Steam")
    WizardTextField(
        label = if (state.hasSteam) stringResource(R.string.setup_field_steam_id_saved) else stringResource(R.string.setup_field_steam_id),
        value = steamIdDraft,
        onValueChange = { steamIdDraft = it },
        placeholder = if (state.hasSteam) stringResource(R.string.setup_placeholder_tap_to_replace) else stringResource(R.string.setup_placeholder_steam_id),
    )
    WizardTextField(
        label = if (state.hasSteam) stringResource(R.string.setup_field_web_api_key_saved) else stringResource(R.string.setup_field_web_api_key),
        value = steamKeyDraft,
        onValueChange = { steamKeyDraft = it },
        placeholder = if (state.hasSteam) stringResource(R.string.setup_placeholder_secret_saved) else stringResource(R.string.setup_placeholder_steam_key),
        isPassword = true,
    )
    if (steamIdDraft.isNotBlank() && steamKeyDraft.isNotBlank()) {
        WizardRow(label = stringResource(R.string.setup_connect_steam), onClick = { onConnectSteam(steamIdDraft, steamKeyDraft) })
    }

    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun ControllerPage(
    prefs: ControllerLayoutPrefs,
    onCycleType: () -> Unit,
    onCycleConfirm: () -> Unit,
    onCycleXY: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    WizardValueRow(
        label = stringResource(R.string.setup_controller_type),
        value = stringResource(prefs.displayType.labelRes),
        sublabel = stringResource(R.string.setup_controller_type_sub),
        focusKey = "controller_type",
        onClick = onCycleType,
    )
    WizardValueRow(
        label = stringResource(R.string.setup_controller_ab_swap),
        value = if (prefs.confirmBackLayout == com.playfieldportal.core.domain.model.ConfirmBackLayout.REVERSED) stringResource(R.string.setup_on) else stringResource(R.string.setup_off),
        sublabel = confirmSwapSublabel(prefs).asString(),
        onClick = onCycleConfirm,
    )
    WizardValueRow(
        label = stringResource(R.string.setup_controller_xy_swap),
        value = if (prefs.xyLayout == XYLayout.SWAPPED) stringResource(R.string.setup_on) else stringResource(R.string.setup_off),
        sublabel = stringResource(R.string.setup_controller_xy_swap_sub),
        onClick = onCycleXY,
    )
    WizardContinueRow(nextLabel, onContinue)
}

/**
 * Emulator data folders that hold locally saved trophies and achievements — Vita3K, ARMSX3, and
 * the Xbox 360 emulators — each section only when its emulator is installed.
 */
@Composable
private fun LocalAchievementsPage(
    state: InitialSetupUiState,
    onLinkVita: () -> Unit,
    onForgetVita: () -> Unit,
    onLinkPs3: () -> Unit,
    onForgetPs3: () -> Unit,
    onLinkX360Mobile: () -> Unit,
    onForgetX360Mobile: () -> Unit,
    onLinkXenDroid: () -> Unit,
    onForgetXenDroid: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    if (state.vita3KInstalled) {
        WizardSectionHeader(stringResource(R.string.setup_local_vita_header))
        val folder = state.vitaFolderName
        if (folder == null) {
            WizardRow(
                label = stringResource(R.string.setup_local_vita_set),
                sublabel = stringResource(R.string.setup_local_vita_set_sub),
                onClick = onLinkVita,
            )
        } else {
            WizardRootRow(
                name = folder,
                sublabel = stringResource(R.string.setup_local_vita_folder_sub),
                onEdit = onLinkVita,
                onRemove = onForgetVita,
            )
        }
    }
    if (state.armsx3Installed) {
        WizardSectionHeader(stringResource(R.string.setup_local_ps3_header))
        val folder = state.ps3FolderName
        if (folder == null) {
            WizardRow(
                label = stringResource(R.string.setup_local_ps3_set),
                sublabel = stringResource(R.string.setup_local_ps3_set_sub),
                onClick = onLinkPs3,
            )
        } else {
            WizardRootRow(
                name = folder,
                sublabel = stringResource(R.string.setup_local_ps3_folder_sub),
                onEdit = onLinkPs3,
                onRemove = onForgetPs3,
            )
        }
    }
    if (state.x360MobileInstalled) {
        WizardSectionHeader(stringResource(R.string.setup_local_x360_header))
        val folder = state.x360MobileFolderName
        if (folder == null) {
            WizardRow(
                label = stringResource(R.string.setup_local_x360_set),
                sublabel = stringResource(R.string.setup_local_x360_set_sub),
                onClick = onLinkX360Mobile,
            )
        } else {
            WizardRootRow(
                name = folder,
                sublabel = stringResource(R.string.setup_local_x360_folder_sub),
                onEdit = onLinkX360Mobile,
                onRemove = onForgetX360Mobile,
            )
        }
    }
    if (state.xenDroidInstalled) {
        WizardSectionHeader(stringResource(R.string.setup_local_xendroid_header))
        val folder = state.xenDroidFolderName
        if (folder == null) {
            WizardRow(
                label = stringResource(R.string.setup_local_xendroid_set),
                sublabel = stringResource(R.string.setup_local_xendroid_set_sub),
                onClick = onLinkXenDroid,
            )
        } else {
            WizardRootRow(
                name = folder,
                sublabel = stringResource(R.string.setup_local_xendroid_folder_sub),
                onEdit = onLinkXenDroid,
                onRemove = onForgetXenDroid,
            )
        }
    }
    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun RetroArchPage(
    state: InitialSetupUiState,
    onLink: () -> Unit,
    onRedetect: () -> Unit,
    onUnlink: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    if (state.retroArchLinked) {
        WizardValueRow(
            label = stringResource(R.string.setup_retroarch_cores_folder),
            value = if (state.retroArchCoreCount != null) {
                pluralStringResource(R.plurals.setup_retroarch_cores_detected, state.retroArchCoreCount, state.retroArchCoreCount)
            } else {
                stringResource(R.string.setup_retroarch_linked)
            },
        )
        WizardRow(label = stringResource(R.string.setup_retroarch_relink), sublabel = stringResource(R.string.setup_retroarch_relink_sub), onClick = onLink)
        WizardRow(label = stringResource(R.string.setup_retroarch_recheck), sublabel = stringResource(R.string.setup_retroarch_recheck_sub), onClick = onRedetect)
        WizardRow(label = stringResource(R.string.setup_retroarch_unlink), sublabel = stringResource(R.string.setup_retroarch_unlink_sub), onClick = onUnlink)
    } else {
        WizardInfoText(
            stringResource(R.string.setup_retroarch_intro)
        )
        WizardRow(label = stringResource(R.string.setup_retroarch_link), sublabel = stringResource(R.string.setup_retroarch_link_sub), onClick = onLink)
    }
    WizardContinueRow(nextLabel, onContinue)
}

/** One row per console with a known emulator installed; confirm cycles its installed candidates. */
@Composable
private fun EmulatorsPage(
    rows: List<SetupEmulatorRow>,
    onCycle: (String) -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    if (rows.isEmpty()) {
        WizardInfoText(
            stringResource(R.string.setup_emulators_empty)
        )
    }
    rows.forEach { row ->
        WizardValueRow(
            label = row.consoleName,
            value = row.emulatorLabel ?: stringResource(R.string.setup_emulators_none),
            focusKey = "emulator_${row.platformId}",
            onClick = if (row.candidateIds.size > 1) ({ onCycle(row.platformId) }) else null,
        )
    }
    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun WindowsGamesPage(
    pages: SetupPagesUiState,
    onPickFolder: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    WizardInfoText(
        stringResource(R.string.setup_windows_intro)
    )
    WizardValueRow(label = stringResource(R.string.setup_windows_detected), value = pages.detectedLaunchers)
    val folder = pages.windowsFolderName
    WizardRow(
        label = if (folder == null) stringResource(R.string.setup_windows_set) else stringResource(R.string.setup_windows_change),
        sublabel = folder ?: stringResource(R.string.setup_windows_set_sub),
        focusKey = "windows_folder",
        onClick = onPickFolder,
    )
    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun HintsPage(
    hints: InterfaceHints,
    onToggleHints: () -> Unit,
    onCycleDelay: () -> Unit,
    onCycleTouch: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    WizardCheckboxRow(
        label = stringResource(R.string.setup_hints_enabled),
        checked = hints.enabled,
        onToggle = { onToggleHints() },
        sublabel = stringResource(R.string.setup_hints_enabled_sub),
        focusKey = "hints_enabled",
    )
    WizardValueRow(
        label = stringResource(R.string.setup_hints_delay),
        value = hintDelayLabel(hints.delaySeconds).asString(),
        sublabel = stringResource(R.string.setup_hints_delay_sub),
        onClick = onCycleDelay,
    )
    WizardValueRow(
        label = stringResource(R.string.setup_hints_touch),
        value = touchButtonLabel(hints.touchButton).asString(),
        sublabel = stringResource(R.string.setup_hints_touch_sub),
        onClick = onCycleTouch,
    )
    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun HomeAppPage(
    isHomeApp: Boolean,
    onSetHome: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    WizardInfoText(
        stringResource(R.string.setup_home_intro)
    )
    WizardValueRow(label = stringResource(R.string.setup_home_current), value = homeAppSummary(isHomeApp).asString())
    WizardRow(
        label = stringResource(R.string.setup_home_set),
        sublabel = stringResource(R.string.setup_home_set_sub),
        focusKey = "home_set",
        onClick = onSetHome,
    )
    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun FinishPage(
    state: InitialSetupUiState,
    pages: SetupPagesUiState,
    controller: ControllerLayoutPrefs,
    onToggleAutoFit: (Boolean) -> Unit,
    onOpenLibraryManager: () -> Unit,
    onGoToLibrary: () -> Unit,
    onFinish: () -> Unit,
) {
    if (!state.anyFolderSet) {
        WizardInfoText(
            stringResource(R.string.setup_finish_nothing)
        )
    }
    WizardSectionHeader(stringResource(R.string.setup_finish_summary))
    val notSet = stringResource(R.string.setup_not_set)
    val connected = stringResource(R.string.setup_connected)
    WizardValueRow(label = stringResource(R.string.setup_summary_controller), value = controllerSummary(controller).asString())
    WizardValueRow(label = stringResource(R.string.setup_summary_rom), value = rootsShortLabel(state.romRoots))
    WizardValueRow(label = stringResource(R.string.setup_summary_music), value = rootsShortLabel(state.musicRoots))
    WizardValueRow(label = stringResource(R.string.setup_summary_video), value = rootsShortLabel(state.videoRoots))
    WizardValueRow(label = stringResource(R.string.setup_summary_photo), value = rootsShortLabel(state.photoRoots))
    WizardValueRow(label = stringResource(R.string.setup_summary_artwork), value = state.artworkFolderName ?: notSet)
    WizardValueRow(label = "SteamGridDB", value = if (state.hasSgdb) connected else notSet)
    WizardValueRow(label = "TheGamesDB", value = if (state.hasTgdb) connected else notSet)
    WizardValueRow(label = "IGDB (Twitch)", value = state.igdbClientId.ifBlank { notSet })
    if (state.ssEnabled) {
        WizardValueRow(label = "ScreenScraper", value = state.ssUsername.ifBlank { notSet })
    }
    WizardValueRow(label = "RetroAchievements", value = state.raUsername.ifBlank { notSet })
    WizardValueRow(label = "Steam", value = if (state.hasSteam) connected else notSet)
    if (state.vita3KInstalled) {
        WizardValueRow(label = stringResource(R.string.setup_summary_vita), value = folderSummary(state.vitaFolderName).asString())
    }
    if (state.armsx3Installed) {
        WizardValueRow(label = stringResource(R.string.setup_summary_ps3), value = folderSummary(state.ps3FolderName).asString())
    }
    if (state.x360MobileInstalled) {
        WizardValueRow(label = stringResource(R.string.setup_summary_x360), value = folderSummary(state.x360MobileFolderName).asString())
    }
    if (state.xenDroidInstalled) {
        WizardValueRow(label = stringResource(R.string.setup_summary_xendroid), value = folderSummary(state.xenDroidFolderName).asString())
    }
    if (state.retroArchInstalled) {
        WizardValueRow(
            label = "RetroArch",
            value = if (state.retroArchLinked) {
                val cores = state.retroArchCoreCount ?: 0
                pluralStringResource(R.plurals.setup_retroarch_cores_count, cores, cores)
            } else {
                stringResource(R.string.setup_retroarch_not_linked)
            },
        )
    }
    if (state.availability.pcLauncher) {
        WizardValueRow(label = stringResource(R.string.setup_summary_windows), value = folderSummary(pages.windowsFolderName).asString())
    }
    WizardValueRow(label = stringResource(R.string.setup_summary_hints), value = hintsSummary(pages.hints).asString())
    WizardValueRow(label = stringResource(R.string.setup_summary_home), value = homeAppSummary(pages.isHomeApp).asString())

    Spacer(Modifier.height(4.dp))
    // OPTIONAL XMB auto-fit — explicitly opt-in, never forced. Sizing the XMB's cross layout to
    // the PSP-authentic proportions is a preference, not a default; skipping it leaves the
    // launcher exactly as it renders today. Undoable later in Display settings.
    WizardSectionHeader(stringResource(R.string.setup_finish_xmb_header))
    WizardCheckboxRow(
        label = stringResource(R.string.setup_finish_autofit),
        checked = state.autoFitXmbLayout,
        onToggle = onToggleAutoFit,
        focusKey = "finish_autofit",
    )
    if (state.romRoots.isNotEmpty()) {
        WizardRow(
            label = stringResource(R.string.setup_finish_open_lm),
            sublabel = stringResource(R.string.setup_finish_open_lm_sub),
            onClick = onOpenLibraryManager,
        )
        // B3: end on a real launch — All Games with the cursor on the first playable game.
        WizardRow(
            label = stringResource(R.string.setup_finish_go_library),
            sublabel = stringResource(R.string.setup_finish_go_library_sub),
            focusKey = "finish_go_library",
            onClick = onGoToLibrary,
        )
    }
    WizardRow(
        label = stringResource(R.string.setup_finish_done),
        sublabel = stringResource(R.string.setup_finish_done_sub),
        focusKey = "finish_done",
        onClick = onFinish,
    )
}

@Composable
private fun rootsShortLabel(roots: List<RootFolderRow>): String =
    when {
        roots.isEmpty() -> stringResource(R.string.setup_not_set)
        roots.size == 1 -> roots.first().name
        else -> stringResource(R.string.setup_roots_more, roots.first().name, roots.size - 1)
    }

// ── Preview scaffolding ────────────────────────────────────────────────────────
// The PSP skin is previewable statelessly (page composables take state + lambdas, never the
// ViewModel) — see [PfpScreenPreview]. Each preview renders a whole page inside [WizardScaffold]
// chrome using the [InitialSetupScreen] copy, exactly as the real screen layers it.

@Composable
private fun WizardPagePreview(
    stepNumber: Int,
    heading: String,
    hint: String?,
    backEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    PfpScreenPreview {
        WizardScaffold(
            stepNumber = stepNumber,
            title = "Initial Setup",
            onBack = {},
            backEnabled = backEnabled,
            heading = heading,
            hint = hint,
            content = content,
        )
    }
}

private val prefix = "content://preview/tree/primary%3A"

@CombinedPreviews
@Composable
private fun WelcomePagePreview() {
    WizardPagePreview(
        stepNumber = 1,
        heading = "Welcome to Play Field Portal.",
        hint = "Most steps are optional. You'll need to pick an artwork folder.",
    ) {
        WelcomePage(onStart = {}, onSkip = {})
    }
}

@CombinedPreviews
@Composable
private fun RomRootsPagePreview() {
    WizardPagePreview(
        stepNumber = 2,
        heading = "Choose your ROM folders.",
        hint = "Add one or more ROM folders — each console's games live in a subfolder inside them.",
    ) {
        RootsPage(
            roots = listOf(
                RootFolderRow("$prefix/ROMS", "ROMS/Sega", linked = true),
                RootFolderRow("$prefix/SDROMS", "SD/ROMS", linked = false),
            ),
            emptyText = "No ROM folders yet. Add the folder where your consoles' games live — one subfolder per console, scanned automatically.",
            addLabel = "Add ROM folder",
            addSublabel = "Grant a ROM folder with one subfolder per console",
            rescanLabel = "Rescan ROM folders",
            rescanSublabel = "Auto-detect consoles and scan their games",
            onCreateFolders = {},
            onAdd = {},
            onRelink = {},
            onRemove = {},
            onRescan = {},
            onContinue = {},
            nextLabel = "Music",
        )
    }
}

@Composable
private fun MediaRootsPagePreview(
    stepNumber: Int,
    heading: String,
    hint: String,
    kindLabel: String,
    kind: MediaRootKind,
    nextLabel: String,
) {
    WizardPagePreview(
        stepNumber = stepNumber,
        heading = heading,
        hint = hint,
    ) {
        MediaRootsPage(
            roots = listOf(
                RootFolderRow("$prefix/$kindLabel", "/$kindLabel", linked = true),
                RootFolderRow("$prefix/SD$kindLabel", "SD/$kindLabel", linked = true),
            ),
            kind = kind,
            emptyText = "No $kindLabel roots yet.",
            addLabel = "Add $kindLabel Root",
            addSublabel = "Grant a root folder — add several to span locations",
            onAdd = {},
            onRelink = {},
            onRemove = {},
            onRescan = {},
            onContinue = {},
            nextLabel = nextLabel,
        )
    }
}

@CombinedPreviews
@Composable
private fun MusicRootsPagePreview() = MediaRootsPagePreview(
    stepNumber = 3,
    heading = "Choose your music folders.",
    hint = "Add several roots to span internal storage and an SD card.",
    kindLabel = "Music",
    kind = MediaRootKind.MUSIC,
    nextLabel = "Video",
)

@CombinedPreviews
@Composable
private fun VideoRootsPagePreview() = MediaRootsPagePreview(
    stepNumber = 4,
    heading = "Choose your video folders.",
    hint = "Add several roots to span internal storage and an SD card.",
    kindLabel = "Video",
    kind = MediaRootKind.VIDEO,
    nextLabel = "Photo",
)

@CombinedPreviews
@Composable
private fun PhotoRootsPagePreview() = MediaRootsPagePreview(
    stepNumber = 5,
    heading = "Choose your photo folders.",
    hint = "Add several roots to span internal storage and an SD card.",
    kindLabel = "Photo",
    kind = MediaRootKind.PHOTO,
    nextLabel = "Artwork",
)

@CombinedPreviews
@Composable
private fun ArtworkPagePreview() {
    WizardPagePreview(
        stepNumber = 6,
        heading = "Choose your artwork folder.",
        hint = "Required. Your art lives here, outside the app, so it survives a reinstall.",
    ) {
        ArtworkPage(
            state = InitialSetupUiState(
                artworkFolderName = "ArtworkLibrary",
                artworkReady = true,
                artworkSources = listOf(ArtworkSourceUi("gpSP (PSP Game Boy Advance)", 3)),
            ),
            onPickFolder = {},
            onImportNow = {},
            onContinue = {},
            nextLabel = "Online Services",
        )
    }
}

@CombinedPreviews
@Composable
private fun ArtworkPageNotLinkedPreview() {
    WizardPagePreview(
        stepNumber = 6,
        heading = "Choose your artwork folder.",
        hint = "Required. Your art lives here, outside the app, so it survives a reinstall.",
    ) {
        ArtworkPage(
            state = InitialSetupUiState(),
            onPickFolder = {},
            onImportNow = {},
            onContinue = {},
            nextLabel = "Online Services",
        )
    }
}

@CombinedPreviews
@Composable
private fun ServicesPagePreview() {
    WizardPagePreview(
        stepNumber = 7,
        heading = "Connect your artwork sources.",
        hint = "All optional and free. SteamGridDB, TheGamesDB, IGDB, and ScreenScraper fetch game artwork and metadata.",
    ) {
        ServicesPage(
            state = InitialSetupUiState(
                hasSgdb = true,
                hasTgdb = true,
                igdbClientId = "client_id_abc",
                ssEnabled = true,
                ssUsername = "scraper_user",
            ),
            onConnectSgdb = {},
            onConnectTgdb = {},
            onTestIgdb = { _, _ -> },
            onConnectIgdb = { _, _ -> },
            onTestSs = { _, _ -> },
            onConnectSs = { _, _ -> },
            onContinue = {},
            nextLabel = "Achievement Services",
        )
    }
}

@CombinedPreviews
@Composable
private fun AchievementsPagePreview() {
    WizardPagePreview(
        stepNumber = 8,
        heading = "Connect your achievement services.",
        hint = "RetroAchievements and Steam track achievements as Shiba Coins.",
    ) {
        AchievementsPage(
            state = InitialSetupUiState(
                raUsername = "player_one",
                steamId64 = "76561198012345678",
            ),
            onConnectRa = { _, _ -> },
            onConnectSteam = { _, _ -> },
            onContinue = {},
            nextLabel = "RetroArch",
        )
    }
}

@CombinedPreviews
@Composable
private fun ControllerPagePreview() {
    WizardPagePreview(
        stepNumber = 2,
        heading = "Choose your controller.",
        hint = "Sets the button icons and which button confirms, so every prompt from here on matches your pad.",
    ) {
        ControllerPage(
            prefs = ControllerLayoutPrefs(),
            onCycleType = {},
            onCycleConfirm = {},
            onCycleXY = {},
            onContinue = {},
            nextLabel = "ROM folders",
        )
    }
}

@CombinedPreviews
@Composable
private fun LocalAchievementsPagePreview() {
    WizardPagePreview(
        stepNumber = 10,
        heading = "Link your emulator data folders.",
        hint = "Trophies and achievements your emulators save on this device. One grant per emulator links every title.",
    ) {
        LocalAchievementsPage(
            state = InitialSetupUiState(
                availability = SetupAvailability(vita3K = true, armsx3 = true, x360Mobile = true, xenDroid = true),
                vitaFolderName = "ux0",
                xenDroidFolderName = "xendroid.compose",
            ),
            onLinkVita = {},
            onForgetVita = {},
            onLinkPs3 = {},
            onForgetPs3 = {},
            onLinkX360Mobile = {},
            onForgetX360Mobile = {},
            onLinkXenDroid = {},
            onForgetXenDroid = {},
            onContinue = {},
            nextLabel = "RetroArch",
        )
    }
}

@CombinedPreviews
@Composable
private fun EmulatorsPagePreview() {
    WizardPagePreview(
        stepNumber = 12,
        heading = "Check your emulators.",
        hint = "Each console's default, picked from what's installed. Change any that look wrong.",
    ) {
        EmulatorsPage(
            rows = listOf(
                SetupEmulatorRow("psx", "PlayStation", "DuckStation", listOf("duckstation", "epsxe"), 0),
                SetupEmulatorRow("psp", "PlayStation Portable", "PPSSPP Gold", listOf("ppsspp"), 0),
                SetupEmulatorRow("gc", "GameCube / Wii", "Dolphin Emulator", listOf("dolphin"), 0),
            ),
            onCycle = {},
            onContinue = {},
            nextLabel = "Windows Games",
        )
    }
}

@CombinedPreviews
@Composable
private fun WindowsGamesPagePreview() {
    WizardPagePreview(
        stepNumber = 13,
        heading = "Set up Windows games.",
        hint = "A Windows emulator is installed — choose where your PC games live.",
    ) {
        WindowsGamesPage(
            pages = SetupPagesUiState(detectedLaunchers = "GameNative · Winlator Cmod · Winlator Ludashi"),
            onPickFolder = {},
            onContinue = {},
            nextLabel = "Hints & Touch",
        )
    }
}

@CombinedPreviews
@Composable
private fun HintsPagePreview() {
    WizardPagePreview(
        stepNumber = 14,
        heading = "Choose how the launcher helps you.",
        hint = "Button hints fade in when you pause, and vanish the moment you press anything.",
    ) {
        HintsPage(
            hints = InterfaceHints(enabled = true, delaySeconds = 2f),
            onToggleHints = {},
            onCycleDelay = {},
            onCycleTouch = {},
            onContinue = {},
            nextLabel = "Home App",
        )
    }
}

@CombinedPreviews
@Composable
private fun HomeAppPagePreview() {
    WizardPagePreview(
        stepNumber = 15,
        heading = "Make Play Field Portal your Home screen.",
        hint = "The Home button then brings you back here instead of the stock launcher.",
    ) {
        HomeAppPage(isHomeApp = false, onSetHome = {}, onContinue = {}, nextLabel = "Finish")
    }
}

@CombinedPreviews
@Composable
private fun RetroArchPagePreview() {
    WizardPagePreview(
        stepNumber = 10,
        heading = "Link RetroArch's cores folder.",
        hint = "Lets the launcher know exactly which cores you have, so only those are offered.",
    ) {
        RetroArchPage(
            state = InitialSetupUiState(
                availability = SetupAvailability(retroArch = true),
                retroArchLinked = true,
                retroArchCoreCount = 42,
            ),
            onLink = {},
            onRedetect = {},
            onUnlink = {},
            onContinue = {},
            nextLabel = "Emulators",
        )
    }
}

@CombinedPreviews
@Composable
private fun FinishPagePreview() {
    WizardPagePreview(
        stepNumber = 16,
        heading = "You're all set!",
        hint = "Everything below can be adjusted anytime in Settings.",
    ) {
        FinishPage(
            state = InitialSetupUiState(
                romRoots = listOf(RootFolderRow("$prefix/ROMS", "/ROMS", linked = true)),
                musicRoots = listOf(RootFolderRow("$prefix/Music", "/Music", linked = true)),
                videoRoots = listOf(RootFolderRow("$prefix/Videos", "/Videos/SD", linked = true)),
                photoRoots = listOf(RootFolderRow("$prefix/DCIM", "DCIM/Camera", linked = true)),
                artworkFolderName = "ArtworkLibrary",
                hasSgdb = true,
                igdbClientId = "client_id_abc",
                ssEnabled = true,
                ssUsername = "scraper_user",
                raUsername = "player_one",
                steamId64 = "76561198012345678",
                availability = SetupAvailability(
                    retroArch = true, vita3K = true, armsx3 = true, x360Mobile = true, xenDroid = true,
                    pcLauncher = true,
                ),
                vitaFolderName = "ux0",
                ps3FolderName = "dev_hdd0",
                xenDroidFolderName = "xendroid.compose",
                retroArchLinked = true,
                retroArchCoreCount = 42,
            ),
            pages = SetupPagesUiState(hints = InterfaceHints(enabled = true, delaySeconds = 2f), isHomeApp = true),
            controller = ControllerLayoutPrefs(),
            onToggleAutoFit = {},
            onOpenLibraryManager = {},
            onGoToLibrary = {},
            onFinish = {},
        )
    }
}
