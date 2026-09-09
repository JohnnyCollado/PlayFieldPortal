package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.ui.components.ControllerPromptItem
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
 * sounds and Boot Sound, which previews through its own ExoPlayer path
 * ([com.playfieldportal.core.ui.media.BootSoundPreviewer]) instead of SoundPool.
 *
 * There is no editor sub-screen: selecting a row opens the system picker directly, and the row's
 * own inline actions carry Preview and Use Default. That is how every other media assignment in
 * this app works (wallpaper, custom icons), and it keeps the whole feature on one screen.
 *
 * ## Controller shortcuts on the assignment rows
 *
 * Two face buttons act on the focused row, bound to PHYSICAL positions so they survive the
 * user's X/Y layout setting: the north-facing button (Y on Xbox/PS pads, X on Nintendo) resets
 * the row to the PFP default, and the west-facing button (X on Xbox/PS, Y on Nintendo) plays its
 * preview. Neither button has any other role on this screen, so no layout can collide with one.
 *
 * Mechanically: XMBViewModel forwards both presses into the settings layer as
 * OPEN_CONTEXT_MENU / CHANGE_SORT ("treated identically", per its own comment); the interceptor
 * below maps them back to physical positions through [AudioSettingsUiState.xyLayout]. The prompt
 * bar shows FIXED position glyphs ([ControllerPromptItem.fixed]) rather than semantic ones —
 * resolving through the layout mapping again would show the wrong buttons after an X/Y swap.
 */
