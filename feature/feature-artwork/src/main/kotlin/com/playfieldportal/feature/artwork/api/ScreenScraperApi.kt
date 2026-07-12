package com.playfieldportal.feature.artwork.api

import com.playfieldportal.feature.artwork.BuildConfig
import com.playfieldportal.feature.artwork.MetadataApiKeyProvider
import com.playfieldportal.feature.artwork.rom.RomIdentity
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// ── Response models (JSON output of jeuInfos.php) ─────────────────────────────

@Serializable
data class SsResponse(
    val response: SsGameResponse? = null,
)

@Serializable
data class SsGameResponse(
    @SerialName("jeu") val game: SsGame? = null,
    @SerialName("ssuser") val user: SsUser? = null,
)

// Account/quota block returned with every authenticated response. All values arrive as strings.
@Serializable
data class SsUser(
    val id: String? = null,
    @SerialName("maxthreads")        val maxThreads: String? = null,
    @SerialName("requeststoday")     val requestsToday: String? = null,
    @SerialName("maxrequestsperday") val maxRequestsPerDay: String? = null,
)

@Serializable
data class SsGame(
    val id: String? = null,
    @SerialName("noms") val names: List<SsLocalizedText> = emptyList(),
    @SerialName("synopsis") val synopsis: List<SsLocalizedText> = emptyList(),
    @SerialName("dates") val dates: List<SsLocalizedText> = emptyList(),
    @SerialName("genres") val genres: List<SsGenre> = emptyList(),
    // Franchise/series ("famille") — same localized-name shape as genres.
    @SerialName("familles") val families: List<SsGenre> = emptyList(),
    @SerialName("classifications") val classifications: List<SsClassification> = emptyList(),
    @SerialName("developpeur") val developer: SsText? = null,
    @SerialName("editeur") val publisher: SsText? = null,
    @SerialName("joueurs") val players: SsText? = null,
    @SerialName("note") val rating: SsRating? = null,
    @SerialName("medias") val medias: List<SsMedia> = emptyList(),
)

// Age classification: type is the rating board ("ESRB", "PEGI", …), text the grade ("E10+", "12").
@Serializable
data class SsClassification(val type: String? = null, val text: String? = null)

@Serializable
data class SsLocalizedText(
    val region: String? = null,
    @SerialName("langue") val language: String? = null,
    val text: String,
)

@Serializable
data class SsText(val text: String? = null)

@Serializable
data class SsRating(val text: String? = null, val nb: String? = null)

@Serializable
data class SsGenre(
    val id: String? = null,
    @SerialName("noms") val names: List<SsLocalizedText> = emptyList(),
)

@Serializable
data class SsMedia(
    val type: String,
    val region: String? = null,
    val url: String? = null,
    val format: String? = null,
)

// ── Parsed result ──────────────────────────────────────────────────────────────

data class SsGameInfo(
    val ssId: Long?,
    val title: String?,
    val description: String?,
    val developer: String?,
    val publisher: String?,
    val releaseYear: Int?,
    val genre: String?,
    val players: String?,
    val ageRating: String?,        // "ESRB E10+" / "PEGI 12" (ESRB preferred, PEGI fallback)
    val franchise: String?,        // famille, English name preferred
    val communityRating: Float?,   // note normalized /20 → 0..1
    val releaseDate: String?,      // best region date, as served (usually ISO yyyy-MM-dd)
    val artworkUrl: String?,   // box-2D front (box-3D fallback) — background fallback source
    val boxArtUrl: String?,    // strict box-2D front (BOX_ART tile)
    val box3dUrl: String?,     // angled 3D box render (BOX_3D tile)
    val physicalMediaUrl: String?, // cartridge/disc shot, support-2D (PHYSICAL_MEDIA tile)
    val screenshotUrl: String?,    // in-game screenshot ("ss") — Game Detail's SCREENSHOT panel
    val heroUrl: String?,      // fanart / wide banner
    val logoUrl: String?,      // wheel / clear logo
    val manualUrl: String?,    // PDF manual
    val videoUrl: String?,     // normalized video snap (already trimmed/scaled by SS)
    val videoRawUrl: String?,  // full gameplay video — transcoded locally when no snap exists
    // The full trimmed medias list as served — persisted to ss_media_cache so later scrapes
    // and the Artwork Studio can browse every kind without re-asking jeuInfos.
    val medias: List<SsCachedMedia> = emptyList(),
)

// ── Diagnostics ────────────────────────────────────────────────────────────────

