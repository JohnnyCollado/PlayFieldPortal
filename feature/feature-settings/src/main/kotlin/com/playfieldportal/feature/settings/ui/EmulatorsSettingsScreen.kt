package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.components.PfpModalSpec
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.launcher.DetectableApp
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.ADD_CUSTOM_EMULATOR_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.EmulatorsSettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.ProfileListItem
import com.playfieldportal.feature.settings.viewmodel.TestLaunchState

enum class EmulatorSettingsSection { INSTALLED, CUSTOM, RETROARCH }

/**
 * Whether the Maintenance group (Reset Emulator Configuration) is drawn. Every Emulators row in the
 * settings tree opens a section, so showing it only with no section left the reset unreachable
 * (survey U33); it lives with the detected profiles under Installed.
 */
internal fun showsEmulatorMaintenance(section: EmulatorSettingsSection?): Boolean =
    section == null || section == EmulatorSettingsSection.INSTALLED

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
        SettingsScaffold(title = stringResource(R.string.lib_emu_add_title), subtitle = stringResource(R.string.lib_emu_detecting), onBack = viewModel::cancelWizard, modifier = modifier) {
            EmulatorHint(stringResource(R.string.lib_emu_inspecting))
        }
        return
    }

    val modal = rememberSettingsModal(
        if (state.showResetConfirm) {
            PfpModalSpec.Confirm(
                key = "reset_emulator_config",
                title = stringResource(R.string.lib_emu_reset_title),
                message = stringResource(R.string.lib_emu_reset_message),
                confirmLabel = stringResource(R.string.lib_emu_reset),
                destructive = true,
                onConfirm = viewModel::confirmResetEmulatorConfig,
                onCancel = viewModel::cancelResetEmulatorConfig,
            )
        } else {
            null
        },
    )

    SettingsScaffold(
        title    = stringResource(R.string.settings_title),
        subtitle = stringResource(
            when (section) {
                EmulatorSettingsSection.INSTALLED -> R.string.lib_emu_subtitle_installed
                EmulatorSettingsSection.CUSTOM -> R.string.lib_emu_subtitle_custom
                EmulatorSettingsSection.RETROARCH -> R.string.lib_emu_subtitle_retroarch
                null -> R.string.lib_emu_title
            },
        ),
        onBack          = onBack,
        modifier        = modifier,
        restoreFocusKey = state.returnFocusKey,
        modalOpen       = modal.open,
        onInterceptAction = modal.intercept,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            if (section == null || section == EmulatorSettingsSection.INSTALLED) {
                val installedNonRetroArch = state.installedProfiles.filterNot(::isRetroArchProfile)
                SettingsGroup(stringResource(R.string.lib_emu_group_installed))

                if (installedNonRetroArch.isEmpty()) {
                    EmulatorHint(stringResource(R.string.lib_emu_none_installed))
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
                SettingsGroup(stringResource(R.string.lib_emu_group_available))

                if (availableNonRetroArch.isEmpty()) {
                    EmulatorHint(stringResource(R.string.lib_emu_all_installed))
                } else {
                    availableNonRetroArch.forEach { profile ->
                        EmulatorProfileRow(profile = profile, onEdit = null)
                    }
                }
            }

            if (section == null || section == EmulatorSettingsSection.CUSTOM) {
                SettingsGroup(stringResource(R.string.lib_emu_group_custom))

            if (state.customProfiles.isEmpty()) {
                EmulatorHint(stringResource(R.string.lib_emu_none_custom))
            } else {
                state.customProfiles.forEach { profile ->
                    EmulatorProfileRow(
                        profile = profile,
                        onEdit  = { viewModel.openEditor(profile.id) },
                    )
                }
            }

            SettingsRow(
                label    = stringResource(R.string.lib_emu_add_custom),
                sublabel = stringResource(R.string.lib_emu_add_custom_sub),
                focusKey = ADD_CUSTOM_EMULATOR_FOCUS_KEY,
                onClick  = { viewModel.startAddEmulatorWizard() },
            )
            }

            if (section == null || section == EmulatorSettingsSection.RETROARCH) {
            SettingsGroup(stringResource(R.string.lib_emu_group_ra_detection))

            EmulatorHint(
                when {
                    state.isDetectingCores ->
                        stringResource(R.string.lib_emu_ra_scanning)
                    state.retroArchLinked && state.retroArchCoreCount != null ->
                        pluralStringResource(R.plurals.lib_emu_ra_linked, state.retroArchCoreCount!!, state.retroArchCoreCount!!)
                    state.retroArchLinked ->
                        stringResource(R.string.lib_emu_ra_access_lost)
                    else ->
                        stringResource(R.string.lib_emu_ra_not_linked)
                }
            )

            SettingsRow(
                label    = stringResource(if (state.retroArchLinked) R.string.lib_emu_ra_relink else R.string.lib_emu_ra_link),
                sublabel = stringResource(R.string.lib_emu_ra_link_sub),
                focusKey = "retroarch_link",
                onClick  = { retroArchPicker.launch(null) },
            )

            SettingsGroup(stringResource(R.string.lib_emu_group_cores))
            if (state.retroArchCores.isEmpty()) {
                EmulatorHint(stringResource(R.string.lib_emu_ra_no_cores))
            } else {
                state.retroArchCores.forEach { core ->
                    SettingsRow(label = core, sublabel = stringResource(R.string.lib_emu_ra_core_available))
                }
            }

            if (state.retroArchLinked) {
                SettingsRow(
                    label    = stringResource(R.string.lib_emu_ra_rescan),
                    sublabel = stringResource(R.string.lib_emu_ra_rescan_sub),
                    onClick  = { viewModel.redetectRetroArchCores() },
                )
                SettingsRow(
                    label    = stringResource(R.string.lib_emu_ra_unlink),
                    sublabel = stringResource(R.string.lib_emu_ra_unlink_sub),
                    onClick  = { viewModel.unlinkRetroArch() },
                )
            }

            }

            if (showsEmulatorMaintenance(section)) SettingsGroup(stringResource(R.string.lib_emu_group_maintenance))

            if (showsEmulatorMaintenance(section)) SettingsRow(
                label    = stringResource(R.string.lib_emu_reset_row),
                sublabel = stringResource(R.string.lib_emu_reset_row_sub),
                focusKey = "reset_emulator_config",
                onClick  = { viewModel.requestResetEmulatorConfig() },
                destructive = true,
            )
        }
    }

    modal.Content()
}

