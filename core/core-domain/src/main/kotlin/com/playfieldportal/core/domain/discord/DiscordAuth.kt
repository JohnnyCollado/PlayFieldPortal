package com.playfieldportal.core.domain.discord

/**
 * A pending device-authorization challenge to render as a QR code on the TV.
 *
 * Contains only values safe to display — never a token. [verificationUriComplete] is what the QR
 * encodes (a public `discord.com/activate?...` URL); [userCode] is the short code shown as text.
 * [deviceCode] is the poll credential — it is never rendered and never logged.
 */
data class DeviceAuthChallenge(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String,
    val deviceCode: String,
    val expiresInSeconds: Int,
    val pollIntervalSeconds: Int,
)

/**
 * OAuth tokens returned once the user approves on their phone. Held in memory only long enough to
 * hand to the SDK and persist encrypted (Android Keystore); never logged.
 */
data class DeviceTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
    val scopes: String,
)

/**
 * State stream that drives the QR login screen. Every variant is safe to surface to the UI; error
 * messages never contain secrets.
 */
sealed interface DeviceLoginState {
    /** Requesting the device code from Discord. */
    data object Requesting : DeviceLoginState

    /** Show the QR + user code; waiting for the user to approve on their phone. */
    data class AwaitingApproval(val challenge: DeviceAuthChallenge) : DeviceLoginState

    /** Approved; tokens obtained (persistence handled by the repository). */
    data class Success(val tokens: DeviceTokens) : DeviceLoginState

    /** The device code expired before approval — the screen offers a retry. */
    data object Expired : DeviceLoginState

    /** The user (or Discord) denied the request. */
    data object Denied : DeviceLoginState

    /** Network / unexpected error — the screen offers a retry. [message] carries no secrets. */
    data class Error(val message: String) : DeviceLoginState
}
