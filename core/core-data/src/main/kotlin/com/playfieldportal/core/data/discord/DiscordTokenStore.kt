package com.playfieldportal.core.data.discord

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.playfieldportal.core.common.security.KeystoreAesGcm
import com.playfieldportal.core.domain.discord.DeviceTokens
import com.playfieldportal.core.domain.discord.DiscordSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

// Dedicated DataStore so Discord secrets are isolated from ordinary app prefs.
private val Context.discordSecureStore: DataStore<Preferences> by
    preferencesDataStore(name = "discord_secure")

/**
 * Persists the Discord [DiscordSession] **encrypted at rest**.
 *
 * The plaintext session JSON is sealed via [KeystoreAesGcm] (hardware-backed / StrongBox when
 * available); the key never leaves the TEE. Only the IV+ciphertext (base64) is written to
 * DataStore. [clear] wipes both the ciphertext and the Keystore key, so a logout leaves nothing
 * recoverable. Nothing here logs tokens.
 */
@Singleton
class DiscordTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val blobKey = stringPreferencesKey("session_blob")
    private val aesGcm = KeystoreAesGcm("pfp_discord_token_key")

    /** Encrypt and persist a freshly obtained session. Absolute expiry is computed here. */
    suspend fun save(tokens: DeviceTokens, nowMs: Long = System.currentTimeMillis()) {
        val session = DiscordSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAtEpochMs = nowMs + tokens.expiresInSeconds * 1000L,
            scopes = tokens.scopes,
        )
        val sealed = aesGcm.seal(json.encodeToString(SessionBlob.serializer(), session.toBlob()))
        context.discordSecureStore.edit { it[blobKey] = sealed }
    }

    /** Decrypt and return the stored session, or null if none / unreadable (treated as logged out). */
    suspend fun load(): DiscordSession? {
        val sealed = context.discordSecureStore.data.first()[blobKey] ?: return null
        return runCatching {
            json.decodeFromString(SessionBlob.serializer(), aesGcm.open(sealed)).toSession()
        }.getOrNull()
    }

    /** Secure logout: remove the ciphertext AND delete the Keystore key. */
    suspend fun clear() {
        context.discordSecureStore.edit { it.remove(blobKey) }
        aesGcm.deleteKey()
    }
}

// Serializable projection of DiscordSession (kept internal so the domain model stays framework-free).
@Serializable
private data class SessionBlob(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val scopes: String,
)

private fun DiscordSession.toBlob() = SessionBlob(accessToken, refreshToken, expiresAtEpochMs, scopes)
private fun SessionBlob.toSession() = DiscordSession(accessToken, refreshToken, expiresAtEpochMs, scopes)