enum class SsFailureReason {
    DISABLED,                 // no dev credentials compiled in — SS is off entirely
    NO_SYSTEM_ID_MAPPING,
    BAD_DEV_CREDENTIALS,      // 403 — our devid/devpassword rejected
    API_CLOSED,               // 401 — API closed for non-members / inactive account
    RATE_LIMITED,             // 429 — thread / per-minute limit
    DAILY_QUOTA_EXCEEDED,     // 430 — stop SS for the rest of the run
    TOO_MANY_UNRECOGNIZED,    // 431 — stop unhashed lookups for the rest of the run
    NO_MATCH,                 // 404 or empty response
    NETWORK_ERROR,
    PARSE_ERROR,
}

data class SsLookupDiagnostics(
    val fileName: String?,
    val platformId: String,
    val systemId: Int?,
    val userCredentialsPresent: Boolean,
    val sentCrc: Boolean,
    val httpStatus: Int? = null,
    val failureReason: SsFailureReason? = null,
    val failureDetail: String? = null,
    val quota: SsUser? = null,
)

data class SsLookupResult(
    val info: SsGameInfo?,
    val diagnostics: SsLookupDiagnostics,
) {
    val success: Boolean get() = info != null

    /** True when the whole batch should stop querying ScreenScraper (quota/credential states). */
    val isBatchStopper: Boolean get() = diagnostics.failureReason in setOf(
        SsFailureReason.DISABLED,
        SsFailureReason.BAD_DEV_CREDENTIALS,
        SsFailureReason.API_CLOSED,
        SsFailureReason.DAILY_QUOTA_EXCEEDED,
    )

    /** True when only hash-less lookups should stop (431 protects the account from penalties). */
    val stopsUnhashedLookups: Boolean get() =
        diagnostics.failureReason == SsFailureReason.TOO_MANY_UNRECOGNIZED
}

// ── API client ─────────────────────────────────────────────────────────────────

/**
 * ScreenScraper WebAPI v2 client (jeuInfos.php).
 *
 * Requires developer credentials compiled in via BuildConfig (local.properties /
 * `screenscraper.devId|devPassword`); without them [isEnabled] is false and every lookup
 * short-circuits to [SsFailureReason.DISABLED]. A user account (ssid/sspassword) is optional
 * but raises thread count and daily quota.
 *
 * Matching tuple: hash (crc) + size (romtaille) + filename (romnom), per the official docs.
 * When [ssGameId] is already known (a previous match), the lookup goes by `gameid` and skips
 * matching entirely. Error bodies are frequently plain text — never assume JSON.
 */
