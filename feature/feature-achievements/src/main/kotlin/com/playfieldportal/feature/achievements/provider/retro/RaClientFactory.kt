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
                cachedApi = buildApi(user, key)
                cachedKey = credKey
            }
            cachedApi!!
        }
        return RaSession(api, user)
    }

    // api-kotlin builds its OkHttp client with the default 10 s timeouts and bakes it into a
    // final Retrofit at construction. That is fine for the small per-game calls, but
    // GetGameList?h=1 (the hash lists the auto-matcher joins against) runs to several MB on the
    // big consoles and cannot finish in 10 s on a handheld's Wi-Fi — the fetch times out and
    // every game reports as unmatchable. Rebuild the interface over the same client (keeping
    // the library's auth interceptor) with timeouts sized for those responses.
    private fun buildApi(user: String, key: String): RetroInterface {
        val client = RetroClient(RetroCredentials(user, key), debugging = false)
        val patched = client.httpClient.newBuilder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        return client.retroClient.newBuilder().client(patched).build()
            .create(RetroInterface::class.java)
    }
}
