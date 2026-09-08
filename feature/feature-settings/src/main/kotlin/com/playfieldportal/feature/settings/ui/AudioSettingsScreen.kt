package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.feature.settings.viewmodel.AudioSettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.PFP_DEFAULT_LABEL
import com.playfieldportal.themekit.UiMediaLimits

/**
 * MIME filter for the picker, taken from the import gate's accepted set so the two can never
 * disagree. `OpenDocument` takes an array; the gate re-checks the resolver's MIME anyway.
 */
private val AUDIO_PICKER_MIME = UiMediaLimits.AUDIO_MIME.toTypedArray()

/**
 * Interface ▸ Sound — the Menu Sounds toggle plus the seven sound assignments: the six menu
 * sounds and Boot Sound, which has NO Preview action (there is no [MenuSound] for it, and
 * Display ▸ Boot Sequence already previews the full presentation).
 *
 * There is no editor sub-screen: selecting a row opens the system picker directly, and the row's
 * own inline actions carry Preview and Use Default. That is how every other media assignment in
 * this app works (wallpaper, custom icons), and it keeps the whole feature on one screen.
 */
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudioSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // ONE picker for all six rows — the pending slot is held on the ViewModel, so the callback
    // does not need to close over which row launched it.
    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onSoundPicked(it) } }

    fun pickFor(slot: UiMediaSlot) {
        viewModel.onPickerLaunchedFor(slot)
        soundPicker.launch(AUDIO_PICKER_MIME)
    }

    SettingsScaffold(
        title = "Settings",
        subtitle = "Sound",
        onBack = onBack,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            SettingsGroup("Menu Sounds")

            SettingsToggleRow(
                label = "Menu Sounds",
                sublabel = "Play navigation, select, and launch sound effects",
                checked = state.menuSoundEnabled,
                onToggle = { viewModel.setMenuSoundEnabled(it) },
            )

            SettingsGroup("Sound Assignments")

            if (state.importing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 48.dp, vertical = 8.dp),
                )
            } else {
                AudioSettingsViewModel.SOUND_SLOTS.forEach { slot ->
                    val label = state.soundLabels[slot] ?: PFP_DEFAULT_LABEL
                    SoundAssignmentRow(
                        slot = slot,
                        value = label,
                        isAssigned = slot in state.assignedSlots,
                        // Boot Sound is ExoPlayer, not SoundPool — no quick audition here.
                        showPreview = slot.kind == UiMediaKind.SOUND,
                        onPick = { pickFor(slot) },
                        onPreview = { viewModel.preview(slot) },
                        onUseDefault = { viewModel.useDefault(slot) },
                    )
                }
            }

            SettingsGroup("")

            SettingsRow(
                label = "Reset Sound to Defaults",
                sublabel = "Return every menu and boot sound to the bundled PFP sample and turn Menu Sounds back on",
                onClick = { viewModel.requestReset() },
            )
        }
    }

    state.message?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissMessage,
            title = { Text("Couldn't use that sound") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissMessage) { Text("OK") }
            },
        )
    }

    if (state.confirmResetVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissReset,
            title = { Text("Reset Sound to Defaults?") },
            text = {
                Text(
                    "Every menu and boot sound returns to the bundled PFP sample and Menu Sounds " +
                        "is turned back on. Your Boot Video and GameBoot media are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmReset) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissReset) { Text("Cancel") }
            },
        )
    }
}

/**
 * One assignment row: the event's name, its current source, and its controller-reachable actions.
 * Preview only appears when [showPreview] (Boot Sound has no quick audition); "Use Default" only
 * appears while a custom sound is assigned — an action that would do nothing is worse than no
 * action for controller navigation, which has to step through every one.
 */
@Composable
private fun SoundAssignmentRow(
    slot: UiMediaSlot,
    value: String,
    isAssigned: Boolean,
    showPreview: Boolean,
    onPick: () -> Unit,
    onPreview: () -> Unit,
    onUseDefault: () -> Unit,
) {
    SettingsRow(
        label = slot.displayName,
        focusKey = "audio_${slot.key}",
        onClick = onPick,
        trailing = {
            Text(
                text = value,
                color = SettingsAccent,
                fontSize = 13.sp,
                style = TextStyle(shadow = SettingsTextShadow),
            )
        },
        actions = buildList {
            if (showPreview) {
                add(
                    SettingsRowAction(
                        "Preview ${slot.displayName}", onPreview,
                        actionFocusBackgroundColor = lerp(SettingsAccent, Color.Black, 0.50f),
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = "Preview ${slot.displayName}",
                            tint = SettingsAccent,
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                .padding(4.dp),
                        )
                    }
                )
            }
            if (isAssigned) {
                add(
                    SettingsRowAction(
                        "Use the PFP default for ${slot.displayName}", onUseDefault,
                        actionFocusBackgroundColor = lerp(Color(0xFFE55353), Color.Black, 0.50f),
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Use the PFP default for ${slot.displayName}",
                            tint = Color(0xFFE55353),
                            modifier = Modifier
                                .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                .padding(4.dp),
                        )
                    }
                )
            }
        },
    )
}
