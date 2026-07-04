package com.playfieldportal.core.data.discord

import com.playfieldportal.core.domain.discord.DeviceLoginState
import com.playfieldportal.core.domain.discord.DiscordConfig
import com.playfieldportal.core.domain.discord.DiscordSessionActivator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates QR login end-to-end: request a device code, poll for approval at the RFC 8628
 * interval, then persist the tokens (encrypted) and activate the native SDK session.
 *
 * Emitted as a cold [Flow] so the QR screen simply collects it; if the collector is cancelled
 * (screen dismissed), the polling [delay] throws and the loop stops — no leaked coroutine, and the
 * device code is never held past the screen's lifetime.
 */
@Singleton
class DiscordAuthRepository @Inject constructor(
    private val deviceAuth: DiscordDeviceAuthClient,
    private val tokenStore: DiscordTokenStore,
    private val sessionActivator: DiscordSessionActivator,
) {
    fun loginWithDeviceQr(scopes: String = DiscordConfig.DEFAULT_SCOPES): Flow<DeviceLoginState> = flow {
        emit(DeviceLoginState.Requesting)

        val challenge = try {
            deviceAuth.requestDeviceCode(scopes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(DeviceLoginState.Error(e.message ?: "Could not reach Discord"))
            return@flow
        }
        emit(DeviceLoginState.AwaitingApproval(challenge))

        // Expiry is tracked purely by accumulated delay (no wall clock) so it's deterministic and
        // test-friendly under virtual time.
        var intervalSec = challenge.pollIntervalSeconds.coerceAtLeast(1)
        var remainingSec = challenge.expiresInSeconds

        while (remainingSec > 0) {
            delay(intervalSec * 1000L)
            remainingSec -= intervalSec

            val result = try {
                deviceAuth.pollForToken(challenge.deviceCode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(DeviceLoginState.Error(e.message ?: "Network error while signing in"))
                return@flow
            }

            when (result) {
                TokenPollResult.Pending -> Unit                          // keep waiting
                TokenPollResult.SlowDown -> intervalSec += 5             // back off, per RFC 8628
                TokenPollResult.Expired -> { emit(DeviceLoginState.Expired); return@flow }
                TokenPollResult.Denied -> { emit(DeviceLoginState.Denied); return@flow }
                is TokenPollResult.Error -> { emit(DeviceLoginState.Error(result.message)); return@flow }
                is TokenPollResult.Approved -> {
                    tokenStore.save(result.tokens)
                    sessionActivator.activate(result.tokens.accessToken)
                    emit(DeviceLoginState.Success(result.tokens))
                    return@flow
                }
            }
        }
        emit(DeviceLoginState.Expired)
    }

    /** True if an encrypted session is stored (the user previously connected Discord). */
    suspend fun hasSession(): Boolean = tokenStore.load() != null

    /**
     * Restore a persisted session on launch: decrypt the token and hand it to the SDK. Returns
     * whether the session is now live. If the access token has expired, we currently require a
     * re-login (refresh-token exchange is a follow-up); the stale token is left in place so the UI
     * can prompt to reconnect.
     */
    suspend fun restoreSession(): Boolean {
        val session = tokenStore.load() ?: return false
        // TODO(refresh): when expired, exchange session.refreshToken for a new access token instead
        // of forcing re-login (refresh tokens don't expire).
        if (session.expiresAtEpochMs <= System.currentTimeMillis()) return false
        return sessionActivator.activate(session.accessToken)
    }

    /** The connected user's profile, or null until the gateway is Ready. */
    suspend fun currentUser() = sessionActivator.currentUser()

    /** Native connection status ordinal (0 = Disconnected … 3 = Ready). */
    fun connectionStatus(): Int = sessionActivator.connectionStatus()

    /** Secure logout: tear down the SDK session and wipe the encrypted tokens + Keystore key. */
    suspend fun logout() {
        runCatching { sessionActivator.deactivate() }
        tokenStore.clear()
    }
}
