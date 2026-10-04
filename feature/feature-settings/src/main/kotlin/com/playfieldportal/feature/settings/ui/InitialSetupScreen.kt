package com.playfieldportal.feature.settings.ui

import android.net.Uri
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.playfieldportal.core.data.repository.Xbox360Emulator
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.domain.model.displayLabel
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.settings.ui.wizard.WizardCheckboxRow
import com.playfieldportal.feature.settings.ui.wizard.WizardInfoText
import com.playfieldportal.feature.settings.ui.wizard.WizardRootRow
import com.playfieldportal.feature.settings.ui.wizard.WizardRow
import com.playfieldportal.feature.settings.ui.wizard.WizardScaffold
import com.playfieldportal.feature.settings.ui.wizard.WizardSectionHeader
import com.playfieldportal.feature.settings.ui.wizard.WizardTextField
import com.playfieldportal.feature.settings.ui.wizard.WizardValueRow
import com.playfieldportal.feature.settings.viewmodel.ArtworkSourceUi
import com.playfieldportal.feature.settings.viewmodel.ControllerSettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.InitialSetupUiState
import com.playfieldportal.feature.settings.viewmodel.InitialSetupViewModel
import com.playfieldportal.feature.settings.viewmodel.InterfaceHints
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
 * everywhere (rows, fields tap to edit). Everything is optional and written through the same
 * stores as Settings, so this is a guided front door, not a second configuration system.
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
    val nextLabel = state.nextStep?.let(::stepTitle) ?: "Finish"

    WizardScaffold(
        stepNumber = stepNumber,
        title = "Initial Setup",
        onBack = { if (!viewModel.previousStep() && !firstRun) onBack() },
        backEnabled = canGoBack,
        message = state.message,
        onDismissMessage = viewModel::dismissMessage,
        heading = headingFor(step),
        hint = hintFor(step),
        onSkip = if (step == SetupStep.FINISH) null else viewModel::skipStep,
        confirmLabel = if (step == SetupStep.EMULATORS) "Change" else "Enter",
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
                emptyText = "No ROM roots yet. Add the folder where your consoles' games live — " +
                    "one subfolder per console, scanned automatically.",
                addLabel = "Add ROM Root",
                addSublabel = "Grant a root folder with one subfolder per console",
                rescanLabel = "Rescan ROM Roots",
                rescanSublabel = "Auto-detect consoles and scan their games",
                // B3: "where do I put my ROMs?" — scaffold one ES-DE folder per platform.
                onCreateFolders = viewModel::createStandardRomFolders,
                onAdd = { pendingAdd = AddSlot.ROM; addPicker.launch(null) },
                onRelink = { row -> pendingRelinkRom = row.treeUri; relinkRomPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull()) },
                onRemove = { viewModel.removeRomRoot(it.treeUri) },
                onRescan = viewModel::rescanRomRoots,
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.MUSIC -> MediaRootsPage(
                roots = state.musicRoots,
                kindLabel = "Music",
                kind = MediaRootKind.MUSIC,
                emptyText = "No music roots yet. Add the folder where your music lives — several roots can span internal storage and an SD card.",
                addLabel = "Add Music Root",
                addSublabel = "Grant a root folder (e.g. /Music) — add several to span locations",
                onAdd = { pendingAdd = AddSlot.MUSIC; addPicker.launch(null) },
                onRelink = { row ->
                    pendingRelinkKind = MediaRootKind.MUSIC to row.treeUri
                    relinkMediaPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull())
                },
                onRemove = { viewModel.removeMediaRoot(MediaRootKind.MUSIC, it.treeUri) },
                onRescan = { viewModel.rescanMediaRoot(MediaRootKind.MUSIC) },
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.VIDEO -> MediaRootsPage(
                roots = state.videoRoots,
                kindLabel = "Video",
                kind = MediaRootKind.VIDEO,
                emptyText = "No video roots yet. Add the folder where your videos live — several roots can span internal storage and an SD card.",
                addLabel = "Add Video Root",
                addSublabel = "Grant a root folder (e.g. /Videos) — add several to span locations",
                onAdd = { pendingAdd = AddSlot.VIDEO; addPicker.launch(null) },
                onRelink = { row ->
                    pendingRelinkKind = MediaRootKind.VIDEO to row.treeUri
                    relinkMediaPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull())
                },
                onRemove = { viewModel.removeMediaRoot(MediaRootKind.VIDEO, it.treeUri) },
                onRescan = { viewModel.rescanMediaRoot(MediaRootKind.VIDEO) },
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.PHOTO -> MediaRootsPage(
                roots = state.photoRoots,
                kindLabel = "Photo",
                kind = MediaRootKind.PHOTO,
                emptyText = "No photo roots yet. Add the folder where your photos live — several roots can span internal storage and an SD card.",
                addLabel = "Add Photo Root",
                addSublabel = "Grant a root folder (e.g. /Photos) — add several to span locations",
                onAdd = { pendingAdd = AddSlot.PHOTO; addPicker.launch(null) },
                onRelink = { row ->
                    pendingRelinkKind = MediaRootKind.PHOTO to row.treeUri
                    relinkMediaPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull())
                },
                onRemove = { viewModel.removeMediaRoot(MediaRootKind.PHOTO, it.treeUri) },
                onRescan = { viewModel.rescanMediaRoot(MediaRootKind.PHOTO) },
                onContinue = { viewModel.nextStep() },
                nextLabel = nextLabel,
            )
            SetupStep.ARTWORK -> ArtworkPage(
                state = state,
                onPickFolder = { artworkPicker.launch(null) },
                onForget = viewModel::forgetArtworkFolder,
                onRemove = viewModel::forgetArtworkFolder,
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
                onOpenLibraryManager = onOpenLibraryManager,
                onGoToLibrary = onGoToLibrary,
                onFinish = {
                    viewModel.finishSetup()
                    onBack()
                },
            )
        }
    }
}

