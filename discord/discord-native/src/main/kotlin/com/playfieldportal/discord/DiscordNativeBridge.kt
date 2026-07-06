package com.playfieldportal.discord

import android.app.Activity
import com.discord.socialsdk.DiscordSocialSdkInit
import com.playfieldportal.core.domain.discord.DiscordConfig
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Kotlin entry point to the native Discord Social SDK bridge (`libdiscord_bridge.so`).
 *
 * All calls are forwarded to a single pump thread inside native code; these wrappers only guard
 * one-time library load / client init. No tokens are logged here or natively.
 */
object DiscordNativeBridge {
    private val libraryLoaded = AtomicBoolean(false)
    private val clientStarted = AtomicBoolean(false)

    private fun ensureLibraryLoaded() {
        if (libraryLoaded.compareAndSet(false, true)) System.loadLibrary("discord_bridge")
    }

    /**
     * Must be called from the main Activity's `onCreate`, before any session use: the SDK needs the
     * Android activity/context for networking, the auth Custom Tab, and audio routing.
     */
    fun attachActivity(activity: Activity) {
        ensureLibraryLoaded()
        DiscordSocialSdkInit.setEngineActivity(activity)
    }

    /** Create the client and start the callback pump exactly once. */
    fun ensureInitialized() {
        ensureLibraryLoaded()
        if (clientStarted.compareAndSet(false, true)) {
            nativeInit(DiscordConfig.APPLICATION_ID.toLong())
        }
    }

    /** Push a bearer token into the SDK and connect. Blocking; call off the main thread. */
    fun updateToken(accessToken: String): Boolean {
        ensureInitialized()
        return nativeUpdateToken(accessToken)
    }

    fun disconnect() {
        if (clientStarted.get()) nativeDisconnect()
    }

    /** Current status ordinal (mirrors `discordpp::Client::Status`; 0 = Disconnected). */
    fun status(): Int = if (clientStarted.get()) nativeGetStatus() else 0

    /** Current user as a JSON string, or "" until the session is Ready. Call off the main thread. */
    fun currentUserJson(): String = if (clientStarted.get()) nativeGetCurrentUserJson() else ""

    /** Friends as a JSON array string, or "[]" until Ready. Call off the main thread. */
    fun friendsJson(): String = if (clientStarted.get()) nativeGetFriendsJson() else "[]"

    /** Broadcast app-scoped rich presence (what friends see as "Playing …"). Call off the main thread. */
    fun setActivity(name: String, details: String) {
        if (clientStarted.get()) nativeSetActivity(name, details)
    }

    /** Clear any broadcast presence. Call off the main thread. */
    fun clearActivity() {
        if (clientStarted.get()) nativeClearActivity()
    }

    /**
     * Join (or create) the voice room identified by [secret] and start the call. Two users passing
     * the same secret share a room. Blocking; call off the main thread. Returns the lobby id, or 0
     * on failure / when the session isn't started.
     */
    fun joinVoice(secret: String): Long =
        if (clientStarted.get()) nativeJoinVoice(secret) else 0L

    /** Leave the active call + room. Safe to call when not in a call. Call off the main thread. */
    fun leaveVoice() {
        if (clientStarted.get()) nativeLeaveVoice()
    }

    /** Mute/unmute the local mic for the whole call. Call off the main thread. */
    fun setSelfMute(mute: Boolean) {
        if (clientStarted.get()) nativeSetSelfMute(mute)
    }

    /**
     * Set the voice-activity gate. [automatic] lets the SDK pick; otherwise [threshold] (dB, -100..0,
     * default -60) is used — raising it toward 0 filters quiet button-click noise. Off the main thread.
     */
    fun setVadThreshold(automatic: Boolean, threshold: Float) {
        if (clientStarted.get()) nativeSetVadThreshold(automatic, threshold)
    }

    /** Krisp AI noise cancellation on/off (strips button clicks + background). Off the main thread. */
    fun setNoiseCancellation(on: Boolean) {
        if (clientStarted.get()) nativeSetNoiseCancellation(on)
    }

    /** Acoustic echo cancellation on/off. Off the main thread. */
    fun setEchoCancellation(on: Boolean) {
        if (clientStarted.get()) nativeSetEchoCancellation(on)
    }

    /** Automatic gain control on/off. Off the main thread. */
    fun setAutomaticGainControl(on: Boolean) {
        if (clientStarted.get()) nativeSetAutomaticGainControl(on)
    }

