package com.playfieldportal.launcher.discord

import com.playfieldportal.core.domain.discord.DiscordFriend
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import com.playfieldportal.core.domain.discord.DiscordUser
import com.playfieldportal.core.domain.discord.DiscordVoiceInvite
import com.playfieldportal.core.domain.discord.DiscordVoiceState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lite flavor: the native Discord SDK is excluded from the build, so every call is inert and
 * [sdkAvailable] is false (which makes the XMB hide the Social section entirely). This exists only so
 * `core-data`'s Discord repositories still have a `DiscordSessionActivator` to inject.
 */
@Singleton
class NoOpDiscordSessionActivator @Inject constructor() : DiscordSessionActivator {
    override val sdkAvailable: Boolean = false

    override suspend fun activate(accessToken: String): Boolean = false
    override suspend fun deactivate() {}
    override suspend fun currentUser(): DiscordUser? = null
    override suspend fun friends(): List<DiscordFriend> = emptyList()
    override fun connectionStatus(): Int = 0
    override suspend fun setActivity(name: String, details: String?) {}
    override suspend fun clearActivity() {}
    override suspend fun joinVoice(secret: String): Boolean = false
    override suspend fun leaveVoice() {}
    override suspend fun setSelfMute(mute: Boolean) {}
    override suspend fun setVadThreshold(automatic: Boolean, threshold: Float) {}
    override suspend fun setNoiseCancellation(on: Boolean) {}
    override suspend fun setEchoCancellation(on: Boolean) {}
    override suspend fun setAutomaticGainControl(on: Boolean) {}
    override suspend fun setInputVolume(percent: Float) {}
    override suspend fun setOutputVolume(percent: Float) {}
    override suspend fun inviteFriend(userId: String, content: String) {}
    override suspend fun sendJoinRequest(userId: String) {}
    override suspend fun pendingInvites(): List<DiscordVoiceInvite> = emptyList()
    override suspend fun acceptInvite(index: Int) {}
    override suspend fun approveJoinRequest(index: Int) {}
    override suspend fun dismissInvite(index: Int) {}
    override suspend fun consumePendingJoin(): String? = null
    override suspend fun setAudioMode(mode: Int) {}
    override suspend fun setPttActive(active: Boolean) {}
    override suspend fun setPttReleaseDelay(ms: Int) {}
    override suspend fun voiceState(): DiscordVoiceState = DiscordVoiceState.Idle
}