/** A page's short name, for the previous page's "Next: …" sublabel. */
private fun stepTitle(step: SetupStep): String = when (step) {
    SetupStep.WELCOME      -> "Welcome"
    SetupStep.CONTROLLER   -> "Controller"
    SetupStep.ROM_ROOTS    -> "ROM folders"
    SetupStep.MUSIC        -> "Music"
    SetupStep.VIDEO        -> "Video"
    SetupStep.PHOTO        -> "Photo"
    SetupStep.ARTWORK      -> "Artwork"
    SetupStep.SERVICES     -> "Online Services"
    SetupStep.ACHIEVEMENTS -> "Achievement Services"
    SetupStep.LOCAL_ACHIEVEMENTS -> "Local Achievements"
    SetupStep.RETROARCH    -> "RetroArch"
    SetupStep.EMULATORS    -> "Emulators"
    SetupStep.WINDOWS      -> "Windows Games"
    SetupStep.HINTS        -> "Hints & Touch"
    SetupStep.HOME_APP     -> "Home App"
    SetupStep.FINISH       -> "Finish"
}

private fun headingFor(step: SetupStep): String = when (step) {
    SetupStep.WELCOME     -> "Welcome to Play Field Portal."
    SetupStep.CONTROLLER  -> "Choose your controller."
    SetupStep.ROM_ROOTS   -> "Choose your ROM folders."
    SetupStep.MUSIC       -> "Choose your music folders."
    SetupStep.VIDEO       -> "Choose your video folders."
    SetupStep.PHOTO       -> "Choose your photo folders."
    SetupStep.ARTWORK     -> "Choose your artwork folder."
    SetupStep.SERVICES    -> "Connect your artwork sources."
    SetupStep.ACHIEVEMENTS -> "Connect your achievement services."
    SetupStep.LOCAL_ACHIEVEMENTS -> "Link your emulator data folders."
    SetupStep.RETROARCH   -> "Link RetroArch's cores folder."
    SetupStep.EMULATORS   -> "Check your emulators."
    SetupStep.WINDOWS     -> "Set up Windows games."
    SetupStep.HINTS       -> "Choose how the launcher helps you."
    SetupStep.HOME_APP    -> "Make Play Field Portal your Home screen."
    SetupStep.FINISH      -> "You're all set!"
}