    /** Mic (input) volume, percentage 0..100. Off the main thread. */
    fun setInputVolume(percent: Float) {
        if (clientStarted.get()) nativeSetInputVolume(percent)
    }

    /** Speaker (output) volume, percentage 0..200. Off the main thread. */
    fun setOutputVolume(percent: Float) {
        if (clientStarted.get()) nativeSetOutputVolume(percent)
    }

    /** Invite a friend to the current lobby ([content] = short message). Off the main thread. */
    fun inviteFriend(userId: Long, content: String) {
        if (clientStarted.get()) nativeInviteFriend(userId, content)
    }

    /** Ask to join a friend's lobby (they approve). Off the main thread. */
    fun sendJoinRequest(userId: Long) {
        if (clientStarted.get()) nativeSendJoinRequest(userId)
    }

    /** Pending invites + join requests as a JSON array. Off the main thread. */
    fun invitesJson(): String = if (clientStarted.get()) nativeGetInvitesJson() else "[]"

    /** Accept invite at [index] → join that lobby. Off the main thread. */
    fun acceptInvite(index: Int) {
        if (clientStarted.get()) nativeAcceptInvite(index)
    }

    /** Approve the join request at [index] → the requester may join. Off the main thread. */
    fun approveJoinRequest(index: Int) {
        if (clientStarted.get()) nativeApproveJoinRequest(index)
    }

    /** Dismiss the invite / decline the join request at [index]. Off the main thread. */
    fun dismissInvite(index: Int) {
        if (clientStarted.get()) nativeDismissInvite(index)
    }

    /** Join secret from a Discord-UI "Join" (or ""), consuming it. Off the main thread. */
    fun consumePendingJoin(): String = if (clientStarted.get()) nativeConsumePendingJoin() else ""

    /** Voice input mode: 1 = VAD (open mic), 2 = push-to-talk. Off the main thread. */
    fun setAudioMode(mode: Int) {
        if (clientStarted.get()) nativeSetAudioMode(mode)
    }

    /** In PTT mode, open (true) / close (false) the mic. Off the main thread. */
    fun setPttActive(active: Boolean) {
        if (clientStarted.get()) nativeSetPttActive(active)
    }

    /** PTT release grace period in ms. Off the main thread. */
    fun setPttReleaseDelay(ms: Int) {
        if (clientStarted.get()) nativeSetPttReleaseDelay(ms)
    }

    /** Current call status ordinal (mirrors `discordpp::Call::Status`; 0 = Disconnected). */
    fun callStatus(): Int = if (clientStarted.get()) nativeGetCallStatus() else 0

    /** Active call as a JSON object (lobbyId/status/selfMute/participants), or "{}". Off the main thread. */
    fun voiceJson(): String = if (clientStarted.get()) nativeGetVoiceJson() else "{}"

    fun shutdown() {
        if (clientStarted.compareAndSet(true, false)) nativeShutdown()
    }

    private external fun nativeInit(applicationId: Long)
    private external fun nativeUpdateToken(token: String): Boolean
    private external fun nativeDisconnect()
    private external fun nativeGetStatus(): Int
    private external fun nativeGetCurrentUserJson(): String
    private external fun nativeGetFriendsJson(): String
    private external fun nativeSetActivity(name: String, details: String)
    private external fun nativeClearActivity()
    private external fun nativeJoinVoice(secret: String): Long
    private external fun nativeLeaveVoice()
    private external fun nativeSetSelfMute(mute: Boolean)
    private external fun nativeSetVadThreshold(automatic: Boolean, threshold: Float)
    private external fun nativeSetNoiseCancellation(on: Boolean)
    private external fun nativeSetEchoCancellation(on: Boolean)
    private external fun nativeSetAutomaticGainControl(on: Boolean)
    private external fun nativeSetInputVolume(percent: Float)
    private external fun nativeSetOutputVolume(percent: Float)
    private external fun nativeInviteFriend(userId: Long, content: String)
    private external fun nativeSendJoinRequest(userId: Long)
    private external fun nativeGetInvitesJson(): String
    private external fun nativeAcceptInvite(index: Int)
    private external fun nativeApproveJoinRequest(index: Int)
    private external fun nativeDismissInvite(index: Int)
    private external fun nativeConsumePendingJoin(): String
    private external fun nativeSetAudioMode(mode: Int)
    private external fun nativeSetPttActive(active: Boolean)
    private external fun nativeSetPttReleaseDelay(ms: Int)
    private external fun nativeGetCallStatus(): Int
    private external fun nativeGetVoiceJson(): String
    private external fun nativeShutdown()
}
