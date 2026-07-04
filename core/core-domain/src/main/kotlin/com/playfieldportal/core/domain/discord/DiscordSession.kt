package com.playfieldportal.core.domain.discord

/**
 * A persisted Discord login session. Stored **encrypted at rest** (Android Keystore AES-GCM);
 * only ever held in memory in plaintext. [expiresAtEpochMs] is absolute so expiry survives clock-
 * independent restore. Refresh tokens do not expire; the access token is refreshed as it nears
 * [expiresAtEpochMs].
 */
data class DiscordSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val scopes: String,
)
