package com.playfieldportal.feature.achievements.provider.retro

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.retroachivements.api.RetroClient
import org.retroachivements.api.RetroInterface
import org.retroachivements.api.data.RetroCredentials
import javax.inject.Inject
import javax.inject.Singleton

/** The live RA client paired with the username to look up (the `u` query param on progress calls). */
data class RaSession(val api: RetroInterface, val username: String)

/**
 * Builds and memoizes the official api-kotlin [RetroInterface] from the user's stored RA
 * credentials. The client bakes credentials in at construction and the user can change or clear
 * them at any time, so the cached client is rebuilt whenever the (username, key) pair changes.
 *
 * `debugging = false` keeps api-kotlin's `HttpLoggingInterceptor` off, so no request line — and
 * therefore no API key — is ever written to a log. This is the only class that constructs the
 * RetroAchievements HTTP client; it is confined to the provider/retro island (see
 * docs/shiba-coins-achievements-plan.md).
 */
@Singleton
class RaClientFactory @Inject constructor(
    private val credentials: AchievementCredentialsProvider,
) {
    private val mutex = Mutex()
    private var cachedKey: Pair<String, String>? = null
    private var cachedApi: RetroInterface? = null

    /**
     * The client + username for the current credentials, or null when RA isn't connected. Reads
     * both the username and the (decrypted) key in one place so they can't drift apart mid-call.
     */
    suspend fun session(): RaSession? {
        val user = credentials.raUsername()?.takeIf { it.isNotBlank() } ?: return null
        val key = credentials.raApiKey()?.takeIf { it.isNotBlank() } ?: return null
        val credKey = user to key
        val api = mutex.withLock {
            if (cachedKey != credKey || cachedApi == null) {
                cachedApi = RetroClient(RetroCredentials(user, key), debugging = false).api
                cachedKey = credKey
            }
            cachedApi!!
        }
        return RaSession(api, user)
    }
}