private fun hintFor(step: SetupStep): String? = when (step) {
    SetupStep.WELCOME   -> "A few short steps to point the launcher at your stuff — every step is optional and can be changed later in Settings."
    SetupStep.CONTROLLER -> "Sets the button icons and which button confirms, so every prompt from here on matches your pad."
    SetupStep.ROM_ROOTS -> "Add one or more root folders — each console's games live in a subfolder under them."
    SetupStep.MUSIC     -> "Add several roots to span internal storage and an SD card."
    SetupStep.VIDEO     -> "Add several roots to span internal storage and an SD card."
    SetupStep.PHOTO     -> "Add several roots to span internal storage and an SD card."
    SetupStep.ARTWORK   -> "One folder hosts the artwork library — you can import into it right after."
    SetupStep.SERVICES  -> "All optional and free. SteamGridDB, TheGamesDB, IGDB, and ScreenScraper fetch game artwork and metadata."
    SetupStep.ACHIEVEMENTS -> "RetroAchievements and Steam track achievements as Shiba Coins."
    SetupStep.LOCAL_ACHIEVEMENTS -> "Trophies and achievements your emulators save on this device. One grant per emulator links every title."
    SetupStep.RETROARCH -> "Lets the launcher know exactly which cores you have, so only those are offered."
    SetupStep.EMULATORS -> "Each console's default, picked from what's installed. Change any that look wrong."
    SetupStep.WINDOWS   -> "A Windows emulator is installed — choose where your PC games live."
    SetupStep.HINTS     -> "Button hints fade in when you pause, and vanish the moment you press anything."
    SetupStep.HOME_APP  -> "The Home button then brings you back here instead of the stock launcher."
    SetupStep.FINISH    -> "Everything below can be adjusted anytime in Settings."
}

@Composable
private fun WizardContinueRow(label: String, onClick: () -> Unit) {
    Spacer(Modifier.height(4.dp))
    WizardRow(label = "Continue", sublabel = "Next: $label", onClick = onClick)
}

@Composable
private fun WelcomePage(onStart: () -> Unit, onSkip: () -> Unit) {
    WizardInfoText(
        "Welcome to Play Field Portal. This quick setup points the launcher at your media " +
            "folders and connects the online services used for artwork and achievements."
    )
    WizardRow(
        label = "Get Started",
        sublabel = "Choose your controller first",
        focusKey = "welcome_start",
        onClick = onStart,
    )
    WizardRow(
        label = "Skip Setup",
        sublabel = "Go straight to the launcher — run Initial Setup from Settings anytime",
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
                sublabel = if (row.linked) "Consoles home under this root"
                           else "Access lost — use ✎ to re-grant access",
                onEdit = { onRelink(row) },
                onRemove = { onRemove(row) },
            )
        }
    }
    WizardRow(label = addLabel, sublabel = addSublabel, onClick = onAdd)
    if (roots.isNotEmpty()) {
        // B3: answer "where do I put my ROMs?" — one folder per supported console, ready to fill.
        WizardRow(
            label = "Create Standard Folders",
            sublabel = "One subfolder per console, named for your games",
            onClick = onCreateFolders,
        )
    }
    WizardRow(label = rescanLabel, sublabel = rescanSublabel, onClick = onRescan)
    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun MediaRootsPage(
    roots: List<RootFolderRow>,
    kindLabel: String,
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
                sublabel = if (row.linked) "$kindLabel library lives here"
                           else "Access lost — use ✎ to re-grant access",
                onEdit = { onRelink(row) },
                onRemove = { onRemove(row) },
            )
        }
    }
    WizardRow(label = addLabel, sublabel = addSublabel, onClick = onAdd)
    WizardRow(label = "Rescan $kindLabel Library", sublabel = "Update the libraries from every root folder", onClick = onRescan)
    WizardContinueRow(nextLabel, onContinue)
}

@Composable
private fun ArtworkPage(
    state: InitialSetupUiState,
    onPickFolder: () -> Unit,
    onForget: () -> Unit,
    onRemove: () -> Unit,
    onImportNow: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    val folder = state.artworkFolderName
    if (folder == null) {
        WizardInfoText(
            "Pick a writable folder. The launcher sets up an artwork library inside it " +
                "automatically — games get art as they're added."
        )
    } else {
        WizardRootRow(
            name = folder,
            sublabel = "Artwork library — use ✎ to pick a different folder",
            onEdit = onPickFolder,
            onRemove = onRemove,
        )
    }
    WizardRow(
        label = if (folder == null) "Choose Artwork Folder" else "Change Artwork Folder",
        sublabel = if (folder == null) "One folder hosts the artwork library"
                   else "Pick a different folder — files are never deleted",
        onClick = onPickFolder,
    )
    if (state.artworkSources.isNotEmpty()) {
        WizardRow(
            label = "Import artwork now?",
            sublabel = "Copy ${state.sizeLabelForSources()} from the folder's import/ into the library",
            focusKey = "artwork_import_now",
            onClick = onImportNow,
        )
    } else if (folder != null) {
        WizardRow(
            label = "Artwork import",
            sublabel = "Nothing to import yet — add a launcher's media folder under import/",
            onClick = onImportNow,
        )
    }
    if (folder != null) {
        WizardRow(
            label = "Release artwork folder",
            sublabel = "Unlink it without touching any files",
            onClick = onForget,
        )
    }
    WizardContinueRow(nextLabel, onContinue)
}

