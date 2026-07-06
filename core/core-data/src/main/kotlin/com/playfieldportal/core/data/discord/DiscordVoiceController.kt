package com.playfieldportal.core.data.discord

import android.content.Context
import android.media.AudioManager
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import com.playfieldportal.core.data.network.NetworkMonitor
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import com.playfieldportal.core.domain.discord.DiscordVoiceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How aggressively the voice-activity gate filters the mic. Backed by the SDK's VAD threshold
 * (dB, -100..0, default -60): raising it toward 0 means only louder sound (speech) opens the mic, so
 * quiet handheld button clicks are gated out. [LOW] is the default — a good balance for handhelds
 * whose mic sits next to the buttons.
 */
enum class MicSensitivity(val label: String, val automatic: Boolean, val threshold: Float) {
    AUTO("Auto", automatic = true, threshold = -60f),
    HIGH("High", automatic = false, threshold = -60f),
    LOW("Low · filters clicks", automatic = false, threshold = -45f),
    MINIMAL("Minimal · loud speech only", automatic = false, threshold = -30f);

    /** Next level, wrapping — for a controller-friendly cycle-on-select row. */
    fun next(): MicSensitivity = entries[(ordinal + 1) % entries.size]
}

/** A snapshot of every user-configurable voice setting, read from DataStore in one pass. */
data class VoiceSettings(
    val micSensitivity: MicSensitivity,
    val noiseCancellation: Boolean,
    val echoCancellation: Boolean,
    val automaticGainControl: Boolean,
    val inputVolumePercent: Int,   // mic, 0..100
    /** Game↔Voice mix, 0 = all game / 50 = even / 100 = all voice. Owns voice loudness + game ducking. */
    val audioBalance: Int,
    /** Push-to-talk: when on, the mic is closed until held (via the overlay or a controller button). */
    val pushToTalk: Boolean,
    /** Show the floating hold-to-talk overlay button (only relevant while [pushToTalk] is on). */
    val pttOverlay: Boolean,
)

/**
 * Coordinates the opt-in voice room (M4). v1 uses **shared rooms by code**: everyone who joins the
 * same code lands in the same room. The user-facing code is namespaced into the SDK lobby secret so
 * PFP rooms never collide with another app's lobbies.
 *
 * Voice is inert until the user explicitly joins from the Social section, and the SDK only touches
 * the mic during an active call (its own foreground service). The offline guard satisfies the
 * project rule that Discord features never spin forever with no network — [join] fails fast so the
 * UI can prompt to reconnect.
 *
 * **Audio balance:** voice (Discord output) is set per-app via the SDK; game audio lives on the
 * Android media stream, which the SDK can't touch — so the balance ducks `STREAM_MUSIC` via
 * [AudioManager]. The user's media volume is captured on join and restored on leave.
 */
