package com.playfieldportal.feature.artwork.api

import android.content.Context
import com.playfieldportal.core.common.logging.LogRedaction
import com.playfieldportal.feature.artwork.BuildConfig
import com.playfieldportal.feature.artwork.credentials.MetadataCredentialSource
import com.playfieldportal.feature.artwork.credentials.ScreenScraperCredentials
import com.playfieldportal.feature.artwork.rom.RomIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

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

// jeuRecherche.php: up to 30 games ranked by likelihood. A miss is often padded with an empty
// object, so every entry is parsed leniently and id-less ones are dropped.
@Serializable
data class SsSearchResponse(
    val response: SsSearchBody? = null,
)

@Serializable
data class SsSearchBody(
    @SerialName("jeux") val games: List<SsGame> = emptyList(),
    @SerialName("ssuser") val user: SsUser? = null,
)

/** One jeuRecherche hit, reduced to what a match needs. */
data class SsSearchHit(
    val ssId: Long,
    val title: String,
    val releaseYear: Int?,
    val systemId: Int? = null,
    val systemName: String? = null,
    // Media of the game itself (`parent: jeu`). A hit's other media are publisher, genre and rating
    // pictograms, so a release can list a hundred media and still have no artwork at all.
    val gameArtCount: Int = 0,
)

/** A ScreenScraper name search that failed, as opposed to one that found nothing. */
class SsSearchFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

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
    // The release's system, {"id","text"}. Read only from name-search hits: a search across every
    // system has to say which release each hit is.
    @SerialName("systeme") val system: SsSystem? = null,
)

@Serializable
data class SsSystem(val id: String? = null, val text: String? = null)

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
    // What the media belongs to: "jeu" for the game's own art, else "editeur", "genre", "classification"…
    val parent: String? = null,
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
    DISABLED,                 // no developer credentials configured — SS is off entirely
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
 * Requires developer credentials, supplied by [MetadataCredentialSource] from what the user
 * entered in Settings ▸ Artwork; without them [isEnabled] is false and every lookup
 * short-circuits to [SsFailureReason.DISABLED]. A user account (ssid/sspassword) is optional
 * but raises thread count and daily quota.
 *
 * Matching tuple: hash (crc) + size (romtaille) + filename (romnom), per the official docs.
 * When [ssGameId] is already known (a previous match), the lookup goes by `gameid` and skips
 * matching entirely. Error bodies are frequently plain text — never assume JSON.
 */
