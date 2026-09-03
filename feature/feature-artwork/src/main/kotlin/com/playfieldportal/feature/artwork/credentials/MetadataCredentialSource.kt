package com.playfieldportal.feature.artwork.credentials

import kotlinx.coroutines.flow.Flow

/**
 * Credentials for the ScreenScraper WebAPI.
 *
 * The developer pair is mandatory — the API answers nothing without it. The user account is
 * optional and only raises the per-account thread count and daily quota.
 */
data class ScreenScraperCredentials(
    val devId: String,
    val devPassword: String,
    val userId: String? = null,
    val userPassword: String? = null,
) {
    companion object {
        /**
         * The single place that decides whether ScreenScraper is configured. Returns null unless
         * *both* developer fields are present: a half-filled pair is rejected by the API with a
         * 403 that reads like bad credentials, which is a confusing way to learn the setting was
         * never finished.
         */
        fun of(
            devId: String?,
            devPassword: String?,
            userId: String?,
            userPassword: String?,
        ): ScreenScraperCredentials? {
            val id = devId?.trim().orEmpty()
            val password = devPassword?.trim().orEmpty()
            if (id.isEmpty() || password.isEmpty()) return null
            return ScreenScraperCredentials(
                devId        = id,
                devPassword  = password,
                userId       = userId?.trim()?.takeIf { it.isNotEmpty() },
                userPassword = userPassword?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * Where a metadata provider's credentials come from.
 *
 * History: the developer pair was first a plain `BuildConfig` string (a live secret in every APK),
 * then briefly user-entered (which stranded users who could not obtain a pair). The active
 * implementation, [BundledDevPairCredentialSource], ships the pair obfuscated and is the only
 * source of it. A proxied adapter — where a server holds the credential and PFP never sees it —
 * is the other implementation this interface is shaped for. It is not written yet.
 */
interface MetadataCredentialSource {

    /** Emits null whenever ScreenScraper is not fully configured. */
    val screenScraper: Flow<ScreenScraperCredentials?>

    /** One-shot read for request paths that already have a coroutine. */
    suspend fun screenScraperNow(): ScreenScraperCredentials?
}
