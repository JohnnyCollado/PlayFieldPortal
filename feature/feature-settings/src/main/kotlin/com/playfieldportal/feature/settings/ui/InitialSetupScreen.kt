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
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.settings.ui.wizard.WizardInfoText
import com.playfieldportal.feature.settings.ui.wizard.WizardRootRow
import com.playfieldportal.feature.settings.ui.wizard.WizardRow
import com.playfieldportal.feature.settings.ui.wizard.WizardScaffold
import com.playfieldportal.feature.settings.ui.wizard.WizardSectionHeader
import com.playfieldportal.feature.settings.ui.wizard.WizardTextField
import com.playfieldportal.feature.settings.ui.wizard.WizardValueRow
import com.playfieldportal.feature.settings.viewmodel.ArtworkSourceUi
import com.playfieldportal.feature.settings.viewmodel.InitialSetupUiState
import com.playfieldportal.feature.settings.viewmodel.InitialSetupViewModel
import com.playfieldportal.feature.settings.viewmodel.RootFolderRow
import com.playfieldportal.feature.settings.viewmodel.SetupStep

// Which root-kind the single "add" SAF picker is currently serving.
private enum class AddSlot { ROM, MUSIC, VIDEO, PHOTO }

/**
 * First-run setup wizard, now one task per page (per the approved plan): Welcome → ROM Roots →
 * Music → Video → Photo → Artwork (with import offer) → Online Services → RetroArch* → Finish
 * (* only when RetroArch is installed). Channels the mockup's PSP skin via [WizardScaffold] —
 * strongly controller driven (○ back, ✕ activates the focused row / ▶ or Continue to advance),
 * touch everywhere (rows, fields tap to edit). Everything is optional and written through the
 * same stores as Settings, so this is a guided front door, not a second configuration system.
 */
@Composable
fun InitialSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // First (automatic) run: Back cannot exit — leaving is explicit (Skip Setup or Finish).
    firstRun: Boolean = false,
    onOpenLibraryManager: () -> Unit = {},
    viewModel: InitialSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

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

    // ── Page chrome driven by the current step ─────────────────────────────────
    val step = state.step
    val stepNumber = state.stepNumber
    val canGoBack = step != SetupStep.WELCOME

    WizardScaffold(
        stepNumber = stepNumber,
        title = "Initial Setup",
        onBack = { if (!viewModel.previousStep() && !firstRun) onBack() },
        backEnabled = canGoBack,
        message = state.message,
        onDismissMessage = viewModel::dismissMessage,
        heading = headingFor(step),
        hint = hintFor(step),
        modifier = modifier,
    ) {
        when (step) {
            SetupStep.WELCOME -> WelcomePage(
                onStart = { viewModel.nextStep() },
                onSkip = onBack,
            )
            SetupStep.ROM_ROOTS -> RootsPage(
                roots = state.romRoots,
                emptyText = "No ROM roots yet. Add the folder where your consoles' games live — " +
                    "one subfolder per console, scanned automatically.",
                addLabel = "Add ROM Root",
                addSublabel = "Grant a root folder with one subfolder per console",
                rescanLabel = "Rescan ROM Roots",
                rescanSublabel = "Auto-detect consoles and scan their games",
                onAdd = { pendingAdd = AddSlot.ROM; addPicker.launch(null) },
                onRelink = { row -> pendingRelinkRom = row.treeUri; relinkRomPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull()) },
                onRemove = { viewModel.removeRomRoot(it.treeUri) },
                onRescan = viewModel::rescanRomRoots,
                onContinue = { viewModel.nextStep() },
                nextLabel = "Music",
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
                nextLabel = "Video",
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
                nextLabel = "Photo",
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
                nextLabel = "Artwork",
            )
            SetupStep.ARTWORK -> ArtworkPage(
                state = state,
                onPickFolder = { artworkPicker.launch(null) },
                onForget = viewModel::forgetArtworkFolder,
                onRemove = viewModel::forgetArtworkFolder,
                onImportNow = viewModel::importArtworkNow,
                onContinue = { viewModel.nextStep() },
                nextLabel = "Online Services",
            )
            SetupStep.SERVICES -> ServicesPage(
                state = state,
                onConnectSgdb = viewModel::connectSgdb,
                onTestIgdb = viewModel::testIgdbCredentials,
                onConnectIgdb = viewModel::connectIgdb,
                onTestSs = viewModel::testSsCredentials,
                onConnectSs = viewModel::connectScreenScraper,
                onContinue = { viewModel.nextStep() },
                nextLabel = "Achievement Services",
            )
            SetupStep.ACHIEVEMENTS -> AchievementsPage(
                state = state,
                onConnectRa = viewModel::connectRetroAchievements,
                onConnectSteam = viewModel::connectSteam,
                onContinue = { viewModel.nextStep() },
                nextLabel = if (state.vita3KInstalled) "Vita Data Folder"
                            else if (state.retroArchInstalled) "RetroArch" else "Finish",
            )
            SetupStep.VITA -> VitaPage(
                state = state,
                onLink = { vitaPicker.launch(null) },
                onForget = viewModel::forgetVitaFolder,
                onContinue = { viewModel.nextStep() },
                nextLabel = if (state.retroArchInstalled) "RetroArch" else "Finish",
            )
            SetupStep.RETROARCH -> RetroArchPage(
                state = state,
                onLink = { retroPicker.launch(null) },
                onRedetect = viewModel::redetectRetroArchCores,
                onUnlink = viewModel::unlinkRetroArch,
                onContinue = { viewModel.nextStep() },
            )
            SetupStep.FINISH -> FinishPage(
                state = state,
                onOpenLibraryManager = onOpenLibraryManager,
                onFinish = onBack,
            )
        }
    }
}

