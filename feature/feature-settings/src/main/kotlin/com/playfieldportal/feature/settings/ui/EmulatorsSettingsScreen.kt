package com.playfieldportal.feature.settings.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.launcher.DetectableApp
import com.playfieldportal.feature.settings.viewmodel.ADD_CUSTOM_EMULATOR_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.EmulatorsSettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.ProfileListItem
import com.playfieldportal.feature.settings.viewmodel.TestLaunchState

enum class EmulatorSettingsSection { INSTALLED, CUSTOM, RETROARCH }

private val AutoBadgeColor    = Color(0xFF4A9EFF)
private val UnavailableColor  = Color(0xFFFF6B6B)
private val ModifiedBadgeColor = Color(0xFF45C46A)

@Composable
fun EmulatorsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    section: EmulatorSettingsSection? = null,
    viewModel: EmulatorsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // SAF tree picker for linking RetroArch's folder so PFP can enumerate installed cores.
    val retroArchPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.linkRetroArch(it) } }

    state.editorState?.let { editor ->
        // Test-launch flow renders over the editor when active.
        state.testLaunch?.let { test ->
            TestLaunchFlow(
                test       = test,
                onPickRom  = viewModel::selectTestRom,
                onLaunch   = viewModel::launchTest,
                onPickOther = viewModel::pickDifferentTestRom,
                onBack     = viewModel::closeTestLaunch,
                modifier   = modifier,
            )
            return
        }

        EmulatorProfileEditorScreen(
            editorState           = editor,
            onNameChange          = viewModel::updateEditorName,
            onPackageNameChange   = viewModel::updateEditorPackageName,
            onActivityClassChange = viewModel::updateEditorActivityClass,
            onIntentTypeChange    = viewModel::updateEditorIntentType,
            onPlatformIdsChange   = viewModel::updateEditorPlatformIds,
            onMimeTypeChange      = viewModel::updateEditorMimeType,
            onUseFileUriChange    = viewModel::updateEditorUseFileUri,
            onUseSafUriChange     = viewModel::updateEditorUseSafUri,
            onCustomCommandChange = viewModel::updateEditorCustomCommand,
            onNotesChange         = viewModel::updateEditorNotes,
            onIntentActionChange  = viewModel::updateEditorIntentAction,
            onIntentExtrasChange  = viewModel::updateEditorIntentExtras,
            onIntentFlagsChange   = viewModel::updateEditorIntentFlags,
            onIntentCategoryChange = viewModel::updateEditorIntentCategory,
            onCoreChange          = viewModel::updateEditorCore,
            onExtensionsChange    = viewModel::updateEditorExtensions,
            onApplyTemplate       = viewModel::applyTemplate,
            onSave                = viewModel::saveEditorProfile,
            onCancel              = viewModel::closeEditor,
            onDelete              = if (!editor.isNew && editor.originalId != null) {
                { viewModel.deleteProfile(editor.originalId) }
            } else null,
            onTestLaunch          = viewModel::startTestLaunch,
            modifier              = modifier,
        )
        return
    }

    // Wizard step 1 — pick an installed app.
    state.wizardApps?.let { apps ->
        WizardPickAppStep(
            apps     = apps,
            onPick   = viewModel::selectWizardApp,
            onBack   = viewModel::cancelWizard,
            modifier = modifier,
        )
        return
    }

    // Brief loading windows: scanning apps, or inspecting the chosen app.
    if (state.isInspecting) {
        SettingsScaffold(title = "Add Emulator", subtitle = "Detecting…", onBack = viewModel::cancelWizard, modifier = modifier) {
            EmulatorHint("Inspecting installed apps…")
        }
        return
    }

    SettingsScaffold(
        title    = "Settings",
        subtitle = when (section) {
            EmulatorSettingsSection.INSTALLED -> "Emulator · Installed"
            EmulatorSettingsSection.CUSTOM -> "Emulator · Custom"
            EmulatorSettingsSection.RETROARCH -> "Emulator · RetroArch"
            null -> "Emulators"
        },
        onBack          = onBack,
        modifier        = modifier,
        restoreFocusKey = state.returnFocusKey,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            if (section == null || section == EmulatorSettingsSection.INSTALLED) {
                val installedNonRetroArch = state.installedProfiles.filterNot(::isRetroArchProfile)
                SettingsGroup("Installed")

                if (installedNonRetroArch.isEmpty()) {
                    EmulatorHint("No supported emulators detected on this device")
                } else {
                    installedNonRetroArch.forEach { profile ->
                        EmulatorProfileRow(
                            profile = profile,
                            onEdit  = { viewModel.openEditor(profile.id) },
                        )
                    }
                }
            }

            if (section == null || section == EmulatorSettingsSection.INSTALLED) {
                val availableNonRetroArch = state.availableProfiles.filterNot(::isRetroArchProfile)
                SettingsGroup("Available (Not Installed)")

                if (availableNonRetroArch.isEmpty()) {
                    EmulatorHint("All bundled profiles are installed")
                } else {
                    availableNonRetroArch.forEach { profile ->
                        EmulatorProfileRow(profile = profile, onEdit = null)
                    }
                }
            }

            if (section == null || section == EmulatorSettingsSection.CUSTOM) {
                SettingsGroup("Custom Profiles")

            if (state.customProfiles.isEmpty()) {
                EmulatorHint("No custom profiles yet")
            } else {
                state.customProfiles.forEach { profile ->
                    EmulatorProfileRow(
                        profile = profile,
                        onEdit  = { viewModel.openEditor(profile.id) },
                    )
                }
            }

            SettingsRow(
                label    = "Add Custom Emulator",
                sublabel = "Pick an app — we auto-detect its launch settings",
                focusKey = ADD_CUSTOM_EMULATOR_FOCUS_KEY,
                onClick  = { viewModel.startAddEmulatorWizard() },
            )
            }

            if (section == null || section == EmulatorSettingsSection.RETROARCH) {
            SettingsGroup("RetroArch Core Detection")

            EmulatorHint(
                when {
                    state.isDetectingCores ->
                        "Scanning RetroArch for installed cores…"
                    state.retroArchLinked && state.retroArchCoreCount != null ->
                        "Linked — ${state.retroArchCoreCount} core(s) detected. Only installed cores are offered, so a missing core can't cause a black screen."
                    state.retroArchLinked ->
                        "Linked, but access was lost (e.g. after reinstall). Re-link RetroArch to detect cores again."
                    else ->
                        "Not linked. PFP can't see which RetroArch cores are installed, so it offers them all — launching one that isn't installed shows a black screen. Link RetroArch to detect installed cores."
                }
            )

            SettingsRow(
                label    = if (state.retroArchLinked) "Re-link RetroArch Folder" else "Link RetroArch to Detect Cores",
                sublabel = "In the picker, choose RetroArch's folder (grant access) so PFP can read its installed cores",
                focusKey = "retroarch_link",
                onClick  = { retroArchPicker.launch(null) },
            )

            SettingsGroup("Available Cores")
            if (state.retroArchCores.isEmpty()) {
                EmulatorHint("No RetroArch cores detected yet.")
            } else {
                state.retroArchCores.forEach { core ->
                    SettingsRow(label = core, sublabel = "Available in RetroArch")
                }
            }

            if (state.retroArchLinked) {
                SettingsRow(
                    label    = "Re-scan Installed Cores",
                    sublabel = "Run after downloading more cores in RetroArch's Core Downloader",
                    onClick  = { viewModel.redetectRetroArchCores() },
                )
                SettingsRow(
                    label    = "Unlink RetroArch",
                    sublabel = "Go back to offering all cores unverified",
                    onClick  = { viewModel.unlinkRetroArch() },
                )
            }

            }

            if (section == null) SettingsGroup("Maintenance")

            if (section == null) SettingsRow(
                label    = "Reset Emulator Configuration",
                sublabel = "Clear launch settings and restore defaults. Your games, artwork, and saves are not affected.",
                focusKey = "reset_emulator_config",
                onClick  = { viewModel.requestResetEmulatorConfig() },
            )
        }
    }

    if (state.showResetConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::cancelResetEmulatorConfig,
            title = { Text("Reset Emulator Configuration?") },
            text  = {
                Text(
                    "This will clear all auto-detected and custom emulator launch settings, " +
                    "then restore bundled defaults.\n\n" +
                    "Your game library, ROM paths, artwork, metadata, and save data are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmResetEmulatorConfig) {
                    Text("Reset", color = UnavailableColor)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelResetEmulatorConfig) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun WizardPickAppStep(
    apps: List<DetectableApp>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title    = "Add Emulator",
        subtitle = "Pick an App",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("Installed Apps")
            if (apps.isEmpty()) {
                EmulatorHint("No launchable apps found.")
            } else {
                apps.forEach { app ->
                    SettingsRow(
                        label    = app.label,
                        sublabel = app.packageName,
                        onClick  = { onPick(app.packageName) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TestLaunchFlow(
    test: TestLaunchState,
    onPickRom: (Long) -> Unit,
    onLaunch: () -> Unit,
    onPickOther: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Step A — pick a ROM from the scanned library.
    if (test.selectedRom == null) {
        SettingsScaffold(title = "Test Launch", subtitle = "Pick a ROM", onBack = onBack, modifier = modifier) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            ) {
                SettingsGroup("Your ROMs")
                if (test.roms.isEmpty()) {
                    EmulatorHint("No scanned ROMs to test with. Scan a console first, or just save and try in the library.")
                } else {
                    test.roms.forEach { rom ->
                        SettingsRow(
                            label    = rom.title,
                            sublabel = rom.platformId.uppercase(),
                            onClick  = { onPickRom(rom.gameId) },
                        )
                    }
                }
            }
        }
        return
    }

    // Step B — intent preview + launch + result.
    SettingsScaffold(title = "Test Launch", subtitle = test.selectedRom.title, onBack = onBack, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            test.preview?.let { p ->
                SettingsGroup("Intent Preview")
                PreviewLine("Package", p.packageName)
                PreviewLine("Activity", p.activity ?: "(resolved by package)")
                PreviewLine("Action", p.action)
                PreviewLine("MIME", p.mimeType ?: "(none)")
                PreviewLine("URI mode", p.uriMode)
                PreviewLine("ROM", p.romRef)
                if (p.extras.isNotEmpty()) PreviewLine("Extras", p.extras.joinToString("\n"))
                if (p.flags.isNotEmpty()) PreviewLine("Flags", p.flags.joinToString(", "))
            }

            test.resultMessage?.let { msg ->
                SettingsGroup("Result")
                Text(
                    text     = msg,
                    color    = if (test.isError) UnavailableColor else ModifiedBadgeColor,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                )
            }

            SettingsGroup("Actions")
            if (test.canLaunch) {
                SettingsRow(label = "Launch Now", sublabel = "Send the intent to the app", onClick = onLaunch)
            }
            SettingsRow(label = "Pick a Different ROM", onClick = onPickOther)
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp)) {
        Text(text = label.uppercase(), color = AutoBadgeColor, fontSize = 10.sp)
        Text(text = value, color = SettingsText, fontSize = 13.sp)
    }
}

@Composable
private fun EmulatorProfileRow(
    profile: ProfileListItem,
    onEdit: (() -> Unit)?,
) {
    SettingsRow(
        label    = profile.name,
        sublabel = buildSublabel(profile),
        focusKey = profile.id,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!profile.isAvailable) {
                    Badge("Unavailable", UnavailableColor)
                }
                if (profile.isAutoGenerated && !profile.userModified) {
                    Badge("Auto", AutoBadgeColor)
                }
                if (profile.userModified) {
                    Badge("Edited", ModifiedBadgeColor)
                }
                if (onEdit != null) {
                    Text("Edit", color = SettingsAccent)
                }
            }
        },
        onClick = onEdit,
    )
}

@Composable
private fun Badge(label: String, color: Color) {
    Text(
        text     = label,
        color    = color,
        fontSize = 10.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

private fun isRetroArchProfile(profile: ProfileListItem): Boolean =
    profile.autoSource == "retroarch-core" ||
        profile.packageName.equals("com.retroarch", ignoreCase = true) ||
        profile.packageName.contains("retroarch", ignoreCase = true) ||
        profile.name.contains("retroarch", ignoreCase = true)

private fun buildSublabel(profile: ProfileListItem): String {
    val base = "${profile.packageName}  ·  ${profile.intentType}"
    return when {
        profile.autoSource == "retroarch-core" -> "$base  ·  RetroArch core"
        profile.isAutoGenerated                -> "$base  ·  auto-detected"
        else                                   -> base
    }
}

@Composable
private fun EmulatorHint(text: String) {
    Text(
        text     = text,
        color    = SettingsSubtext,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
    )
}