@Singleton
class ScreenScraperApi @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val httpClient: HttpClient,
    private val credentials: MetadataCredentialSource,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // Single-flight + 1.1 s spacing: ScreenScraper enforces per-account thread and per-minute
    // limits; serializing our requests keeps a batch scrape inside the free-account allowance.
    private val requestGate = Mutex()
    private var lastRequestAt = 0L

    /** True once the user has supplied a developer account. Reads storage, hence suspend. */
    suspend fun isEnabled(): Boolean = credentials.screenScraperNow() != null

    /** [isEnabled] as a stream, for settings screens that mirror it into UI state. */
    val isEnabledFlow: Flow<Boolean> = credentials.screenScraper.map { it != null }

    suspend fun fetchGameInfo(
        platformId: String,
        rom: RomIdentity?,
        ssGameId: Long? = null,
    ): SsLookupResult {
        val systemId = PLATFORM_IDS[platformId]
        val creds = credentials.screenScraperNow()
        val baseDiag = SsLookupDiagnostics(
            fileName   = rom?.fileName,
            platformId = platformId,
            systemId   = systemId,
            userCredentialsPresent = creds?.userId != null,
            sentCrc    = rom?.crc32 != null,
        )

        if (creds == null) {
            return SsLookupResult(null, baseDiag.copy(
                failureReason = SsFailureReason.DISABLED,
                failureDetail = "No ScreenScraper developer account — add one in Settings ▸ Artwork",
            ))
        }
        if (systemId == null && ssGameId == null) {
            return SsLookupResult(null, baseDiag.copy(
                failureReason = SsFailureReason.NO_SYSTEM_ID_MAPPING,
                failureDetail = "No ScreenScraper system id for platform '$platformId'",
            ))
        }
        if (!canLookUp(rom, ssGameId)) {
            // A Windows install (or any game with no ROM file) has nothing jeuInfos can match on.
            // Sending the bare systemeid only earned an HTTP 400 and spent a request of the quota.
            return SsLookupResult(null, baseDiag.copy(
                failureReason = SsFailureReason.NO_MATCH,
                failureDetail = "No ScreenScraper id and no ROM file to identify '$platformId' game by — match it by title",
            ))
        }

        return try {
            val response: HttpResponse = rateLimited { httpClient.get("$BASE/jeuInfos.php") {
                credentialParams(creds)
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
            captureBodyInDebug("jeuInfos-${ssGameId ?: slugOf(rom?.fileName ?: "rom")}", bodyText)
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

    /**
     * ScreenScraper's name search (`jeuRecherche.php`, up to 30 games ranked by likelihood) — the
     * matcher's Tier 3 and Change Match, and the only way to identify a game with no ROM file, such
     * as a Windows install. Scoped to [platformId]'s system when it is mapped; a null [platformId]
     * searches every system.
     *
     * A search that fails — no answer, an HTTP error, or an error message instead of JSON — throws
     * [SsSearchFailedException] rather than returning no hits, so a caller never remembers a failure
     * as "nothing found". ScreenScraper's genuine empty answer (`"jeux":[{}]`) is an empty list, as
     * is having no account to ask with. A cancelled request still propagates.
     */
    suspend fun searchGames(platformId: String?, title: String): List<SsSearchHit> {
        val creds = credentials.screenScraperNow() ?: return emptyList()
        if (title.isBlank()) return emptyList()
        val systemId = platformId?.let { PLATFORM_IDS[it] }
        val (status, body) = try {
            rateLimited { httpClient.get("$BASE/jeuRecherche.php") {
                credentialParams(creds)
                parameter("recherche", title)
                systemId?.let { parameter("systemeid", it) }
            } }.let { it.status.value to it.bodyAsText() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "ScreenScraper search failed for '$title'")
            throw SsSearchFailedException("No answer from ScreenScraper", e)
        }
        failureForStatus(status)?.let { (_, detail) ->
            Timber.w("ScreenScraper search: $detail for '$title' (platform=$platformId)")
            throw SsSearchFailedException(detail)
        }
        captureBodyInDebug("jeuRecherche-${systemId ?: "any"}-${slugOf(title)}", body)
        val hits = parseSearch(body)
            ?: throw SsSearchFailedException("ScreenScraper answered with an error instead of results")
        Timber.d("ScreenScraper search '$title' (system ${systemId ?: "any"}) → ${hits.size} hits")
        return hits
    }

    /**
     * Debug builds only: saves a ScreenScraper response body to the app cache as [name].json, so a
     * real response can be pulled off the device and pinned as a test fixture. Scrubbed first
     * ([scrubCapture]): the body names the account and echoes the request URL, and the media URLs
     * that remain carry credentials for [LogRedaction] to blank. Only the
     * newest [MAX_CAPTURES] files are kept, so a batch scrape cannot fill the cache.
     */
    private suspend fun captureBodyInDebug(name: String, body: String) {
        if (!BuildConfig.DEBUG) return
        withContext(Dispatchers.IO) {
            try {
                val dir = File(appContext.cacheDir, "ss-captures").apply { mkdirs() }
                val file = File(dir, "$name.json")
                file.writeText(LogRedaction.redact(scrubCapture(body)))
                dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_CAPTURES)?.forEach { it.delete() }
                Timber.d("ScreenScraper body (${body.length} chars) saved to ${file.absolutePath}")
            } catch (e: IOException) {
                Timber.w("ScreenScraper body not saved: ${e.message}")
            }
        }
    }

    private fun slugOf(text: String): String =
        text.lowercase(Locale.ROOT).replace(NON_SLUG, "-").trim('-').take(40)

    /**
     * A jeuRecherche body → hits, or null when the body is not a search response at all:
     * ScreenScraper serves error messages as plain text, often with HTTP 200. Pure, so parsing is
     * testable without a network.
     */
    internal fun parseSearch(body: String): List<SsSearchHit>? {
        val parsed = runCatching { json.decodeFromString(SsSearchResponse.serializer(), body) }
            .getOrElse {
                Timber.w("ScreenScraper search: non-JSON body '${body.take(160)}'")
                return null
            }
        return parsed.response?.games.orEmpty().mapNotNull { game ->
            val info = game.toInfo()
            val id = info.ssId ?: return@mapNotNull null
            val title = info.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SsSearchHit(
                ssId = id,
                title = title,
                releaseYear = info.releaseYear,
                systemId = game.system?.id?.toIntOrNull(),
                systemName = game.system?.text?.takeIf { it.isNotBlank() },
                gameArtCount = game.medias.count { it.parent == "jeu" },
            )
        }
    }

    /** Validates user credentials via ssuserInfos.php; returns the quota block, or null. */
    suspend fun fetchUserInfo(username: String, password: String): SsUser? = runCatching {
        val creds = credentials.screenScraperNow() ?: return null
        val response = rateLimited { httpClient.get("$BASE/ssuserInfos.php") {
            // The account being tested is the one passed in, not the stored one.
            credentialParams(creds.copy(userId = username, userPassword = password))
        } }
        if (response.status.value != 200) return null
        json.decodeFromString(SsUserInfoResponse.serializer(), response.bodyAsText()).response?.user
    }.onFailure { Timber.w(it, "ScreenScraper: ssuserInfos failed") }.getOrNull()

    // ── Internals ─────────────────────────────────────────────────────────────

    /**
     * The developer pair every endpoint requires, plus the optional user account. Kept in one
     * place so a new endpoint cannot forget half of it.
     */
    private fun HttpRequestBuilder.credentialParams(creds: ScreenScraperCredentials) {
        parameter("devid",       creds.devId)
        parameter("devpassword", creds.devPassword)
        parameter("softname",    BuildConfig.SS_SOFT_NAME)
        parameter("output",      "json")
        creds.userId?.let {
            parameter("ssid",       it)
            parameter("sspassword", creds.userPassword.orEmpty())
        }
    }

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

        private val NON_SLUG = Regex("[^a-z0-9]+")
        private const val MAX_CAPTURES = 20
        private val CAPTURE_JSON = Json { prettyPrint = true }

        /**
         * [body] without the parts that name the account: `response.ssuser` holds the account name
         * as a plain JSON value, which [LogRedaction] cannot recognise, and `header.commandRequested`
         * echoes the whole request URL. A body that is not a JSON object (ScreenScraper's plain-text
         * errors) comes back unchanged.
         */
        internal fun scrubCapture(body: String): String {
            val root = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return body
            val scrubbed = JsonObject(
                root.mapValues { (key, value) ->
                    when {
                        key == "header" && value is JsonObject -> JsonObject(value - "commandRequested")
                        key == "response" && value is JsonObject -> JsonObject(value - "ssuser")
                        else -> value
                    }
                },
            )
            return CAPTURE_JSON.encodeToString(JsonElement.serializer(), scrubbed)
        }

        /** jeuInfos needs a known game id, or at least a ROM checksum or file name to match on. */
        internal fun canLookUp(rom: RomIdentity?, ssGameId: Long?): Boolean =
            ssGameId != null || rom?.crc32 != null || rom?.fileName != null

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
