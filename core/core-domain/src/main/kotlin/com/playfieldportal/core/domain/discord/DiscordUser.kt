package com.playfieldportal.core.domain.discord

/** The connected Discord user's public profile, surfaced in the Social section. */
data class DiscordUser(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String,
) {
    /** Preferred label: the display name when set, otherwise the username. */
    val label: String get() = displayName.ifBlank { username }
}
