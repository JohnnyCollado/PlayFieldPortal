package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.RateLimiter
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSource
import com.playfieldportal.feature.achievements.provider.steam.SteamCoinMapper
import com.playfieldportal.feature.achievements.provider.steam.SteamMetadata
import com.playfieldportal.feature.achievements.provider.steam.SteamMetadataStore
import com.playfieldportal.feature.achievements.provider.steam.SteamPlayerAchievement
import com.playfieldportal.feature.achievements.provider.steam.SteamWebApi
import com.playfieldportal.feature.achievements.sync.AchievementClock
import kotlinx.coroutines.CancellationException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A local progress read: [Unknown] (no or unreadable file) is never "nothing earned". */
sealed interface LocalEarnedRead {
    data object Unknown : LocalEarnedRead

    /** [fingerprint] identifies the earned state, so an unchanged file costs no write. */
    data class Read(val entries: List<EmuEarnedAchievement>, val fingerprint: String) : LocalEarnedRead
}

/**
 * The LOCAL_STEAM provider: earned state from the GSE emulator's progress file in the game
 * folder, names/descriptions/icons from the Steam schema, rarity from the keyless global
 * percentages, tiers through [SteamCoinMapper]'s rules unchanged. Display-only — PFP reads what
 * the game already wrote; nothing here touches the emulator.
 *
 * Schema and rarity come from [SteamMetadataStore] when cached, so a routine refresh — and the
 * launch-return check through [readEarned] + [mapFromCache] — reads only the local file and makes
 * no Steam request. The network path (a new app id, missing metadata, or an explicit repair)
 * fetches both, caches them, and runs the hidden-description enrichment once.
 *
 * Hidden-description enrichment can't use the STEAM path (which reads the user's OWN profile page,
 * absent for an unowned emu copy), so it goes through [LocalSteamHiddenDescriptions] instead —
 * reading a roster of public top-owner profiles. Best-effort and never fatal.
 */
@Singleton
class LocalSteamSource @Inject constructor(
    private val discovery: LocalSteamDiscovery,
    private val webApi: SteamWebApi,
    private val credentials: AchievementCredentialsProvider,
    private val hiddenDescriptions: LocalSteamHiddenDescriptions,
    private val metadataStore: SteamMetadataStore,
    private val clock: AchievementClock,
) : RemoteAchievementSource {

    private val rate = RateLimiter(1_100)

    override suspend fun fetch(providerGameId: String): ProviderSyncResult =
        fetch(providerGameId, renewMetadata = false)

    /** A full read of [appId]; [renewMetadata] forces fresh schema + rarity (explicit repair). */
    suspend fun fetch(appId: String, renewMetadata: Boolean): ProviderSyncResult {
        val cached = if (renewMetadata) null else metadataStore.get(appId)
        // Only the network path needs the key; with metadata cached this is a purely local read.
        val key = if (cached != null) null else {
            credentials.steamApiKey()?.takeIf { it.isNotBlank() }
                ?: return ProviderSyncResult.MissingCredentials
        }
        val game = discovery.findByAppId(appId)
            ?: return ProviderSyncResult.Failed("no emu game folder for appid $appId")

        // No progress file just means nothing earned YET (the emu's save redirect isn't set, or
        // the game hasn't been played) — the set still tracks at 0%, it never fails the sync.
        val earnedByName = game.achievementsUri
            ?.let { uri -> discovery.readProgress(uri).toEarnedMap() }
            .orEmpty()

        if (cached != null || key == null) {
            val meta = cached ?: return ProviderSyncResult.MissingCredentials
            return ProviderSyncResult.Success(appId, SteamCoinMapper.map(appId, meta.schema, meta.rarity, earnedByName))
        }

        rate.await()
        val schema = runCatching { webApi.getSchemaForGame(key, appId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                return ProviderSyncResult.Failed("schema request failed")
            }
        val schemaCoins = schema.body()?.game?.availableGameStats?.achievements.orEmpty()
        if (schemaCoins.isEmpty()) return ProviderSyncResult.NotFound

        // Rarity is best-effort, exactly as the STEAM provider treats it.
        rate.await()
        val percentByName = runCatching { webApi.getGlobalAchievementPercentages(appId) }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                null
            }
            ?.body()?.achievementpercentages?.achievements
            ?.associate { it.name to it.percent }
            .orEmpty()
        metadataStore.put(appId, SteamMetadata(schemaCoins, percentByName, clock.now()))

        val coins = SteamCoinMapper.map(appId, schemaCoins, percentByName, earnedByName)
        return ProviderSyncResult.Success(appId, hiddenDescriptions.enrich(appId, coins))
    }

    /** Reads only [appId]'s local progress file — no network, no metadata. */
    suspend fun readEarned(appId: String): LocalEarnedRead {
        val uri = discovery.findByAppId(appId)?.achievementsUri ?: return LocalEarnedRead.Unknown
        val entries = discovery.readProgressOrNull(uri) ?: return LocalEarnedRead.Unknown
        return LocalEarnedRead.Read(entries, fingerprintOf(entries))
    }

    /** Maps a local read through cached schema + rarity; null when nothing is cached for [appId]. */
    suspend fun mapFromCache(appId: String, read: LocalEarnedRead.Read): ProviderSyncResult? {
        val cached = metadataStore.get(appId) ?: return null
        return ProviderSyncResult.Success(
            appId,
            SteamCoinMapper.map(appId, cached.schema, cached.rarity, read.entries.toEarnedMap()),
        )
    }

    private fun List<EmuEarnedAchievement>.toEarnedMap(): Map<String, SteamPlayerAchievement> = associate {
        it.apiName to SteamPlayerAchievement(it.apiName, if (it.earned) 1 else 0, it.earnedAtEpochSeconds ?: 0)
    }

    companion object {
        /** A stable digest of the earned state: order-independent, unaffected by file formatting. */
        fun fingerprintOf(entries: List<EmuEarnedAchievement>): String {
            val canonical = entries
                .sortedBy { it.apiName }
                .joinToString("\n") { "${it.apiName}|${it.earned}|${it.earnedAtEpochSeconds ?: 0}" }
            val digest = MessageDigest.getInstance("SHA-1").digest(canonical.toByteArray(Charsets.UTF_8))
            return "local:v1:" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}
