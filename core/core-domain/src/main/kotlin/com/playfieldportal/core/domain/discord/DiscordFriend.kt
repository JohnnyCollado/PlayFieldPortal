package com.playfieldportal.core.domain.discord

/** A Discord friend surfaced in the Social ▸ Friends list. */
data class DiscordFriend(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
    val presence: DiscordPresence,
    // What the friend is doing IN Playfield Portal, if anything (the SDK only exposes this app's
    // activity, not their general Discord presence). Null when they aren't active in PFP.
    val activity: String? = null,
) {
    val label: String get() = displayName.ifBlank { username }
}

/** Simplified presence for the friends list. */
enum class DiscordPresence {
    ONLINE, IDLE, DND, STREAMING, OFFLINE, UNKNOWN;

    val isOnline: Boolean get() = this == ONLINE || this == IDLE || this == DND || this == STREAMING

    companion object {
        // Maps discordpp::StatusType ordinals (Online=0, Offline=1, Blocked=2, Idle=3, Dnd=4,
        // Invisible=5, Streaming=6, Unknown=7).
        fun fromStatusOrdinal(ordinal: Int): DiscordPresence = when (ordinal) {
            0 -> ONLINE
            3 -> IDLE
            4 -> DND
            6 -> STREAMING
            1, 5 -> OFFLINE
            else -> UNKNOWN
        }
    }
}
