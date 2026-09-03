package com.playfieldportal.feature.artwork.credentials

import com.playfieldportal.feature.artwork.BuildConfig
import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reassembles the ScreenScraper developer pair that the Gradle build XOR-encoded into
 * `BuildConfig` (see feature-artwork/build.gradle.kts). Pure so the round-trip is unit-testable.
 *
 * This is obfuscation, not encryption: the key material travels with the APK, so a decompiler
 * recovers the pair. It defeats `strings` scrapes and automated harvesters only.
 */
object DevPairDecoder {

    /**
     * XORs [share] with [mask] and decodes the result as UTF-8. Either array empty (or a length
     * mismatch, which cannot happen when the Gradle generator produced both) yields null — the
     * bundled pair simply does not exist in that build.
     */
    fun decode(share: ByteArray?, mask: ByteArray?): String? {
        if (share == null || mask == null) return null
        if (share.isEmpty() || mask.isEmpty() || share.size != mask.size) return null
        val plain = ByteArray(share.size) { i -> (share[i].toInt() xor mask[i].toInt()).toByte() }
        val text = String(plain, Charsets.UTF_8)
        return text.trim().takeIf { it.isNotEmpty() }
    }
}

/**
 * The credential source: the developer pair ships obfuscated inside the APK ([DevPairDecoder]);
 * the optional user account is whatever the user entered in Settings ▸ Artwork.
 *
 * There is no user-entered developer pair — the build-time pair is the only one. ScreenScraper
 * validates the pair as a unit, so there is deliberately no code path that can mix a stored half
 * with a bundled half.
 */
@Singleton
class BundledDevPairCredentialSource @Inject constructor(
    private val keys: MetadataApiKeyProvider,
) : MetadataCredentialSource {

    private fun bundledPair(): Pair<String, String>? {
        val devId = DevPairDecoder.decode(BuildConfig.SS_DEV_ID_SHARE, BuildConfig.SS_DEV_ID_MASK)
        val devPassword = DevPairDecoder.decode(BuildConfig.SS_DEV_PASSWORD_SHARE, BuildConfig.SS_DEV_PASSWORD_MASK)
        return if (devId != null && devPassword != null) devId to devPassword else null
    }

    override val screenScraper: Flow<ScreenScraperCredentials?> =
        keys.ssUsernameFlow.map { userId ->
            // getSsPassword is a suspend read; map's transform is suspend. The dev pair is a
            // build constant, so the username flow is a sufficient reactivity trigger here.
            val (devId, devPassword) = bundledPair() ?: return@map null
            ScreenScraperCredentials.of(
                devId        = devId,
                devPassword  = devPassword,
                userId       = userId,
                userPassword = keys.getSsPassword(),
            )
        }

    override suspend fun screenScraperNow(): ScreenScraperCredentials? {
        val (devId, devPassword) = bundledPair() ?: return null
        return ScreenScraperCredentials.of(
            devId        = devId,
            devPassword  = devPassword,
            userId       = keys.getSsUsername(),
            userPassword = keys.getSsPassword(),
        )
    }
}
