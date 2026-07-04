package com.playfieldportal.core.domain.discord

/**
 * Static Discord Social SDK configuration.
 *
 * The Application (Client) ID is **public** and safe to embed. There is **no client secret** —
 * this is a public client using PKCE / the OAuth2 device-authorization grant, so nothing secret
 * ever ships in the APK.
 */
object DiscordConfig {
    /** Discord Application (Client) ID for Playfield Portal. Public, non-secret. */
    const val APPLICATION_ID: String = "1522836772847878216"

    /** OAuth2 device-authorization endpoints (Discord API v10), used by QR login. */
    const val DEVICE_AUTHORIZATION_ENDPOINT: String = "https://discord.com/api/v10/oauth2/device/authorize"
    const val TOKEN_ENDPOINT: String = "https://discord.com/api/v10/oauth2/token"

    /** The only host auth traffic may target — used to reject any non-Discord URL/redirect. */
    const val API_HOST: String = "discord.com"

    /**
     * Default communication scopes for the Social SDK (mirrors
     * `Client::GetDefaultCommunicationScopes` → `openid sdk.social_layer`). `sdk.social_layer`
     * expands server-side to the friends / voice / presence scopes the integration needs, so we
     * request only the minimum.
     */
    const val DEFAULT_SCOPES: String = "openid sdk.social_layer"

    /** RFC 8628 device-code grant type used when polling the token endpoint. */
    const val DEVICE_CODE_GRANT_TYPE: String = "urn:ietf:params:oauth:grant-type:device_code"
}
