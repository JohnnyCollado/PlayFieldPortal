package com.playfieldportal.core.data.discord

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.playfieldportal.core.domain.discord.DeviceTokens
import com.playfieldportal.core.domain.discord.DiscordSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

// Dedicated DataStore so Discord secrets are isolated from ordinary app prefs.
private val Context.discordSecureStore: DataStore<Preferences> by
    preferencesDataStore(name = "discord_secure")

/**
 * Persists the Discord [DiscordSession] **encrypted at rest**.
 *
 * The plaintext session JSON is sealed with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed / StrongBox when available) and never leaves the TEE. Only the IV+ciphertext
 * (base64) is written to DataStore. [clear] wipes both the ciphertext and the Keystore key, so a
 * logout leaves nothing recoverable. Nothing here logs tokens.
 */
@Singleton
class DiscordTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val blobKey = stringPreferencesKey("session_blob")

    /** Encrypt and persist a freshly obtained session. Absolute expiry is computed here. */
    suspend fun save(tokens: DeviceTokens, nowMs: Long = System.currentTimeMillis()) {
        val session = DiscordSession(
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresAtEpochMs = nowMs + tokens.expiresInSeconds * 1000L,
            scopes = tokens.scopes,
        )
        val sealed = encrypt(json.encodeToString(SessionBlob.serializer(), session.toBlob()))
        context.discordSecureStore.edit { it[blobKey] = sealed }
    }

    /** Decrypt and return the stored session, or null if none / unreadable (treated as logged out). */
    suspend fun load(): DiscordSession? {
        val sealed = context.discordSecureStore.data.first()[blobKey] ?: return null
        return runCatching {
            json.decodeFromString(SessionBlob.serializer(), decrypt(sealed)).toSession()
        }.getOrNull()
    }

    /** Secure logout: remove the ciphertext AND delete the Keystore key. */
    suspend fun clear() {
        context.discordSecureStore.edit { it.remove(blobKey) }
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    // ── Keystore-backed AES-GCM ──────────────────────────────────────────────
    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // Prepend the 12-byte IV so decrypt is self-contained.
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(sealed: String): String {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = bytes.copyOfRange(GCM_IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val ks = keyStore()
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey?.let { return it }
        return generateKey(strongBox = true)
    }

    private fun generateKey(strongBox: Boolean): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .apply { if (strongBox) setIsStrongBoxBacked(true) }
            .build()
        return try {
            generator.init(spec)
            generator.generateKey()
        } catch (_: Exception) {
            // Device without a StrongBox (secure element) — fall back to TEE-backed key.
            if (strongBox) generateKey(strongBox = false) else throw IllegalStateException("keygen failed")
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pfp_discord_token_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val GCM_IV_BYTES = 12
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