private fun InitialSetupUiState.sizeLabelForSources(): String {
    val n = artworkSources.size
    val label = if (n == 1) artworkSources.first().label else "$n source folders"
    return label
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
        "All accounts are optional and free. SteamGridDB, TheGamesDB, IGDB, and ScreenScraper " +
            "fetch game artwork and metadata for your library."
    )

    // ── SteamGridDB ───────────────────────────────────────────────────────────
    WizardSectionHeader("SteamGridDB")
    WizardTextField(
        label = if (state.hasSgdb) "API Key (saved)" else "API Key",
        value = sgdbKeyDraft,
        onValueChange = { sgdbKeyDraft = it },
        placeholder = if (state.hasSgdb) "••••••••  (tap to replace)" else "Paste your SteamGridDB key",
        isPassword = true,
    )
    if (sgdbKeyDraft.isNotBlank()) {
        WizardRow(label = "Connect SteamGridDB", onClick = { onConnectSgdb(sgdbKeyDraft) })
    }

    // ── TheGamesDB ────────────────────────────────────────────────────────────
    // Same shape as SteamGridDB: one free API key. Mirrors the card in Settings ▸ Artwork, and
    // writes through the same MetadataApiKeyProvider, so either place configures the other.
    WizardSectionHeader("TheGamesDB")
    WizardTextField(
        label = if (state.hasTgdb) "API Key (saved)" else "API Key",
        value = tgdbKeyDraft,
        onValueChange = { tgdbKeyDraft = it },
        placeholder = if (state.hasTgdb) "••••••••  (tap to replace)" else "Paste your TheGamesDB key",
        isPassword = true,
    )
    if (tgdbKeyDraft.isNotBlank()) {
        WizardRow(label = "Connect TheGamesDB", onClick = { onConnectTgdb(tgdbKeyDraft) })
    }

    // ── IGDB (Twitch) ─────────────────────────────────────────────────────────
    WizardSectionHeader("IGDB (Twitch)")
    WizardTextField(
        label = if (state.hasIgdb) "Client ID (saved)" else "Client ID",
        value = igdbIdDraft,
        onValueChange = { igdbIdDraft = it },
        placeholder = if (state.hasIgdb) "Tap to replace" else "Twitch Client ID",
    )
    WizardTextField(
        label = if (state.hasIgdb) "Client Secret (saved)" else "Client Secret",
        value = igdbSecretDraft,
        onValueChange = { igdbSecretDraft = it },
        placeholder = if (state.hasIgdb) "••••••••  (tap to replace)" else "Twitch Client Secret",
        isPassword = true,
    )
    state.igdbStatus?.let {
        WizardInfoText(it)  // transient validation result
    }
    if (igdbIdDraft.isNotBlank() && igdbSecretDraft.isNotBlank()) {
        WizardRow(label = "Test Credentials", onClick = { onTestIgdb(igdbIdDraft, igdbSecretDraft) })
        WizardRow(label = "Connect IGDB", onClick = { onConnectIgdb(igdbIdDraft, igdbSecretDraft) })
    }

    // ── ScreenScraper (only when the build ships dev credentials) ─────────────
    if (state.ssEnabled) {
        WizardSectionHeader("ScreenScraper")
        if (state.hasScreenScraper) {
            WizardValueRow(label = "Connected as", value = state.ssUsername)
        }
        WizardTextField(
            label = if (state.hasScreenScraper) "Username (saved)" else "Username",
            value = ssUserDraft,
            onValueChange = { ssUserDraft = it },
            placeholder = if (state.hasScreenScraper) "Tap to replace" else "ScreenScraper username",
        )
        WizardTextField(
            label = if (state.hasScreenScraper) "Password (saved)" else "Password",
            value = ssPassDraft,
            onValueChange = { ssPassDraft = it },
            placeholder = if (state.hasScreenScraper) "••••••••  (tap to replace)" else "ScreenScraper password",
            isPassword = true,
        )
        state.ssStatus?.let { WizardInfoText(it) }
        if (ssUserDraft.isNotBlank() && ssPassDraft.isNotBlank()) {
            WizardRow(label = "Test Account", onClick = { onTestSs(ssUserDraft, ssPassDraft) })
            WizardRow(label = "Connect ScreenScraper", onClick = { onConnectSs(ssUserDraft, ssPassDraft) })
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
        "Optional and free. RetroAchievements and Steam track achievements as Shiba Coins."
    )

    // ── RetroAchievements ─────────────────────────────────────────────────────
    WizardSectionHeader("RetroAchievements")
    WizardTextField(
        label = if (state.hasRetroAchievements) "Username (saved)" else "Username",
        value = raUserDraft,
        onValueChange = { raUserDraft = it },
        placeholder = if (state.hasRetroAchievements) "Tap to replace" else "Your RA username",
    )
    WizardTextField(
        label = if (state.hasRetroAchievements) "Web API Key (saved)" else "Web API Key",
        value = raKeyDraft,
        onValueChange = { raKeyDraft = it },
        placeholder = if (state.hasRetroAchievements) "••••••••  (tap to replace)" else "Paste your RA Web API key",
        isPassword = true,
    )
    if (raUserDraft.isNotBlank() && raKeyDraft.isNotBlank()) {
        WizardRow(label = "Connect RetroAchievements", onClick = { onConnectRa(raUserDraft, raKeyDraft) })
    }

    // ── Steam ─────────────────────────────────────────────────────────────────
    WizardSectionHeader("Steam")
    WizardTextField(
        label = if (state.hasSteam) "SteamID64 or vanity name (saved)" else "SteamID64 or vanity name",
        value = steamIdDraft,
        onValueChange = { steamIdDraft = it },
        placeholder = if (state.hasSteam) "Tap to replace" else "7656119… or your custom URL name",
    )
    WizardTextField(
        label = if (state.hasSteam) "Web API Key (saved)" else "Web API Key",
        value = steamKeyDraft,
        onValueChange = { steamKeyDraft = it },
        placeholder = if (state.hasSteam) "••••••••  (tap to replace)" else "Paste your Steam Web API key",
        isPassword = true,
    )
    if (steamIdDraft.isNotBlank() && steamKeyDraft.isNotBlank()) {
        WizardRow(label = "Connect Steam", onClick = { onConnectSteam(steamIdDraft, steamKeyDraft) })
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
        label = "Controller Type",
        value = prefs.displayType.displayLabel(),
        sublabel = "Changes the button icons in every prompt",
        focusKey = "controller_type",
        onClick = onCycleType,
    )
    WizardValueRow(
        label = "A / B Swap",
        value = if (prefs.confirmBackLayout == com.playfieldportal.core.domain.model.ConfirmBackLayout.REVERSED) "On" else "Off",
        sublabel = confirmSwapSublabel(prefs),
        onClick = onCycleConfirm,
    )
    WizardValueRow(
        label = "X / Y Swap",
        value = if (prefs.xyLayout == XYLayout.SWAPPED) "On" else "Off",
        sublabel = "Launcher menus only — emulator controls are untouched",
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
        WizardSectionHeader("PS Vita · Vita3K")
        val folder = state.vitaFolderName
        if (folder == null) {
            WizardRow(
                label = "Set Vita3K Data Folder",
                sublabel = "Grant the ux0 folder (or the folder that contains it)",
                onClick = onLinkVita,
            )
        } else {
            WizardRootRow(
                name = folder,
                sublabel = "Vita3K data folder — use ✎ to pick a different one",
                onEdit = onLinkVita,
                onRemove = onForgetVita,
            )
        }
    }
    if (state.armsx3Installed) {
        WizardSectionHeader("PS3 · ARMSX3")
        val folder = state.ps3FolderName
        if (folder == null) {
            WizardRow(
                label = "Set ARMSX3 Data Folder",
                sublabel = "Grant PS3/config/dev_hdd0 — or any folder above or below it",
                onClick = onLinkPs3,
            )
        } else {
            WizardRootRow(
                name = folder,
                sublabel = "ARMSX3 data folder — use ✎ to pick a different one",
                onEdit = onLinkPs3,
                onRemove = onForgetPs3,
            )
        }
    }
    if (state.x360MobileInstalled) {
        WizardSectionHeader("Xbox 360 · X360 Mobile")
        val folder = state.x360MobileFolderName
        if (folder == null) {
            WizardRow(
                label = "Set X360 Mobile Data Folder",
                sublabel = "Pick X360 Mobile in the folder picker's side menu, then allow access",
                onClick = onLinkX360Mobile,
            )
        } else {
            WizardRootRow(
                name = folder,
                sublabel = "X360 Mobile data folder — use ✎ to pick a different one",
                onEdit = onLinkX360Mobile,
                onRemove = onForgetX360Mobile,
            )
        }
    }
    if (state.xenDroidInstalled) {
        WizardSectionHeader("Xbox 360 · XenDroid")
        val folder = state.xenDroidFolderName
        if (folder == null) {
            WizardRow(
                label = "Set XenDroid Data Folder",
                sublabel = "Grant Android/data/xendroid.compose — or any folder above or below it",
                onClick = onLinkXenDroid,
            )
        } else {
            WizardRootRow(
                name = folder,
                sublabel = "XenDroid data folder — use ✎ to pick a different one",
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
            label = "Cores Folder",
            value = if (state.retroArchCoreCount != null) {
                "${state.retroArchCoreCount} cores detected"
            } else {
                "Linked"
            },
        )
        WizardRow(label = "Re-link Folder", sublabel = "Pick RetroArch's folder again", onClick = onLink)
        WizardRow(label = "Re-check Cores", sublabel = "After installing new cores", onClick = onRedetect)
        WizardRow(label = "Unlink RetroArch", sublabel = "Fall back to offering every curated core", onClick = onUnlink)
    } else {
        WizardInfoText(
            "RetroArch is installed. Link its folder so only the cores you actually have are " +
                "offered when launching games — otherwise every curated core is shown (unverified)."
        )
        WizardRow(label = "Link RetroArch Folder", sublabel = "Pick the com.retroarch document tree", onClick = onLink)
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
            "No consoles with games yet. Once your ROM folders are scanned, each console appears " +
                "here with the emulator it will launch — change it anytime in Settings ▸ Emulators."
        )
    }
    rows.forEach { row ->
        WizardValueRow(
            label = row.consoleName,
            value = row.emulatorLabel ?: "None",
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
        "Pick the folder that holds one subfolder per game. PFP scans it for your Windows games; " +
            "nothing in it is changed."
    )
    WizardValueRow(label = "Detected", value = pages.detectedLaunchers)
    val folder = pages.windowsFolderName
    WizardRow(
        label = if (folder == null) "Set Windows Games Folder" else "Change Windows Games Folder",
        sublabel = folder ?: "Grant the folder your games are installed in",
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
        label = "Button Hints",
        checked = hints.enabled,
        onToggle = { onToggleHints() },
        sublabel = "The idle hint chip on the XMB, in Settings and on media screens",
        focusKey = "hints_enabled",
    )
    WizardValueRow(
        label = "Hint Delay",
        value = hintDelayLabel(hints.delaySeconds),
        sublabel = "How long to pause before hints appear (1–5 seconds)",
        onClick = onCycleDelay,
    )
    WizardValueRow(
        label = "Touch Button",
        value = touchButtonLabel(hints.touchButton),
        sublabel = "The on-screen App Drawer button — Auto shows it after you touch the screen",
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
        "Android asks you to pick a Home app and confirm it. You can switch back anytime in " +
            "Android's Default apps settings."
    )
    WizardValueRow(label = "Current Home App", value = homeAppSummary(isHomeApp))
    WizardRow(
        label = "Set as Home App",
        sublabel = "Opens Android's Home app chooser",
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
            "Nothing was configured yet — every folder and service can be added anytime from Settings."
        )
    }
    WizardSectionHeader("Summary")
    WizardValueRow(label = "Controller", value = controllerSummary(controller))
    WizardValueRow(label = "ROM Library", value = rootsShortLabel(state.romRoots))
    WizardValueRow(label = "Music", value = rootsShortLabel(state.musicRoots))
    WizardValueRow(label = "Video", value = rootsShortLabel(state.videoRoots))
    WizardValueRow(label = "Photo", value = rootsShortLabel(state.photoRoots))
    WizardValueRow(label = "Artwork Library", value = state.artworkFolderName ?: "Not set")
    WizardValueRow(label = "SteamGridDB", value = if (state.hasSgdb) "Connected" else "Not set")
    WizardValueRow(label = "TheGamesDB", value = if (state.hasTgdb) "Connected" else "Not set")
    WizardValueRow(label = "IGDB (Twitch)", value = state.igdbClientId.ifBlank { "Not set" })
    if (state.ssEnabled) {
        WizardValueRow(label = "ScreenScraper", value = state.ssUsername.ifBlank { "Not set" })
    }
    WizardValueRow(label = "RetroAchievements", value = state.raUsername.ifBlank { "Not set" })
    WizardValueRow(label = "Steam", value = if (state.hasSteam) "Connected" else "Not set")
    if (state.vita3KInstalled) {
        WizardValueRow(label = "Vita Data Folder", value = folderSummary(state.vitaFolderName))
    }
    if (state.armsx3Installed) {
        WizardValueRow(label = "PS3 Data Folder", value = folderSummary(state.ps3FolderName))
    }
    if (state.x360MobileInstalled) {
        WizardValueRow(label = "X360 Mobile Data Folder", value = folderSummary(state.x360MobileFolderName))
    }
    if (state.xenDroidInstalled) {
        WizardValueRow(label = "XenDroid Data Folder", value = folderSummary(state.xenDroidFolderName))
    }
    if (state.retroArchInstalled) {
        WizardValueRow(
            label = "RetroArch",
            value = if (state.retroArchLinked) "${state.retroArchCoreCount ?: 0} cores" else "Not linked",
        )
    }
    if (state.availability.pcLauncher) {
        WizardValueRow(label = "Windows Games", value = folderSummary(pages.windowsFolderName))
    }
    WizardValueRow(label = "Button Hints", value = hintsSummary(pages.hints))
    WizardValueRow(label = "Home App", value = homeAppSummary(pages.isHomeApp))

    Spacer(Modifier.height(4.dp))
    // OPTIONAL XMB auto-fit — explicitly opt-in, never forced. Sizing the XMB's cross layout to
    // the PSP-authentic proportions is a preference, not a default; skipping it leaves the
    // launcher exactly as it renders today. Undoable later in Display settings.
    WizardSectionHeader("XMB Layout")
    WizardCheckboxRow(
        label = "Auto-fit the XMB layout (PSP proportions) — changeable anytime in Settings",
        checked = state.autoFitXmbLayout,
        onToggle = onToggleAutoFit,
        focusKey = "finish_autofit",
    )
    if (state.romRoots.isNotEmpty()) {
        WizardRow(
            label = "Open Library Manager",
            sublabel = "Add consoles and scan the ROM roots you just set",
            onClick = onOpenLibraryManager,
        )
        // B3: end on a real launch — All Games with the cursor on the first playable game.
        WizardRow(
            label = "Go to your library",
            sublabel = "Jump straight to All Games",
            focusKey = "finish_go_library",
            onClick = onGoToLibrary,
        )
    }
    WizardRow(
        label = "Finish",
        sublabel = "Head to the launcher",
        focusKey = "finish_done",
        onClick = onFinish,
    )
}