@Singleton
class ScreenScraperApi @Inject constructor(
    private val httpClient: HttpClient,
    private val keyProvider: MetadataApiKeyProvider,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Single-flight + 1.1 s spacing: ScreenScraper enforces per-account thread and per-minute
    // limits; serializing our requests keeps a batch scrape inside the free-account allowance.
    private val requestGate = Mutex()
    private var lastRequestAt = 0L

    val isEnabled: Boolean get() = BuildConfig.SS_DEV_ID.isNotBlank() && BuildConfig.SS_DEV_PASSWORD.isNotBlank()

    suspend fun fetchGameInfo(
        platformId: String,
        rom: RomIdentity?,
        ssGameId: Long? = null,
    ): SsLookupResult {
        val systemId = PLATFORM_IDS[platformId]
        val username = keyProvider.getSsUsername()
        val baseDiag = SsLookupDiagnostics(
            fileName   = rom?.fileName,
            platformId = platformId,
            systemId   = systemId,
            userCredentialsPresent = !username.isNullOrBlank(),
            sentCrc    = rom?.crc32 != null,
        )

        if (!isEnabled) {
            return SsLookupResult(null, baseDiag.copy(
                failureReason = SsFailureReason.DISABLED,
                failureDetail = "No developer credentials compiled in",
            ))
        }
        if (systemId == null && ssGameId == null) {
            return SsLookupResult(null, baseDiag.copy(
                failureReason = SsFailureReason.NO_SYSTEM_ID_MAPPING,
                failureDetail = "No ScreenScraper system id for platform '$platformId'",
            ))
        }

        return try {
            val response: HttpResponse = rateLimited { httpClient.get("$BASE/jeuInfos.php") {
                parameter("devid",       BuildConfig.SS_DEV_ID)
                parameter("devpassword", BuildConfig.SS_DEV_PASSWORD)
                parameter("softname",    BuildConfig.SS_SOFT_NAME)
                parameter("output",      "json")
                if (!username.isNullOrBlank()) {
                    parameter("ssid",        username)
                    parameter("sspassword",  keyProvider.getSsPassword().orEmpty())
                }
                if (ssGameId != null) {
                    parameter("gameid", ssGameId)
                } else {
                    parameter("systemeid", systemId)
                    parameter("romtype",   "rom")
                    rom?.fileName?.let  { parameter("romnom",    it) }
                    rom?.sizeBytes?.let { parameter("romtaille", it) }
                    rom?.crc32?.let     { parameter("crc",       it) }
                }
            } }

            val diag = baseDiag.copy(httpStatus = response.status.value)
            failureForStatus(response.status.value)?.let { (reason, detail) ->
                Timber.w("ScreenScraper: $detail (platform=$platformId file='${rom?.fileName}')")
                return SsLookupResult(null, diag.copy(failureReason = reason, failureDetail = detail))
            }

            // Parse from text — SS serves error strings with 200s often enough that a typed
            // body{} call would turn quota messages into opaque parse crashes.
            val bodyText = response.bodyAsText()
            val parsed = runCatching { json.decodeFromString(SsResponse.serializer(), bodyText) }
                .getOrElse { e ->
                    val prefix = bodyText.take(160)
                    Timber.w("ScreenScraper: non-JSON body for '${rom?.fileName}': '$prefix'")
                    return SsLookupResult(null, diag.copy(
                        failureReason = failureForTextBody(prefix),
                        failureDetail = prefix,
                    ))
                }

            val quota = parsed.response?.user
            val game  = parsed.response?.game
            if (game == null) {
                return SsLookupResult(null, diag.copy(
                    failureReason = SsFailureReason.NO_MATCH,
                    failureDetail = "No game found for file '${rom?.fileName}'",
                    quota = quota,
                ))
            }

            val info = game.toInfo()
            Timber.i("ScreenScraper: match ssId=${info.ssId} '${info.title}' for '${rom?.fileName}'")
            SsLookupResult(info, diag.copy(quota = quota))
        } catch (e: Exception) {
            Timber.w(e, "ScreenScraper: network error for '${rom?.fileName}'")
            SsLookupResult(null, baseDiag.copy(
                failureReason = SsFailureReason.NETWORK_ERROR,
                failureDetail = e.message ?: "Network error",
            ))
        }
    }

    /** Validates user credentials via ssuserInfos.php; returns the quota block, or null. */
    suspend fun fetchUserInfo(username: String, password: String): SsUser? = runCatching {
        if (!isEnabled) return null
        val response = rateLimited { httpClient.get("$BASE/ssuserInfos.php") {
            parameter("devid",       BuildConfig.SS_DEV_ID)
            parameter("devpassword", BuildConfig.SS_DEV_PASSWORD)
            parameter("softname",    BuildConfig.SS_SOFT_NAME)
            parameter("output",      "json")
            parameter("ssid",        username)
            parameter("sspassword",  password)
        } }
        if (response.status.value != 200) return null
        json.decodeFromString(SsUserInfoResponse.serializer(), response.bodyAsText()).response?.user
    }.onFailure { Timber.w(it, "ScreenScraper: ssuserInfos failed") }.getOrNull()

    // ── Internals ─────────────────────────────────────────────────────────────

    private suspend fun <T> rateLimited(block: suspend () -> T): T = requestGate.withLock {
        val wait = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAt)
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastRequestAt = System.currentTimeMillis()
        }
    }

    private fun failureForStatus(status: Int): Pair<SsFailureReason, String>? = when (status) {
        200  -> null
        400  -> SsFailureReason.PARSE_ERROR to "HTTP 400 — malformed request"
        401, 426 -> SsFailureReason.API_CLOSED to "HTTP $status — API closed for non-members right now"
        403  -> SsFailureReason.BAD_DEV_CREDENTIALS to "HTTP 403 — developer credentials rejected"
        404  -> SsFailureReason.NO_MATCH to "HTTP 404 — no game matched"
        429  -> SsFailureReason.RATE_LIMITED to "HTTP 429 — thread/minute limit reached"
        430  -> SsFailureReason.DAILY_QUOTA_EXCEEDED to "HTTP 430 — daily quota exceeded"
        431  -> SsFailureReason.TOO_MANY_UNRECOGNIZED to "HTTP 431 — too many unrecognized ROMs today"
        else -> SsFailureReason.NETWORK_ERROR to "HTTP $status"
    }

    // Some deployments return 200 with a plain-text error body; classify the common ones.
    internal fun failureForTextBody(prefix: String): SsFailureReason = when {
        prefix.contains("API closed", ignoreCase = true)        -> SsFailureReason.API_CLOSED
        prefix.contains("quota", ignoreCase = true)             -> SsFailureReason.DAILY_QUOTA_EXCEEDED
        prefix.contains("identifiants", ignoreCase = true) ||
        prefix.contains("Erreur de login", ignoreCase = true)   -> SsFailureReason.BAD_DEV_CREDENTIALS
        else                                                    -> SsFailureReason.PARSE_ERROR
    }

    private fun SsGame.toInfo(): SsGameInfo {
        val title = names.firstOrNull { it.region == "us" }?.text
            ?: names.firstOrNull { it.region == "wor" }?.text
            ?: names.firstOrNull()?.text

        val description = synopsis.firstOrNull { it.language == "en" }?.text
            ?: synopsis.firstOrNull()?.text

        val yearStr = dates.firstOrNull { it.region == "us" }?.text
            ?: dates.firstOrNull { it.region == "wor" }?.text
            ?: dates.firstOrNull()?.text

        val genre = genres.firstOrNull()?.names
            ?.firstOrNull { it.language == "en" }?.text
            ?: genres.firstOrNull()?.names?.firstOrNull()?.text

        val franchise = families.firstOrNull()?.names
            ?.firstOrNull { it.language == "en" }?.text
            ?: families.firstOrNull()?.names?.firstOrNull()?.text

        // Age rating: ESRB first (US-market app), PEGI fallback, else whatever board is present.
        val ageRating = (
            classifications.firstOrNull { it.type.equals("ESRB", ignoreCase = true) }
                ?: classifications.firstOrNull { it.type.equals("PEGI", ignoreCase = true) }
                ?: classifications.firstOrNull()
            )?.let { c -> c.text?.takeIf { it.isNotBlank() }?.let { "${c.type.orEmpty()} $it".trim() } }

        // SS note is out of 20 — normalize to 0..1 so the UI can render any scale it likes.
        val communityRating = rating?.text?.toFloatOrNull()?.div(20f)?.coerceIn(0f, 1f)

        // Per-kind winners come from the shared selector so the live parse, the media-URL
        // cache and the Artwork Studio all pick identically (SsMediaSelection).
        val cachedMedias = medias.mapNotNull { m ->
            m.url?.let { SsCachedMedia(type = m.type, region = m.region, url = it, format = m.format) }
        }
        val urls = SsMediaSelection.urls(cachedMedias)

        return SsGameInfo(
            ssId        = id?.toLongOrNull(),
            title       = title,
            description = description,
            developer   = developer?.text,
            publisher   = publisher?.text,
            releaseYear = yearStr?.take(4)?.toIntOrNull(),
            genre       = genre,
            players     = players?.text,
            ageRating   = ageRating,
            franchise   = franchise,
            communityRating = communityRating,
            releaseDate = yearStr?.takeIf { it.length >= 8 },   // full dates only; bare years stay in releaseYear
            artworkUrl  = urls.artworkUrl,
            boxArtUrl   = urls.boxArtUrl,
            box3dUrl    = urls.box3dUrl,
            physicalMediaUrl = urls.physicalMediaUrl,
            screenshotUrl = urls.screenshotUrl,
            heroUrl     = urls.heroUrl,
            logoUrl     = urls.logoUrl,
            manualUrl   = urls.manualUrl,
            videoUrl    = urls.videoUrl,
            videoRawUrl = urls.videoRawUrl,
            medias      = cachedMedias,
        )
    }

    companion object {
        private const val BASE = "https://api.screenscraper.fr/api2"
        private const val MIN_REQUEST_INTERVAL_MS = 1_100L

        // ScreenScraper system ids → PFP platform ids.
        val PLATFORM_IDS = mapOf(
            "psx"            to 57,
            "ps2"            to 58,
            "ps3"            to 59,
            "psp"            to 61,
            "psvita"         to 62,
            "nes"            to 3,
            "snes"           to 4,
            "n64"            to 14,
            "gb"             to 9,
            "gbc"            to 10,
            "gba"            to 12,
            "nds"            to 15,
            "n3ds"           to 17,
            "gc"             to 13,
            "wii"            to 16,
            "wiiu"           to 18,
            "switch"         to 225,
            "virtualboy"     to 11,
            "megadrive"      to 1,
            "mastersystem"   to 2,
            "gamegear"       to 21,
            "saturn"         to 22,
            "dreamcast"      to 23,
            "segacd"         to 20,
            "sega32x"        to 19,
            "atari2600"      to 26,
            "atari5200"      to 40,
            "atari7800"      to 41,
            "atarilynx"      to 28,
            "pcengine"       to 31,
            "neogeo"         to 142,
            "ngp"            to 25,
            "mame"           to 75,
            "wonderswan"     to 45,
            "wonderswancolor" to 46,
            "x360"           to 33,
            "c64"            to 66,
            "android"        to 63,
            "windows"        to 138,
        )
    }
}

@Serializable
data class SsUserInfoResponse(val response: SsUserInfoBody? = null)

@Serializable
data class SsUserInfoBody(@SerialName("ssuser") val user: SsUser? = null)
