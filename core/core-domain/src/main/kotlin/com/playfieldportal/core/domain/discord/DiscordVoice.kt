package com.playfieldportal.core.domain.discord

/** One participant in the active Discord voice room (self included). */
data class DiscordVoiceParticipant(
    val id: String,
    val displayName: String,
    val muted: Boolean,
    val deaf: Boolean,
    val speaking: Boolean,
)

/**
 * Snapshot of the local voice-call state. [inRoom] is true once a room has been joined; [connecting]
 * stays true until the SDK's call reaches `Connected` (status 4). Voice is opt-in and inert until the
 * user joins a room from the Social section.
 */
data class DiscordVoiceState(
    val inRoom: Boolean,
    val connecting: Boolean,
    val selfMuted: Boolean,
    val participants: List<DiscordVoiceParticipant>,
) {
    companion object {
        val Idle = DiscordVoiceState(
            inRoom = false,
            connecting = false,
            selfMuted = false,
            participants = emptyList(),
        )

        /** `discordpp::Call::Status`: 0 Disconnected · 1 Joining · 2 Connecting · 3 SignalingConnected · 4 Connected. */
        const val STATUS_CONNECTED = 4
    }
}

/** A pending lobby invite (a friend invited you) or join request (someone wants into your lobby). */
data class DiscordVoiceInvite(
    val index: Int,          // position in the native invite queue; used to accept/approve/dismiss
    val senderId: String,
    val senderName: String,
    val isJoinRequest: Boolean,   // false = invite to their lobby · true = request to join yours
) {
    companion object {
        // discordpp::ActivityActionTypes: Join = 1 (an invite), JoinRequest = 5.
        const val TYPE_JOIN_REQUEST = 5
    }
}