private fun rootsShortLabel(roots: List<RootFolderRow>): String =
    when {
        roots.isEmpty() -> "Not set"
        roots.size == 1 -> roots.first().name
        else -> "${roots.first().name} +${roots.size - 1} more"
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
        hint = "A few short steps to point the launcher at your stuff — every step is optional and can be changed later in Settings.",
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
        hint = "Add one or more root folders — each console's games live in a subfolder under them.",
    ) {
        RootsPage(
            roots = listOf(
                RootFolderRow("$prefix/ROMS", "ROMS/Sega", linked = true),
                RootFolderRow("$prefix/SDROMS", "SD/ROMS", linked = false),
            ),
            emptyText = "No ROM roots yet. Add the folder where your consoles' games live.",
            addLabel = "Add ROM Root",
            addSublabel = "Grant a root folder with one subfolder per console",
            rescanLabel = "Rescan ROM Roots",
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
            kindLabel = kindLabel,
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
        hint = "One folder hosts the artwork library — you can import into it right after.",
    ) {
        ArtworkPage(
            state = InitialSetupUiState(
                artworkFolderName = "ArtworkLibrary",
                artworkSources = listOf(ArtworkSourceUi("gpSP (PSP Game Boy Advance)", 3)),
            ),
            onPickFolder = {},
            onForget = {},
            onRemove = {},
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
