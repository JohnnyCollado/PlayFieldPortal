package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.data.repository.MediaRootKind
import com.playfieldportal.feature.settings.viewmodel.InitialSetupUiState
import com.playfieldportal.feature.settings.viewmodel.InitialSetupViewModel
import com.playfieldportal.feature.settings.viewmodel.SetupStep

// Which folder slot the single SAF picker is currently serving.
private enum class FolderSlot { ROM, MUSIC, VIDEO, PHOTO, ARTWORK }

/**
 * First-run setup wizard: Welcome -> Root Folders -> Online Services -> Finish. Everything is
 * optional — each page can be skipped and the whole wizard exited at any time with Back. Values
 * are written through the same stores as the individual settings screens, so this is purely a
 * guided front door, not a second configuration system.
 */
@Composable
fun InitialSetupScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // First (automatic) run: Back cannot exit from the Welcome page, so a stray B press never
    // drops a brand-new user onto an unconfigured XMB. Leaving is explicit — Skip Setup or
    // Finish. Re-runs from Settings back out normally.
    firstRun: Boolean = false,
    onOpenLibraryManager: () -> Unit = {},
    viewModel: InitialSetupViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // The ViewModel outlives this overlay — snap back to page one whenever the wizard closes,
    // so a later re-run from Settings starts at the beginning instead of resuming mid-flow.
    DisposableEffect(Unit) {
        onDispose { viewModel.resetWizard() }
    }

    var pendingSlot by remember { mutableStateOf<FolderSlot?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val slot = pendingSlot
        pendingSlot = null
        if (uri != null && slot != null) when (slot) {
            FolderSlot.ROM     -> viewModel.onRomRootPicked(uri)
            FolderSlot.MUSIC   -> viewModel.onMediaRootPicked(MediaRootKind.MUSIC, uri)
            FolderSlot.VIDEO   -> viewModel.onMediaRootPicked(MediaRootKind.VIDEO, uri)
            FolderSlot.PHOTO   -> viewModel.onMediaRootPicked(MediaRootKind.PHOTO, uri)
            FolderSlot.ARTWORK -> viewModel.onArtworkFolderPicked(uri)
        }
    }
    fun pick(slot: FolderSlot) {
        pendingSlot = slot
        folderPicker.launch(null)
    }

    val subtitle = when (state.step) {
        SetupStep.WELCOME  -> "Welcome"
        SetupStep.FOLDERS  -> "Root Folders"
        SetupStep.SERVICES -> "Online Services"
        SetupStep.FINISH   -> "All Set"
    }

    // key(step): each page mounts a fresh scaffold, so focus starts on its first row.
    key(state.step) {
        SettingsScaffold(
            title    = "Initial Setup",
            subtitle = subtitle,
            onBack   = { if (!viewModel.previousStep() && !firstRun) onBack() },
            modifier = modifier,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                state.message?.let { message ->
                    SettingsRow(
                        label    = message,
                        sublabel = "Tap to dismiss",
                        onClick  = { viewModel.dismissMessage() },
                    )
                }

                when (state.step) {
                    SetupStep.WELCOME  -> WelcomePage(
                        onStart = { viewModel.nextStep() },
                        onSkip  = onBack,
                    )
                    SetupStep.FOLDERS  -> FoldersPage(
                        state  = state,
                        onPickRom     = { pick(FolderSlot.ROM) },
                        onPickMusic   = { pick(FolderSlot.MUSIC) },
                        onPickVideo   = { pick(FolderSlot.VIDEO) },
                        onPickPhoto   = { pick(FolderSlot.PHOTO) },
                        onPickArtwork = { pick(FolderSlot.ARTWORK) },
                        onContinue    = { viewModel.nextStep() },
                    )
                    SetupStep.SERVICES -> ServicesPage(
                        state     = state,
                        viewModel = viewModel,
                        onContinue = { viewModel.nextStep() },
                    )
                    SetupStep.FINISH   -> FinishPage(
                        state = state,
                        onOpenLibraryManager = onOpenLibraryManager,
                        onFinish = onBack,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntroText(text: String) {
    Text(
        text     = text,
        color    = SettingsSubtext,
        fontSize = 13.sp,
        lineHeight = 19.sp,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 16.dp),
    )
}

@Composable
private fun WelcomePage(onStart: () -> Unit, onSkip: () -> Unit) {
    IntroText(
        "Welcome to Play Field Portal. This quick setup points the launcher at your media " +
            "folders and connects the online services used for artwork and achievements. " +
            "Every step is optional, and everything here can be changed later in Settings.",
    )
    SettingsRow(
        label    = "Get Started",
        sublabel = "Choose your root folders first",
        onClick  = onStart,
    )
    SettingsRow(
        label    = "Skip Setup",
        sublabel = "Go straight to the launcher — run Initial Setup from Settings anytime",
        onClick  = onSkip,
    )
}

@Composable
private fun FoldersPage(
    state: InitialSetupUiState,
    onPickRom: () -> Unit,
    onPickMusic: () -> Unit,
    onPickVideo: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickArtwork: () -> Unit,
    onContinue: () -> Unit,
) {
    IntroText(
        "Pick the top-level folder for each kind of content. Subfolders are handled " +
            "automatically — consoles under the ROM root, libraries under the media roots.",
    )
    SettingsGroup("Root Folders")
    SettingsValueRow(
        label    = "ROM Library",
        sublabel = if (state.romRootName != null) "Tap to pick a different folder"
                   else "Root folder with one subfolder per console",
        value    = state.romRootName ?: "Tap to choose",
        onClick  = onPickRom,
    )
    SettingsValueRow(
        label    = "Music",
        sublabel = if (state.musicRootName != null) "Tap to pick a different folder"
                   else "Root folder for your music collection",
        value    = state.musicRootName ?: "Tap to choose",
        onClick  = onPickMusic,
    )
    SettingsValueRow(
        label    = "Video",
        sublabel = if (state.videoRootName != null) "Tap to pick a different folder"
                   else "Root folder for your video collection",
        value    = state.videoRootName ?: "Tap to choose",
        onClick  = onPickVideo,
    )
    SettingsValueRow(
        label    = "Photo",
        sublabel = if (state.photoRootName != null) "Tap to pick a different folder"
                   else "Root folder for your photo collection",
        value    = state.photoRootName ?: "Tap to choose",
        onClick  = onPickPhoto,
    )
    SettingsValueRow(
        label    = "Artwork Library",
        sublabel = if (state.artworkFolderName != null) "Tap to pick a different folder"
                   else "Writable folder for game artwork (set up automatically)",
        value    = state.artworkFolderName ?: "Tap to choose",
        onClick  = onPickArtwork,
    )
    Spacer(Modifier.height(8.dp))
    SettingsRow(
        label    = "Continue",
        sublabel = "Next: online services",
        onClick  = onContinue,
    )
}

@Composable
private fun ServicesPage(
    state: InitialSetupUiState,
    viewModel: InitialSetupViewModel,
    onContinue: () -> Unit,
) {
    // Drafts reset once the matching connection state flips (a successful connect clears them).
    var sgdbKeyDraft by remember(state.hasSgdb) { mutableStateOf("") }
    var igdbIdDraft by remember(state.hasIgdb) { mutableStateOf("") }
    var igdbSecretDraft by remember(state.hasIgdb) { mutableStateOf("") }
    var ssUserDraft by remember(state.hasScreenScraper) { mutableStateOf("") }
    var ssPassDraft by remember(state.hasScreenScraper) { mutableStateOf("") }
    var raUserDraft by remember(state.hasRetroAchievements) { mutableStateOf("") }
    var raKeyDraft by remember(state.hasRetroAchievements) { mutableStateOf("") }
    var steamIdDraft by remember(state.hasSteam) { mutableStateOf("") }
    var steamKeyDraft by remember(state.hasSteam) { mutableStateOf("") }

    IntroText(
        "All accounts are optional and free. SteamGridDB, IGDB, and ScreenScraper fetch game " +
            "artwork and metadata; RetroAchievements and Steam track your achievements as " +
            "Shiba Coins. Already-connected services can be replaced by entering new details.",
    )

    // ── SteamGridDB ───────────────────────────────────────────────────────────
    SettingsGroup("SteamGridDB")
    if (state.hasSgdb) {
        SettingsValueRow(label = "API Key", value = "Connected")
    }
    SettingsTextFieldRow(
        label         = if (state.hasSgdb) "API Key (saved)" else "API Key",
        value         = sgdbKeyDraft,
        onValueChange = { sgdbKeyDraft = it },
        placeholder   = if (state.hasSgdb) "••••••••  (tap to replace)" else "Paste your SteamGridDB key",
        isPassword    = true,
        helper        = "Get a free key at steamgriddb.com/api",
    )
    if (sgdbKeyDraft.isNotBlank()) {
        SettingsRow(
            label   = "Connect SteamGridDB",
            onClick = { viewModel.connectSgdb(sgdbKeyDraft) },
        )
    }

    // ── IGDB (Twitch) ─────────────────────────────────────────────────────────
    SettingsGroup("IGDB (Twitch)")
    if (state.hasIgdb) {
        SettingsValueRow(label = "Client ID", value = state.igdbClientId)
    }
    SettingsTextFieldRow(
        label         = if (state.hasIgdb) "Client ID (saved)" else "Client ID",
        value         = igdbIdDraft,
        onValueChange = { igdbIdDraft = it },
        placeholder   = if (state.hasIgdb) "Tap to replace" else "Twitch Client ID",
    )
    SettingsTextFieldRow(
        label         = if (state.hasIgdb) "Client Secret (saved)" else "Client Secret",
        value         = igdbSecretDraft,
        onValueChange = { igdbSecretDraft = it },
        placeholder   = if (state.hasIgdb) "••••••••  (tap to replace)" else "Twitch Client Secret",
        isPassword    = true,
        helper        = "Create app at dev.twitch.tv — improves fallback coverage for modern games",
    )
    state.igdbStatus?.let {
        SettingsRow(label = it, sublabel = "Tap to dismiss", onClick = { viewModel.dismissIgdbStatus() })
    }
    if (igdbIdDraft.isNotBlank() && igdbSecretDraft.isNotBlank()) {
        SettingsRow(
            label   = "Test Credentials",
            onClick = { viewModel.testIgdbCredentials(igdbIdDraft, igdbSecretDraft) },
        )
        SettingsRow(
            label   = "Connect IGDB",
            onClick = { viewModel.connectIgdb(igdbIdDraft, igdbSecretDraft) },
        )
    }

    // ── ScreenScraper (only when the build ships dev credentials) ─────────────
    if (state.ssEnabled) {
        SettingsGroup("ScreenScraper")
        if (state.hasScreenScraper) {
            SettingsValueRow(label = "Connected as", value = state.ssUsername)
        }
        SettingsTextFieldRow(
            label         = if (state.hasScreenScraper) "Username (saved)" else "Username",
            value         = ssUserDraft,
            onValueChange = { ssUserDraft = it },
            placeholder   = if (state.hasScreenScraper) "Tap to replace" else "ScreenScraper username",
        )
        SettingsTextFieldRow(
            label         = if (state.hasScreenScraper) "Password (saved)" else "Password",
            value         = ssPassDraft,
            onValueChange = { ssPassDraft = it },
            placeholder   = if (state.hasScreenScraper) "••••••••  (tap to replace)" else "ScreenScraper password",
            isPassword    = true,
            helper        = "Free account at screenscraper.fr — raises the scrape rate limit and daily quota",
        )
        state.ssStatus?.let {
            SettingsRow(label = it, sublabel = "Tap to dismiss", onClick = { viewModel.dismissSsStatus() })
        }
        if (ssUserDraft.isNotBlank() && ssPassDraft.isNotBlank()) {
            SettingsRow(
                label   = "Test Account",
                onClick = { viewModel.testSsCredentials(ssUserDraft, ssPassDraft) },
            )
            SettingsRow(
                label   = "Connect ScreenScraper",
                onClick = { viewModel.connectScreenScraper(ssUserDraft, ssPassDraft) },
            )
        }
    }

    // ── RetroAchievements ─────────────────────────────────────────────────────
    SettingsGroup("RetroAchievements")
    if (state.hasRetroAchievements) {
        SettingsValueRow(label = "Connected as", value = state.raUsername)
    }
    SettingsTextFieldRow(
        label         = if (state.hasRetroAchievements) "Username (saved)" else "Username",
        value         = raUserDraft,
        onValueChange = { raUserDraft = it },
        placeholder   = if (state.hasRetroAchievements) "Tap to replace" else "Your RA username",
    )
    SettingsTextFieldRow(
        label         = if (state.hasRetroAchievements) "Web API Key (saved)" else "Web API Key",
        value         = raKeyDraft,
        onValueChange = { raKeyDraft = it },
        placeholder   = if (state.hasRetroAchievements) "••••••••  (tap to replace)" else "Paste your RA Web API key",
        isPassword    = true,
        helper        = "retroachievements.org → Settings → Keys → Web API Key",
    )
    if (raUserDraft.isNotBlank() && raKeyDraft.isNotBlank()) {
        SettingsRow(
            label   = "Connect RetroAchievements",
            onClick = { viewModel.connectRetroAchievements(raUserDraft, raKeyDraft) },
        )
    }

    // ── Steam ─────────────────────────────────────────────────────────────────
    SettingsGroup("Steam")
    if (state.hasSteam) {
        SettingsValueRow(label = "SteamID64", value = state.steamId64)
    }
    SettingsTextFieldRow(
        label         = if (state.hasSteam) "SteamID64 or vanity name (saved)" else "SteamID64 or vanity name",
        value         = steamIdDraft,
        onValueChange = { steamIdDraft = it },
        placeholder   = if (state.hasSteam) "Tap to replace" else "7656119… or your custom URL name",
    )
    SettingsTextFieldRow(
        label         = if (state.hasSteam) "Web API Key (saved)" else "Web API Key",
        value         = steamKeyDraft,
        onValueChange = { steamKeyDraft = it },
        placeholder   = if (state.hasSteam) "••••••••  (tap to replace)" else "Paste your Steam Web API key",
        isPassword    = true,
        helper        = "steamcommunity.com/dev/apikey — your game details must be public",
    )
    if (steamIdDraft.isNotBlank() && steamKeyDraft.isNotBlank()) {
        SettingsRow(
            label   = "Connect Steam",
            onClick = { viewModel.connectSteam(steamIdDraft, steamKeyDraft) },
        )
    }

    Spacer(Modifier.height(8.dp))
    SettingsRow(
        label    = "Continue",
        sublabel = "Review and finish",
        onClick  = onContinue,
    )
}

@Composable
private fun FinishPage(
    state: InitialSetupUiState,
    onOpenLibraryManager: () -> Unit,
    onFinish: () -> Unit,
) {
    IntroText(
        if (state.anyFolderSet) {
            "Setup complete. Everything below can be adjusted anytime from Settings."
        } else {
            "Setup complete. Nothing was configured yet — every folder and service can be " +
                "added anytime from Settings."
        },
    )
    SettingsGroup("Summary")
    SettingsValueRow(label = "ROM Library",      value = state.romRootName ?: "Not set")
    SettingsValueRow(label = "Music",            value = state.musicRootName ?: "Not set")
    SettingsValueRow(label = "Video",            value = state.videoRootName ?: "Not set")
    SettingsValueRow(label = "Photo",            value = state.photoRootName ?: "Not set")
    SettingsValueRow(label = "Artwork Library",  value = state.artworkFolderName ?: "Not set")
    SettingsValueRow(label = "SteamGridDB",      value = if (state.hasSgdb) "Connected" else "Not set")
    SettingsValueRow(label = "IGDB (Twitch)",    value = state.igdbClientId.ifBlank { "Not set" })
    if (state.ssEnabled) {
        SettingsValueRow(label = "ScreenScraper", value = state.ssUsername.ifBlank { "Not set" })
    }
    SettingsValueRow(label = "RetroAchievements", value = state.raUsername.ifBlank { "Not set" })
    SettingsValueRow(label = "Steam",             value = if (state.hasSteam) "Connected" else "Not set")

    Spacer(Modifier.height(8.dp))
    if (state.romRootName != null) {
        SettingsRow(
            label    = "Open Library Manager",
            sublabel = "Add your consoles and scan the ROM root you just set",
            onClick  = onOpenLibraryManager,
        )
    }
    SettingsRow(
        label    = "Finish",
        sublabel = "Head to the launcher",
        onClick  = onFinish,
    )
}