@Composable
private fun WizardPickAppStep(
    apps: List<DetectableApp>,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title    = stringResource(R.string.lib_emu_add_title),
        subtitle = stringResource(R.string.lib_emu_pick_app),
        onBack   = onBack,
        modifier = modifier,
    ) {
        // Registered like the list screen above: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            SettingsGroup(stringResource(R.string.lib_emu_group_apps))
            if (apps.isEmpty()) {
                EmulatorHint(stringResource(R.string.lib_emu_no_apps))
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
        SettingsScaffold(title = stringResource(R.string.lib_emu_test_title), subtitle = stringResource(R.string.lib_emu_test_pick_rom), onBack = onBack, modifier = modifier) {
            // Registered like the list screen above: the scaffold needs a scroll owner here for its
            // chrome drag-to-scroll and for controller keep-in-view.
            val scrollState = rememberScrollState()
            LocalSettingsScrollStateRegistrar.current(scrollState)
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
            ) {
                SettingsGroup(stringResource(R.string.lib_emu_test_group_roms))
                if (test.roms.isEmpty()) {
                    EmulatorHint(stringResource(R.string.lib_emu_test_no_roms))
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
    SettingsScaffold(title = stringResource(R.string.lib_emu_test_title), subtitle = test.selectedRom.title, onBack = onBack, modifier = modifier) {
        // Registered like the list screen above: the scaffold needs a scroll owner here for its
        // chrome drag-to-scroll and for controller keep-in-view.
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        ) {
            test.preview?.let { p ->
                SettingsGroup(stringResource(R.string.lib_emu_preview_group))
                PreviewLine(stringResource(R.string.lib_emu_preview_package), p.packageName)
                PreviewLine(stringResource(R.string.lib_emu_preview_activity), p.activity ?: stringResource(R.string.lib_emu_preview_activity_default))
                PreviewLine(stringResource(R.string.lib_emu_preview_action), p.action.asString())
                PreviewLine(stringResource(R.string.lib_emu_preview_mime), p.mimeType ?: stringResource(R.string.lib_emu_none))
                PreviewLine(stringResource(R.string.lib_emu_preview_uri_mode), p.uriMode.asString())
                PreviewLine(stringResource(R.string.lib_emu_preview_rom), p.romRef.asString())
                if (p.extras.isNotEmpty()) PreviewLine(stringResource(R.string.lib_emu_preview_extras), p.extras.joinToString("\n"))
                if (p.flags.isNotEmpty()) PreviewLine(stringResource(R.string.lib_emu_preview_flags), p.flags.joinToString(", "))
            }

            test.resultMessage?.let { msg ->
                SettingsGroup(stringResource(R.string.lib_emu_result_group))
                Text(
                    text     = msg.asString(),
                    color    = if (test.isError) UnavailableColor else ModifiedBadgeColor,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp),
                )
            }

            SettingsGroup(stringResource(R.string.lib_emu_actions_group))
            if (test.canLaunch) {
                SettingsRow(label = stringResource(R.string.lib_emu_launch_now), sublabel = stringResource(R.string.lib_emu_launch_now_sub), onClick = onLaunch)
            }
            SettingsRow(label = stringResource(R.string.lib_emu_pick_other_rom), onClick = onPickOther)
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
                    Badge(stringResource(R.string.lib_emu_badge_unavailable), UnavailableColor)
                }
                if (profile.isAutoGenerated && !profile.userModified) {
                    Badge(stringResource(R.string.lib_emu_badge_auto), AutoBadgeColor)
                }
                if (profile.userModified) {
                    Badge(stringResource(R.string.lib_emu_badge_edited), ModifiedBadgeColor)
                }
                if (onEdit != null) {
                    Text(stringResource(R.string.lib_emu_edit), color = SettingsAccent)
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

@Composable
private fun buildSublabel(profile: ProfileListItem): String = stringResource(
    when {
        profile.autoSource == "retroarch-core" -> R.string.lib_emu_row_sub_retroarch_core
        profile.isAutoGenerated                -> R.string.lib_emu_row_sub_auto
        else                                   -> R.string.lib_emu_row_sub
    },
    profile.packageName,
    profile.intentType,
)

@Composable
private fun EmulatorHint(text: String) {
    Text(
        text     = text,
        color    = SettingsSubtext,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
    )
}