@Singleton
class DiscordVoiceController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionActivator: DiscordSessionActivator,
    private val networkMonitor: NetworkMonitor,
) {
    private val sensitivityKey = intPreferencesKey("discord_voice_mic_sensitivity")
    private val noiseKey = booleanPreferencesKey("discord_voice_noise_cancellation")
    private val echoKey = booleanPreferencesKey("discord_voice_echo_cancellation")
    private val agcKey = booleanPreferencesKey("discord_voice_agc")
    private val inputVolKey = intPreferencesKey("discord_voice_input_volume")
    private val balanceKey = intPreferencesKey("discord_voice_audio_balance")
    private val pttKey = booleanPreferencesKey("discord_voice_push_to_talk")
    private val pttOverlayKey = booleanPreferencesKey("discord_voice_ptt_overlay")

    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    // The user's media (game) volume at join time, so ducking is relative and reversible. Null = not
    // in a call, so the balance never touches the system stream outside a call.
    @Volatile private var mediaBaseline: Int? = null

    // ── Read ──────────────────────────────────────────────────────────────────────
    /** One-pass read of every voice setting (used to render the Voice Settings screen). */
    suspend fun settings(): VoiceSettings = settingsFrom(context.pfpDataStore.data.first())

    suspend fun micSensitivity(): MicSensitivity = settings().micSensitivity

    // ── Write (persist + apply to the live call immediately) ────────────────────────
    suspend fun setMicSensitivity(level: MicSensitivity) {
        context.pfpDataStore.edit { it[sensitivityKey] = level.ordinal }
        sessionActivator.setVadThreshold(level.automatic, level.threshold)
    }

    suspend fun setNoiseCancellation(on: Boolean) {
        context.pfpDataStore.edit { it[noiseKey] = on }
        sessionActivator.setNoiseCancellation(on)
    }

    suspend fun setEchoCancellation(on: Boolean) {
        context.pfpDataStore.edit { it[echoKey] = on }
        sessionActivator.setEchoCancellation(on)
    }

    suspend fun setAutomaticGainControl(on: Boolean) {
        context.pfpDataStore.edit { it[agcKey] = on }
        sessionActivator.setAutomaticGainControl(on)
    }

    suspend fun setInputVolume(percent: Int) {
        val v = percent.coerceIn(0, 100)
        context.pfpDataStore.edit { it[inputVolKey] = v }
        sessionActivator.setInputVolume(v.toFloat())
    }

    suspend fun setAudioBalance(balance: Int) {
        val b = balance.coerceIn(0, 100)
        context.pfpDataStore.edit { it[balanceKey] = b }
        applyBalance(b)
    }

    /** Enable/disable push-to-talk. When on the mic is closed until [setPttActive] is held. */
    suspend fun setPushToTalk(on: Boolean) {
        context.pfpDataStore.edit { it[pttKey] = on }
        applyAudioMode(on)
    }

    /** Show/hide the floating hold-to-talk overlay button. */
    suspend fun setPttOverlay(on: Boolean) {
        context.pfpDataStore.edit { it[pttOverlayKey] = on }
    }

    /** In PTT mode, open (held) / close (released) the mic. Called by the overlay + controller button. */
    suspend fun setPttActive(active: Boolean) = sessionActivator.setPttActive(active)

    private suspend fun applyAudioMode(pushToTalk: Boolean) {
        sessionActivator.setAudioMode(if (pushToTalk) MODE_PTT else MODE_VAD)
        if (pushToTalk) sessionActivator.setPttReleaseDelay(PTT_RELEASE_MS)
    }

    /** Step the mic volume through preset levels, wrapping (controller-friendly cycle row). */
    suspend fun cycleInputVolume() = setInputVolume(nextStep(settings().inputVolumePercent, INPUT_VOLUME_STEPS))

    /** Step the Game↔Voice balance through preset levels, wrapping. */
    suspend fun cycleAudioBalance() = setAudioBalance(nextStep(settings().audioBalance, BALANCE_STEPS))

    // ── Lobbies ───────────────────────────────────────────────────────────────────
    /** Create a fresh private lobby (unique secret) and host it. @return false when offline / failed. */
    suspend fun createLobby(): Boolean = joinRaw(SECRET_PREFIX + java.util.UUID.randomUUID())

    /** Join a lobby by an explicit code (namespaced). @return false when offline / failed. */
    suspend fun join(code: String): Boolean = joinRaw(roomSecret(code))

    // Enter a lobby by its full SDK secret, capturing the media baseline + applying audio settings.
    private suspend fun joinRaw(secret: String): Boolean {
        if (!networkMonitor.isOnline()) return false
        val joined = sessionActivator.joinVoice(secret)
        if (joined) applyAudioSettings()
        return joined
    }

    /** Leave the active room + call; restore the game (media) volume we ducked. */
    suspend fun leave() {
        restoreMediaVolume()
        sessionActivator.leaveVoice()
    }

    /**
     * Push the saved audio settings onto the current call and capture the media baseline. Call this
     * on entering a lobby by ANY path — join, invite-accept, or a Discord-app Join — since those last
     * two enter the lobby natively and would otherwise start with the SDK's default-off processing.
     */
    suspend fun applyAudioSettings() {
        if (mediaBaseline == null) {
            mediaBaseline = runCatching { audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        }
        applyAll(settings())
    }

    // ── Invites & join requests ───────────────────────────────────────────────────
    /** Invite a friend (by Discord user id) to the current lobby. */
    suspend fun inviteFriend(userId: String) =
        sessionActivator.inviteFriend(userId, INVITE_MESSAGE)

    /** Ask to join a friend's lobby (they approve). */
    suspend fun askToJoin(userId: String) = sessionActivator.sendJoinRequest(userId)

    /** Pending invites (friends inviting you) + join requests (people asking into your lobby). */
    suspend fun invites() = sessionActivator.pendingInvites()

    suspend fun acceptInvite(index: Int) = sessionActivator.acceptInvite(index)
    suspend fun approveJoinRequest(index: Int) = sessionActivator.approveJoinRequest(index)
    suspend fun dismissInvite(index: Int) = sessionActivator.dismissInvite(index)

    /** If the user accepted a Join from the Discord app, enter that lobby. @return true if we joined. */
    suspend fun checkPendingJoin(): Boolean {
        val secret = sessionActivator.consumePendingJoin() ?: return false
        return joinRaw(secret)
    }

    /** Mute or unmute the local mic for the whole call. */
    suspend fun setMuted(muted: Boolean) = sessionActivator.setSelfMute(muted)

    /** Current voice snapshot ([DiscordVoiceState.Idle] when not in a room). Poll while the UI is open. */
    suspend fun state(): DiscordVoiceState = sessionActivator.voiceState()

    // Push every saved setting to the SDK — used right after a join so the call starts configured.
    private suspend fun applyAll(s: VoiceSettings) {
        sessionActivator.setNoiseCancellation(s.noiseCancellation)
        sessionActivator.setEchoCancellation(s.echoCancellation)
        sessionActivator.setAutomaticGainControl(s.automaticGainControl)
        sessionActivator.setInputVolume(s.inputVolumePercent.toFloat())
        sessionActivator.setVadThreshold(s.micSensitivity.automatic, s.micSensitivity.threshold)
        applyBalance(s.audioBalance)
        applyAudioMode(s.pushToTalk)
    }

    // Voice side: set Discord's output volume from the balance (per-app, always safe).
    // Game side: duck the system media stream relative to the captured baseline — only while in a call.
    private suspend fun applyBalance(balance: Int) {
        sessionActivator.setOutputVolume(voiceOutputPercent(balance))
        val baseline = mediaBaseline ?: return
        val max = runCatching { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrNull() ?: return
        val target = (baseline * gameVolumeFraction(balance)).roundToInt().coerceIn(0, max)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    private fun restoreMediaVolume() {
        val baseline = mediaBaseline ?: return
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, baseline, 0) }
        mediaBaseline = null
    }

    // Namespace + normalize the user code so PFP rooms are isolated from other apps' lobby secrets
    // and "Party" / "party" / " party " all resolve to the same room.
    private fun roomSecret(code: String): String =
        SECRET_PREFIX + code.trim().lowercase().ifBlank { DEFAULT_ROOM }

    // Defaults tuned for handhelds: Krisp + echo + AGC on, LOW sensitivity, mic 100%, balance centred.
    private fun settingsFrom(p: Preferences) = VoiceSettings(
        micSensitivity = p[sensitivityKey]?.let { MicSensitivity.entries.getOrNull(it) } ?: MicSensitivity.LOW,
        noiseCancellation = p[noiseKey] ?: true,
        echoCancellation = p[echoKey] ?: true,
        automaticGainControl = p[agcKey] ?: true,
        inputVolumePercent = p[inputVolKey] ?: 100,
        audioBalance = p[balanceKey] ?: 50,
        pushToTalk = p[pttKey] ?: false,
        pttOverlay = p[pttOverlayKey] ?: true,
    )

    // Voice output %: 30 at all-game → 100 centred → 200 at all-voice (Discord's 0..200 range).
    private fun voiceOutputPercent(b: Int): Float =
        if (b <= 50) 30f + (b / 50f) * 70f else 100f + ((b - 50) / 50f) * 100f

    // Game volume as a fraction of the baseline: full until centre, then ducks to 0.2 at all-voice.
    private fun gameVolumeFraction(b: Int): Float =
        if (b <= 50) 1f else 1f - ((b - 50) / 50f) * 0.8f

    private fun nextStep(current: Int, steps: List<Int>): Int {
        val idx = steps.indexOfFirst { it >= current }.let { if (it < 0) steps.lastIndex else it }
        return steps[(idx + 1) % steps.size]
    }

    companion object {
        const val DEFAULT_ROOM = "party"
        private const val SECRET_PREFIX = "pfp-voice-"
        private const val INVITE_MESSAGE = "Join my Playfield Portal voice room"
        private val INPUT_VOLUME_STEPS = listOf(0, 25, 50, 75, 100)
        private val BALANCE_STEPS = listOf(0, 25, 50, 75, 100)
        // discordpp::AudioModeType: MODE_VAD = 1 (open mic), MODE_PTT = 2 (push-to-talk).
        private const val MODE_VAD = 1
        private const val MODE_PTT = 2
        private const val PTT_RELEASE_MS = 250
    }
}
