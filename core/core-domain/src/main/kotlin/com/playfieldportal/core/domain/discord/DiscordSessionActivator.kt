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
}
