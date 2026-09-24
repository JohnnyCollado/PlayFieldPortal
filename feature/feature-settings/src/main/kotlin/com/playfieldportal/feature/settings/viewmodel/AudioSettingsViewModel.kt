package com.playfieldportal.feature.settings.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.repository.AudioLevelStore
import com.playfieldportal.core.data.repository.AudioLevelStore.Companion.levelKey
import com.playfieldportal.core.data.repository.AudioLevelStore.Companion.masterKey
import com.playfieldportal.core.data.repository.ControllerLayoutRepository
import com.playfieldportal.core.data.repository.UiMediaStore
import com.playfieldportal.core.domain.model.AudioChannel
import com.playfieldportal.core.domain.model.UiMediaKind
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.model.XYLayout
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

/** The label shown when a slot has no user assignment. */
const val PFP_DEFAULT_LABEL = "PFP Default"

data class AudioSettingsUiState(
    /** Master level, 0..1. At 0 the launcher is silent — this replaced the Menu Sounds toggle. */
    val masterLevel: Float = 1f,
    /** Per-channel level, 0..1. Every channel is present; a missing entry would render as 0. */
    val channelLevels: Map<AudioChannel, Float> = emptyMap(),
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
 * Interface ▸ Sound. Three lists, in this order: **master**, **levels**, **assignments**.
 *
 * **Levels and assignments are two lists, not one.** The obvious design is one row per sound
 * carrying both its file and its slider, and it cannot work on a controller: `SettingsRow` claims
 * SELECT to open the file picker and `SettingsSliderRow` claims SELECT to enter adjust mode. One
 * row cannot own both, and inventing a modifier chord for a settings screen is a worse answer
 * than two lists.
 *
 * **The membership rule, restated.** This screen used to pin "every row is a menu sound and every
 * menu sound is a row". That invariant now belongs to [SOUND_SLOTS] — the ASSIGNMENT list —
 * specifically. [LEVEL_CHANNELS] is a different list with different membership: it includes Boot
 * Sequence and GameBoot, which have no assignable file here, because a level is a thing you can
 * set for a sound you cannot replace.
 *
 * Ambience is the only entry in both lists, and correctly so: it is the only sound that is both
 * assignable and continuous.
 *
 * The Menu Sounds toggle is gone. Master at 0 is the mute — a switch and a slider that both mean
 * "silent" drift apart the moment one is changed without the other.
 *
 * Boot VIDEO and GameBoot's clip are deliberately NOT reachable from here — they live with their
 * own presentations under Display, and [confirmReset] must never touch them.
 */
@HiltViewModel
class AudioSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: UiMediaStore,
    private val menuSound: MenuSoundPlayer,
    private val levels: AudioLevelStore,
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
            // Read straight from the same prefs snapshot the labels came from, so a level and a
            // label can never disagree about which edit they are showing.
            masterLevel = (prefs[masterKey] ?: AudioLevelStore.DEFAULT_LEVEL).coerceIn(0f, 1f),
            channelLevels = AudioChannel.entries.associateWith { channel ->
                (prefs[channel.levelKey] ?: AudioLevelStore.DEFAULT_LEVEL).coerceIn(0f, 1f)
            },
            soundLabels = labels,
            // BOOT_VIDEO / GAMEBOOT_VIDEO are assignments on other screens' concerns, so filter
            // by "on this screen" rather than showing a Use Default action for a slot this screen
            // does not own.
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

    fun setMasterLevel(percent: Float) = viewModelScope.launch {
        levels.setMaster(percent)
    }

    fun setChannelLevel(channel: AudioChannel, percent: Float) = viewModelScope.launch {
        levels.setChannel(channel, percent)
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
     * Auditions [slot]'s current sound, custom or default, at full volume even with master at 0 —
     * a sound you just picked has to be audible while you are deciding about it.
     *
     * **Ambience deliberately has no preview.** It is already playing behind this screen: the
     * settings UI is an overlay on the XMB, so the launcher is still foregrounded and unsuppressed
     * and the loop is running. Auditioning it would mean starting a second copy of a track the
     * user can already hear, and its slider is the honest way to judge it.
     */
    fun preview(slot: UiMediaSlot) {
        val event = MenuSound.entries.firstOrNull { it.slot == slot } ?: return
        menuSound.play(event, ignoreMute = true)
    }

    /**
     * Drops [slot]'s custom file. For a menu sound that restores its bundled sample; for ambience
     * there is no bundled track, so it turns the feature off — the assignment IS the switch.
     */
    fun useDefault(slot: UiMediaSlot) = viewModelScope.launch {
        store.clear(slot)
    }

    fun requestReset() { _confirmResetVisible.value = true }
    fun dismissReset() { _confirmResetVisible.value = false }

    /**
     * "Reset Sound to Defaults": clears every SOUND slot and returns every level to full.
     *
     * Deliberately does NOT clear the ambience assignment. Ambience is AUDIO_TRACK, so
     * `clearAll(SOUND)` structurally cannot see it — and that is the behaviour we want: a reset
     * of the menu sounds should not silently delete the background track the user chose, which
     * has its own Use Default on its own row. Never touches the boot video or GameBoot's clip
     * either, for the same reason.
     */
    fun confirmReset() = viewModelScope.launch {
        _confirmResetVisible.value = false
        menuSound.play(MenuSound.CONFIRM)
        store.clearAll(UiMediaKind.SOUND)
        levels.resetAll()
    }

    fun dismissMessage() { _message.value = null }

    companion object {
        /**
         * The ASSIGNMENT rows, in the order the screen lists them: every menu sound, then the
         * ambience track. Ambience is appended rather than derived because it is AUDIO_TRACK —
         * the one assignable row here that is not a SoundPool sample.
         */
        val SOUND_SLOTS: List<UiMediaSlot> =
            UiMediaSlot.ofKind(UiMediaKind.SOUND) + UiMediaSlot.AMBIENCE_AUDIO

        /** The LEVEL rows, in enum order. A different list — see the class KDoc. */
        val LEVEL_CHANNELS: List<AudioChannel> = AudioChannel.entries.toList()

        private val SCREEN_SLOTS: Set<UiMediaSlot> = SOUND_SLOTS.toSet()
    }
}
