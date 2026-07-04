package com.playfieldportal.core.domain.discord

/**
 * Hands an OAuth access token to the native Discord SDK to open (or restore) the live session.
 *
 * Implemented in the native bridge module (`:discord:discord-native`) over
 * `Client::UpdateToken`; kept as a domain interface so the auth repository stays testable without
 * the NDK and free of a dependency on the native module.
 */
interface DiscordSessionActivator {
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
}
