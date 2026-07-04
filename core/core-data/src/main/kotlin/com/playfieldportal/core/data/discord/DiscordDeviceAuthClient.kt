package com.playfieldportal.core.data.discord

import com.playfieldportal.core.domain.discord.DeviceAuthChallenge
import com.playfieldportal.core.domain.discord.DeviceTokens
import com.playfieldportal.core.domain.discord.DiscordConfig
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the OAuth2 **device-authorization grant** (RFC 8628) directly against Discord so we can
 * render our own QR code (the SDK's `GetTokenFromDevice` hides the verification URL). All traffic
 * is HTTPS to [DiscordConfig.API_HOST]; this is a public client, so **no client secret** is sent.
 *
 * Nothing here logs the device code, user code, or tokens.
 *
 * The two calls are kept separate so the poll loop / cancellation lives in the repository and this
 * class stays a thin, unit-testable transport.
 */
@Singleton
class DiscordDeviceAuthClient @Inject constructor(
    @DiscordHttpClient private val http: HttpClient,
) {
    /** Step 1: ask Discord for a device + user code to display as a QR. */
    suspend fun requestDeviceCode(scopes: String = DiscordConfig.DEFAULT_SCOPES): DeviceAuthChallenge {
        val response = http.submitForm(
            url = DiscordConfig.DEVICE_AUTHORIZATION_ENDPOINT,
            formParameters = parameters {
                append("client_id", DiscordConfig.APPLICATION_ID)
                append("scope", scopes)
            },
        )
        require(response.status.isSuccess()) {
            "Device-code request failed (${response.status.value})"
        }
        val dto = response.body<DeviceCodeResponse>()
        // verification_uri_complete is optional per RFC 8628; synthesize it if Discord omits it.
        val complete = dto.verificationUriComplete
            ?: "${dto.verificationUri}?user_code=${dto.userCode}"
        return DeviceAuthChallenge(
            userCode = dto.userCode,
            verificationUri = dto.verificationUri,
            verificationUriComplete = complete,
            deviceCode = dto.deviceCode,
            expiresInSeconds = dto.expiresIn,
            pollIntervalSeconds = dto.interval,
        )
    }

    /** Step 2: one poll of the token endpoint. The caller repeats this at the returned interval. */
    suspend fun pollForToken(deviceCode: String): TokenPollResult {
        val response = http.submitForm(
            url = DiscordConfig.TOKEN_ENDPOINT,
            formParameters = parameters {
                append("client_id", DiscordConfig.APPLICATION_ID)
                append("device_code", deviceCode)
                append("grant_type", DiscordConfig.DEVICE_CODE_GRANT_TYPE)
            },
        )
        val dto = runCatching { response.body<TokenResponse>() }.getOrNull()
            ?: return TokenPollResult.Error("Malformed token response (${response.status.value})")

        return when {
            response.status.isSuccess() && dto.accessToken != null && dto.refreshToken != null ->
                TokenPollResult.Approved(
                    DeviceTokens(
                        accessToken = dto.accessToken,
                        refreshToken = dto.refreshToken,
                        expiresInSeconds = dto.expiresIn ?: 0,
                        scopes = dto.scope.orEmpty(),
                    ),
                )
            dto.error == "authorization_pending" -> TokenPollResult.Pending
            dto.error == "slow_down"             -> TokenPollResult.SlowDown
            dto.error == "expired_token"         -> TokenPollResult.Expired
            dto.error == "access_denied"         -> TokenPollResult.Denied
            else -> TokenPollResult.Error(dto.error ?: "unknown_error (${response.status.value})")
        }
    }

    /**
     * Exchange a refresh token for a fresh access token (OAuth2 refresh grant). Discord may rotate
     * the refresh token or omit it in the response; when omitted, the caller's existing refresh
     * token is reused. Returns [TokenPollResult.Approved] on success, else [TokenPollResult.Error].
     */
    suspend fun refreshTokens(refreshToken: String): TokenPollResult {
        val response = http.submitForm(
            url = DiscordConfig.TOKEN_ENDPOINT,
            formParameters = parameters {
                append("client_id", DiscordConfig.APPLICATION_ID)
                append("grant_type", DiscordConfig.REFRESH_TOKEN_GRANT_TYPE)
                append("refresh_token", refreshToken)
            },
        )
        val dto = runCatching { response.body<TokenResponse>() }.getOrNull()
            ?: return TokenPollResult.Error("Malformed refresh response (${response.status.value})")

        return if (response.status.isSuccess() && dto.accessToken != null) {
            TokenPollResult.Approved(
                DeviceTokens(
                    accessToken = dto.accessToken,
                    refreshToken = dto.refreshToken ?: refreshToken,
                    expiresInSeconds = dto.expiresIn ?: 0,
                    scopes = dto.scope.orEmpty(),
                ),
            )
        } else {
            TokenPollResult.Error(dto.error ?: "refresh_failed (${response.status.value})")
        }
    }
}

/** Result of a single token poll (RFC 8628 semantics). Maps 1:1 onto the login-state transitions. */
sealed interface TokenPollResult {
    /** Not approved yet — keep polling at the current interval. */
    data object Pending : TokenPollResult

    /** Polling too fast — increase the interval, then keep polling. */
    data object SlowDown : TokenPollResult

    /** Approved — tokens are ready. */
    data class Approved(val tokens: DeviceTokens) : TokenPollResult

    /** The device code expired before approval. */
    data object Expired : TokenPollResult

    /** The user (or Discord) denied the request. */
    data object Denied : TokenPollResult

    /** Unexpected error. [message] carries no secrets. */
    data class Error(val message: String) : TokenPollResult
}

// ── Wire DTOs (Discord API v10 device-grant JSON) ────────────────────────────
@Serializable
private data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("interval") val interval: Int = 5,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Int? = null,
    @SerialName("scope") val scope: String? = null,
    @SerialName("error") val error: String? = null,
)