@Composable
fun AudioSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AudioSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Remember the sound field being changed so focus can return to it after the picker or a
    // reset removes the field's inline action. Without this, the navigation fallback lands on
    // the bottom Reset row when the focused action disappears.
    var focusTargetSlot by remember { mutableStateOf<UiMediaSlot?>(null) }
    var focusRequestToken by remember { mutableIntStateOf(0) }
    var importWasActive by remember { mutableStateOf(false) }

    fun requestSoundFocus(slot: UiMediaSlot) {
        focusTargetSlot = slot
        focusRequestToken++
    }

    // ONE picker for all seven rows — the pending slot is held on the ViewModel, so the callback
    // does not need to close over which row launched it.
    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onSoundPicked(uri)
        } else {
            // Cancellation does not change import state, so restore immediately.
            focusTargetSlot?.let(::requestSoundFocus)
        }
    }

    fun pickFor(slot: UiMediaSlot) {
        requestSoundFocus(slot)
        viewModel.onPickerLaunchedFor(slot)
        soundPicker.launch(AUDIO_PICKER_MIME)
    }

    // Restore after the asynchronous import has finished, whether it succeeded or was rejected.
    // A replacement can leave the assignment set unchanged, so importing is the reliable
    // completion signal rather than waiting only for assignedSlots to differ.
    LaunchedEffect(state.importing) {
        if (state.importing) {
            importWasActive = true
        } else if (importWasActive) {
            importWasActive = false
            focusTargetSlot?.let(::requestSoundFocus)
        }
    }

    // Which assignment row the cursor is on right now — the north/west face-button shortcuts
    // operate on it. Toggle and reset rows never set it, so shortcuts are inert over them.
    var focusedSlot by remember { mutableStateOf<UiMediaSlot?>(null) }

    // True when [action] arrived from the NORTH-facing face button (Y on Xbox/PS pads, X on
    // Nintendo), resolved through the user's X/Y layout: under STANDARD the north button emits
    // OPEN_CONTEXT_MENU; under SWAPPED it emits CHANGE_SORT.
    fun isNorthFace(action: GamepadAction): Boolean = when (state.xyLayout) {
        XYLayout.STANDARD -> action == GamepadAction.OPEN_CONTEXT_MENU
        XYLayout.SWAPPED -> action == GamepadAction.CHANGE_SORT
    }

    // Same, for the WEST-facing face button (X on Xbox/PS, Y on Nintendo).
    fun isWestFace(action: GamepadAction): Boolean = when (state.xyLayout) {
        XYLayout.STANDARD -> action == GamepadAction.CHANGE_SORT
        XYLayout.SWAPPED -> action == GamepadAction.OPEN_CONTEXT_MENU
    }

    Box(modifier = modifier) {
        SettingsScaffold(
            title = "Settings",
            subtitle = "Sound",
            onBack = onBack,
            helperFooterItems = buildList {
                val focusedSound = focusedSlot
                if (focusedSound != null && focusedSound in state.assignedSlots) {
                    add(
                        ControllerPromptItem(
                            action = if (state.xyLayout == XYLayout.STANDARD) {
                                GamepadAction.OPEN_CONTEXT_MENU
                            } else {
                                GamepadAction.CHANGE_SORT
                            },
                            label = "Use Default",
                        )
                    )
                }
                if (focusedSound != null) {
                    add(
                        ControllerPromptItem(
                            action = if (state.xyLayout == XYLayout.STANDARD) {
                                GamepadAction.CHANGE_SORT
                            } else {
                                GamepadAction.OPEN_CONTEXT_MENU
                            },
                            label = "Preview",
                        )
                    )
                }
            },
            onInterceptAction = { action ->
                val slot = focusedSlot ?: return@SettingsScaffold false
                when {
                    isNorthFace(action) && slot in state.assignedSlots -> {
                        // The helper is only advertised while this row has a custom assignment,
                        // so the north-face shortcut is consumed only when it has real work to do.
                        // Restore the row after Use Default removes its inline action.
                        requestSoundFocus(slot)
                        viewModel.useDefault(slot)
                        true
                    }
                    isWestFace(action) -> {
                        viewModel.preview(slot)
                        true
                    }
                    else -> false
                }
            },
        ) {
            val focusRegistry = LocalSettingsFocusRegistry.current
            LaunchedEffect(focusRequestToken) {
                if (focusRequestToken > 0) {
                    // Wait until the reset action has been removed from the composition and the
                    // scaffold has finished its normal focus-recovery pass.
                    withFrameNanos { }
                    withFrameNanos { }
                    focusTargetSlot?.let { slot ->
                        runCatching { focusRegistry["audio_${slot.key}"]?.requestFocus() }
                    }
                }
            }

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
                    onFocusChangedExternal = {
                        if (it) focusedSlot = null
                    },
                    onToggle = { viewModel.setMenuSoundEnabled(it) },
                )

                SettingsGroup("Sound Assignments")

                // Keep the assignment rows composed while an import is in flight. Removing
                // them here unregisters their FocusRequesters; the navigation engine then
                // recovers to the only remaining selectable row (Reset Sound), so returning
                // from the picker appears to jump away from the sound field being edited.
                if (state.importing) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 48.dp, vertical = 8.dp),
                    )
                }
                AudioSettingsViewModel.SOUND_SLOTS.forEach { slot ->
                    val label = state.soundLabels[slot] ?: PFP_DEFAULT_LABEL
                    SoundAssignmentRow(
                        slot = slot,
                        value = label,
                        isAssigned = slot in state.assignedSlots,
                        showPreview = true,
                        onPick = { pickFor(slot) },
                        onPreview = { viewModel.preview(slot) },
                        onUseDefault = {
                            requestSoundFocus(slot)
                            viewModel.useDefault(slot)
                        },
                        onFocusChanged = { focused ->
                            focusedSlot = if (focused) slot else null
                        },
                    )
                }

                SettingsGroup("")

                SettingsRow(
                    label = "Reset Sound to Defaults",
                    sublabel = "Return every menu and boot sound to the bundled PFP sample and turn Menu Sounds back on",
                    onFocusChangedExternal = { if (it) focusedSlot = null },
                    onClick = { viewModel.requestReset() },
                )
            }
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
 * "Use Default" only appears while a custom sound is assigned — an action that would do nothing
 * is worse than no action for controller navigation, which has to step through every one.
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
    onFocusChanged: (Boolean) -> Unit,
) {
    SettingsRow(
        label = slot.displayName,
        focusKey = "audio_${slot.key}",
        onClick = onPick,
        onFocusChangedExternal = onFocusChanged,
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
