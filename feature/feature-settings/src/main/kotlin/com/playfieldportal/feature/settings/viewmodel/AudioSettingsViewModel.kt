package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.ui.media.UiMediaAudioPlayer
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// Must match XMBViewModel / DisplaySettingsViewModel — the SAME pref this screen inherited when
// the row moved out of Display ▸ Sound. Kept as "enabled" rather than migrated to a "mute" key:
// inverting the polarity would make a restored old backup mean the opposite of what it said.
private val KEY_MENU_SOUND = booleanPreferencesKey("sound_menu_enabled")

/** The label shown when a slot has no user assignment. */
const val PFP_DEFAULT_LABEL = "PFP Default"

data class AudioSettingsUiState(
    val menuSoundEnabled: Boolean = true,
    /** Per-sound-slot row summary: the imported file's name, or [PFP_DEFAULT_LABEL]. */
    val soundLabels: Map<UiMediaSlot, String> = emptyMap(),
    /** Slots the user has actually assigned — drives whether "Use Default" is offered. */
    val assignedSlots: Set<UiMediaSlot> = emptySet(),
    val message: String? = null,
    val importing: Boolean = false,
    val confirmResetVisible: Boolean = false,
    /**
     * The user's X/Y face-button layout. The screen's controller shortcuts are bound to
     * PHYSICAL positions (north face = Use Default, west face = Preview) so they survive the
     * X/Y swap setting; this is what maps those positions onto the actions that arrive.
     */
    val xyLayout: XYLayout = XYLayout.STANDARD,
)

/**
 * Interface ▸ Sound. Owns the Menu Sounds toggle (moved here from Display ▸ Sound) and the
 * SEVEN sound assignments — the six menu sounds plus Boot Sound, which lives here as its
 * seventh row while staying an AUDIO_TRACK slot (Display ▸ Boot Sequence reaches the same slot).
 *
 * Every row previews: the six menu sounds through [MenuSoundPlayer], Boot Sound through
 * [UiMediaAudioPlayer] (no [MenuSound] exists for it — it is boot music, not a UI tick).
 *
 * Boot VIDEO and GameBoot's clip are deliberately NOT reachable from here — they live with their
 * own presentations under Display, and [confirmReset] must never touch them.
 */