private fun headingFor(step: SetupStep): String = when (step) {
    SetupStep.WELCOME     -> "Welcome to Play Field Portal."
    SetupStep.ROM_ROOTS   -> "Choose your ROM folders."
    SetupStep.MUSIC       -> "Choose your music folders."
    SetupStep.VIDEO       -> "Choose your video folders."
    SetupStep.PHOTO       -> "Choose your photo folders."
    SetupStep.ARTWORK     -> "Choose your artwork folder."
    SetupStep.SERVICES    -> "Connect your artwork sources."
    SetupStep.ACHIEVEMENTS -> "Connect your achievement services."
    SetupStep.VITA        -> "Set your Vita data folder."
    SetupStep.RETROARCH   -> "Link RetroArch's cores folder."
    SetupStep.FINISH      -> "You're all set!"
}

private fun hintFor(step: SetupStep): String? = when (step) {
    SetupStep.WELCOME   -> "A few short steps to point the launcher at your stuff — every step is optional and can be changed later in Settings."
    SetupStep.ROM_ROOTS -> "Add one or more root folders — each console's games live in a subfolder under them."
    SetupStep.MUSIC     -> "Add several roots to span internal storage and an SD card."
    SetupStep.VIDEO     -> "Add several roots to span internal storage and an SD card."
    SetupStep.PHOTO     -> "Add several roots to span internal storage and an SD card."
    SetupStep.ARTWORK   -> "One folder hosts the artwork library — you can import into it right after."
    SetupStep.SERVICES  -> "All optional and free. SteamGridDB, IGDB, and ScreenScraper fetch game artwork and metadata."
    SetupStep.ACHIEVEMENTS -> "RetroAchievements and Steam track achievements as Shiba Coins."
    SetupStep.VITA      -> "Vita3K is installed — one grant links every installed Vita title for discovery and trophies."
    SetupStep.RETROARCH -> "Lets the launcher know exactly which cores you have, so only those are offered."
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
        sublabel = "Choose your ROM roots first",
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
    onTestIgdb: (String, String) -> Unit,
    onConnectIgdb: (String, String) -> Unit,
    onTestSs: (String, String) -> Unit,
    onConnectSs: (String, String) -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    var sgdbKeyDraft by remember(state.hasSgdb) { mutableStateOf("") }
    var igdbIdDraft by remember(state.hasIgdb) { mutableStateOf("") }
    var igdbSecretDraft by remember(state.hasIgdb) { mutableStateOf("") }
    var ssUserDraft by remember(state.hasScreenScraper) { mutableStateOf("") }
    var ssPassDraft by remember(state.hasScreenScraper) { mutableStateOf("") }

    WizardInfoText(
        "All accounts are optional and free. SteamGridDB, IGDB, and ScreenScraper fetch game " +
            "artwork and metadata for your library."
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
private fun VitaPage(
    state: InitialSetupUiState,
    onLink: () -> Unit,
    onForget: () -> Unit,
    onContinue: () -> Unit,
    nextLabel: String,
) {
    val folder = state.vitaFolderName
    if (folder == null) {
        WizardInfoText(
            "Vita3K is installed. Grant its data (ux0) folder — often shared-storage " +
                "e.g. Roms/vita/ux0 — so PFP can discover installed Vita titles and read " +
                "trophies without granting per game."
        )
    } else {
        WizardRootRow(
            name = folder,
            sublabel = "Vita3K data folder — use ✎ to pick a different one",
            onEdit = onLink,
            onRemove = onForget,
        )
    }
    WizardRow(
        label = if (folder == null) "Set Vita3K Data Folder" else "Change Vita3K Data Folder",
        sublabel = if (folder == null) "Grant the ux0 folder (or the folder that contains it)"
                   else "Pick a different folder — files are never touched",
        onClick = onLink,
    )
    if (folder != null) {
        WizardRow(
            label = "Release Vita Data Folder",
            sublabel = "Unlink it without touching any files",
            onClick = onForget,
        )
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
    WizardContinueRow("Finish", onContinue)
}

@Composable
private fun FinishPage(
    state: InitialSetupUiState,
    onOpenLibraryManager: () -> Unit,
    onFinish: () -> Unit,
) {
    if (!state.anyFolderSet) {
        WizardInfoText(
            "Nothing was configured yet — every folder and service can be added anytime from Settings."
        )
    }
    WizardSectionHeader("Summary")
    WizardValueRow(label = "ROM Library", value = rootsShortLabel(state.romRoots))
    WizardValueRow(label = "Music", value = rootsShortLabel(state.musicRoots))
    WizardValueRow(label = "Video", value = rootsShortLabel(state.videoRoots))
    WizardValueRow(label = "Photo", value = rootsShortLabel(state.photoRoots))
    WizardValueRow(label = "Artwork Library", value = state.artworkFolderName ?: "Not set")
    WizardValueRow(label = "SteamGridDB", value = if (state.hasSgdb) "Connected" else "Not set")
    WizardValueRow(label = "IGDB (Twitch)", value = state.igdbClientId.ifBlank { "Not set" })
    if (state.ssEnabled) {
        WizardValueRow(label = "ScreenScraper", value = state.ssUsername.ifBlank { "Not set" })
    }
    WizardValueRow(label = "RetroAchievements", value = state.raUsername.ifBlank { "Not set" })
    WizardValueRow(label = "Steam", value = if (state.hasSteam) "Connected" else "Not set")
    if (state.vita3KInstalled) {
        WizardValueRow(label = "Vita Data Folder", value = state.vitaFolderName ?: "Not set")
    }
    if (state.retroArchInstalled) {
        WizardValueRow(
            label = "RetroArch",
            value = if (state.retroArchLinked) "${state.retroArchCoreCount ?: 0} cores" else "Not linked",
        )
    }

    Spacer(Modifier.height(4.dp))
    if (state.romRoots.isNotEmpty()) {
        WizardRow(
            label = "Open Library Manager",
            sublabel = "Add consoles and scan the ROM roots you just set",
            onClick = onOpenLibraryManager,
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
        hint = "All optional and free. SteamGridDB, IGDB, and ScreenScraper fetch game artwork and metadata.",
    ) {
        ServicesPage(
            state = InitialSetupUiState(
                hasSgdb = true,
                igdbClientId = "client_id_abc",
                ssEnabled = true,
                ssUsername = "scraper_user",
            ),
            onConnectSgdb = {},
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
private fun VitaPagePreview() {
    WizardPagePreview(
        stepNumber = 9,
        heading = "Set your Vita data folder.",
        hint = "Vita3K is installed — one grant links every installed Vita title for discovery and trophies.",
    ) {
        VitaPage(
            state = InitialSetupUiState(
                vita3KInstalled = true,
                vitaFolderName = "Roms/vita/ux0",
            ),
            onLink = {},
            onForget = {},
            onContinue = {},
            nextLabel = "RetroArch",
        )
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
                retroArchInstalled = true,
                retroArchLinked = true,
                retroArchCoreCount = 42,
            ),
            onLink = {},
            onRedetect = {},
            onUnlink = {},
            onContinue = {},
        )
    }
}

@CombinedPreviews
@Composable
private fun FinishPagePreview() {
    WizardPagePreview(
        stepNumber = 11,
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
                retroArchInstalled = true,
                retroArchLinked = true,
                retroArchCoreCount = 42,
            ),
            onOpenLibraryManager = {},
            onFinish = {},
        )
    }
}