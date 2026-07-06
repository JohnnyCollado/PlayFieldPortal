package com.playfieldportal.core.domain.discord

/**
 * Hands an OAuth access token to the native Discord SDK to open (or restore) the live session.
 *
 * Implemented in the native bridge module (`:discord:discord-native`) over
 * `Client::UpdateToken`; kept as a domain interface so the auth repository stays testable without
 * the NDK and free of a dependency on the native module.
 */
interface DiscordSessionActivator {
    /**
     * Whether the Discord SDK is actually present in this build. False in the "lite" flavor (the
     * native SDK is excluded to shrink the download), which the UI uses to hide the Social section.
     */
    val sdkAvailable: Boolean

    /** @return true once the SDK reports the token accepted and the session is live. */
    suspend fun activate(accessToken: String): Boolean

    /** Tear down the live SDK session (logout). */
    suspend fun deactivate()

    /** The connected user, or null if the gateway hasn't reached Ready yet. */
    suspend fun currentUser(): DiscordUser?

    /** The connected user's friends (empty until the gateway is Ready). */
    suspend fun friends(): List<DiscordFriend>

    /** Native connection status ordinal (mirrors discordpp Client::Status; 0 = Disconnected, 3 = Ready). */
    fun connectionStatus(): Int

    /** Broadcast this app-scoped rich presence — what friends see as "Playing …" under our app. */
    suspend fun setActivity(name: String, details: String?)

    /** Clear any broadcast presence (sharing turned off / signed out). */
    suspend fun clearActivity()

    /** Join (or create) the voice room for [secret] and start the call. @return true on success. */
    suspend fun joinVoice(secret: String): Boolean

    /** Leave the active voice call + room. Safe to call when not in a call. */
    suspend fun leaveVoice()

    /** Mute or unmute the local mic for the whole call. */
    suspend fun setSelfMute(mute: Boolean)

    /**
     * Set the voice-activity gate. [automatic] lets the SDK auto-detect; otherwise [threshold]
     * (dB, -100..0, default -60) is used — raising it toward 0 filters quiet button-click noise.
     */
    suspend fun setVadThreshold(automatic: Boolean, threshold: Float)

    /** Krisp AI noise cancellation on/off (strips button clicks + background). */
    suspend fun setNoiseCancellation(on: Boolean)

    /** Acoustic echo cancellation on/off. */
    suspend fun setEchoCancellation(on: Boolean)

    /** Automatic gain control on/off. */
    suspend fun setAutomaticGainControl(on: Boolean)

    /** Mic (input) volume, percentage 0..100. */
    suspend fun setInputVolume(percent: Float)

    /** Speaker (output) volume, percentage 0..200. */
    suspend fun setOutputVolume(percent: Float)

    /** Invite a friend to the current lobby ([content] = short message shown with the invite). */
    suspend fun inviteFriend(userId: String, content: String)

    /** Ask to join a friend's lobby (they approve). */
    suspend fun sendJoinRequest(userId: String)

    /** Pending invites (friends inviting you) + join requests (people asking into your lobby). */
    suspend fun pendingInvites(): List<DiscordVoiceInvite>

    /** Accept the invite at [index] → join that friend's lobby. */
    suspend fun acceptInvite(index: Int)

    /** Approve the join request at [index] → the requester may join your lobby. */
    suspend fun approveJoinRequest(index: Int)

    /** Dismiss the invite / decline the join request at [index]. */
    suspend fun dismissInvite(index: Int)

    /** Join secret from a Discord-app "Join" tap (or null), consuming it. */
    suspend fun consumePendingJoin(): String?

    /** Voice input mode: 1 = VAD (open mic), 2 = push-to-talk. */
    suspend fun setAudioMode(mode: Int)

    /** In push-to-talk mode, open (true) / close (false) the mic. */
    suspend fun setPttActive(active: Boolean)

    /** PTT release grace period in ms (mic stays open briefly after release). */
    suspend fun setPttReleaseDelay(ms: Int)

    /** Current voice-call snapshot ([DiscordVoiceState.Idle] when not in a room). */
    suspend fun voiceState(): DiscordVoiceState
}