@HiltViewModel
class AudioSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: UiMediaStore,
    private val menuSound: MenuSoundPlayer,
    private val bootSoundPreviewer: UiMediaAudioPlayer,
    private val controllerLayout: ControllerLayoutRepository,
) : ViewModel() {

    private val _message = MutableStateFlow<String?>(null)
    private val _importing = MutableStateFlow(false)
    private val _confirmResetVisible = MutableStateFlow(false)

    /** The slot whose picker is open — set before launching, read when the Uri comes back. */
    private var pendingSlot: UiMediaSlot? = null

    val uiState: StateFlow<AudioSettingsUiState> = combine(
        context.pfpDataStore.data,
        controllerLayout.prefs,
        _message,
        _importing,
        _confirmResetVisible,
    ) { prefs, layout, message, importing, confirmReset ->
        // Re-read on every prefs emission: the stamp bumps inside the same store, so an import
        // or a clear re-runs this and the row summaries follow the directory.
        val assigned = store.assignments().keys
        val labels = SOUND_SLOTS.associateWith { slot ->
            if (slot in assigned) prefs[UiMediaStore.displayNameKey(slot)] ?: "Custom sound"
            else PFP_DEFAULT_LABEL
        }
        AudioSettingsUiState(
            menuSoundEnabled = prefs[KEY_MENU_SOUND] ?: true,
            soundLabels = labels,
            // Not a kind filter anymore: BOOT_AUDIO is AUDIO_TRACK but IS a row on this screen,
            // while BOOT_VIDEO / GAMEBOOT_VIDEO are assignments on other screens' concerns. Filter
            // by "on this screen" so the Use Default action appears on the Boot row and nowhere
            // it should not.
            assignedSlots = assigned.filterTo(HashSet()) { it in SCREEN_SLOTS },
            message = message,
            importing = importing,
            confirmResetVisible = confirmReset,
            xyLayout = layout.xyLayout,
        )
    }
        // store.assignments() is a directory listing — cheap, but still file IO.
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AudioSettingsUiState())

    fun setMenuSoundEnabled(enabled: Boolean) = viewModelScope.launch {
        context.pfpDataStore.edit { it[KEY_MENU_SOUND] = enabled }
    }

    /** Records which row the picker was launched for. Called immediately before launching it. */
    fun onPickerLaunchedFor(slot: UiMediaSlot) {
        pendingSlot = slot
    }

    /**
     * Handles the picked Uri for the pending slot. A rejection surfaces its reason and leaves the
     * previous assignment untouched — the store stages the copy and only commits on a pass.
     */
    fun onSoundPicked(uri: Uri) {
        val slot = pendingSlot ?: return
        pendingSlot = null
        viewModelScope.launch {
            _importing.value = true
            val result = store.import(slot, uri)
            _importing.value = false
            if (result.ok) {
                // The player reloads on the stamp anyway; this just removes the race between the
                // user pressing Preview and the DataStore emission arriving.
                menuSound.refreshCustomSamples()
            } else {
                // A refused pick is an ERROR event, not just a dialog: the user did something
                // and the launcher said no — that is exactly what the Error row customizes.
                menuSound.play(MenuSound.ERROR)
                _message.value = result.message
            }
        }
    }

    /**
     * Auditions [slot]'s current sound, custom or default, even while Menu Sounds is off. The six
     * menu sounds play through [MenuSoundPlayer]; Boot Sound plays through [UiMediaAudioPlayer]
     * — custom assignment first, bundled default otherwise — so its row previews like every
     * other one.
     */
    fun preview(slot: UiMediaSlot) {
        val event = MenuSound.entries.firstOrNull { it.slot == slot }
        if (event != null) {
            menuSound.play(event, ignoreMute = true)
        } else {
            bootSoundPreviewer.play(customPath = store.pathFor(slot))
        }
    }

    /** Drops [slot]'s custom file, returning that event to its bundled sample. */
    fun useDefault(slot: UiMediaSlot) = viewModelScope.launch {
        store.clear(slot)
    }

    fun requestReset() { _confirmResetVisible.value = true }
    fun dismissReset() { _confirmResetVisible.value = false }

    /**
     * "Reset Sound to Defaults": clears every SOUND slot PLUS Boot Sound (it is a row on this
     * screen) and restores the Menu Sounds toggle. Never touches the boot video or GameBoot's
     * clip — those belong to their own screens' resets.
     */
    fun confirmReset() = viewModelScope.launch {
        _confirmResetVisible.value = false
        menuSound.play(MenuSound.CONFIRM)
        store.clearAll(UiMediaKind.SOUND)
        store.clear(UiMediaSlot.BOOT_AUDIO)
        context.pfpDataStore.edit { it[KEY_MENU_SOUND] = true }
    }

    fun dismissMessage() { _message.value = null }

    /** Stops a running Boot Sound preview (a no-op when none is playing). */
    fun stopBootPreview() = bootSoundPreviewer.stop()

    /** A leaving screen must not leave a boot preview sounding behind it. */
    override fun onCleared() {
        stopBootPreview()
    }

    companion object {
        /**
         * The seven customizable sound rows, in the order the screen lists them: the six
         * [UiMediaKind.SOUND] slots in enum (roster) order, then Boot Sound last.
         */
        val SOUND_SLOTS: List<UiMediaSlot> =
            UiMediaSlot.ofKind(UiMediaKind.SOUND) + UiMediaSlot.BOOT_AUDIO

        private val SCREEN_SLOTS: Set<UiMediaSlot> = SOUND_SLOTS.toSet()
    }
}
