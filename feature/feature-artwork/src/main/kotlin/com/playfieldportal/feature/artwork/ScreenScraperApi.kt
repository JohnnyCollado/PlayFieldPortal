package com.playfieldportal.feature.artwork

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton

// ── Response models ────────────────────────────────────────────────────────────

@Serializable
data class SsResponse(
    val response: SsGameResponse? = null,
)

@Serializable
data class SsGameResponse(
    @SerialName("jeu") val game: SsGame? = null,
)

@Serializable
data class SsGame(
    val id: String? = null,
    @SerialName("noms") val names: List<SsLocalizedText> = emptyList(),
    @SerialName("synopsis") val synopsis: List<SsLocalizedText> = emptyList(),
    @SerialName("dates") val dates: List<SsLocalizedText> = emptyList(),
    @SerialName("genres") val genres: List<SsGenre> = emptyList(),
    @SerialName("developpeur") val developer: SsText? = null,
    @SerialName("editeur") val publisher: SsText? = null,
    @SerialName("joueurs") val players: SsText? = null,
    @SerialName("note") val rating: SsRating? = null,
    @SerialName("medias") val medias: List<SsMedia> = emptyList(),
)

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
    val title: String?,
    val description: String?,
    val developer: String?,
    val publisher: String?,
    val releaseYear: Int?,
    val genre: String?,
    val artworkUrl: String?,   // box-2D front
    val heroUrl: String?,      // fanart / wide banner
    val logoUrl: String?,      // wheel / logo
    val manualUrl: String?,    // PDF manual
    val videoUrl: String?,     // video snap (video-normalized)
)

// ── Diagnostic result ──────────────────────────────────────────────────────────

enum class SsFailureReason {
    NO_SYSTEM_ID_MAPPING,
    MISSING_CREDENTIALS,
    BAD_CREDENTIALS,
    RATE_LIMITED,
    NO_MATCH,
    NETWORK_ERROR,
    PARSE_ERROR,
}

data class SsLookupDiagnostics(
    val originalFileName: String,
    val cleanedSearchName: String,
    val platformId: String,
    val screenScraperSystemId: Int?,
    val credentialsPresent: Boolean,
    val httpStatus: Int? = null,
    val matchCount: Int = 0,
    val failureReason: SsFailureReason? = null,
    val failureDetail: String? = null,
)

data class SsLookupResult(
    val info: SsGameInfo?,
    val diagnostics: SsLookupDiagnostics,
) {
    val success: Boolean get() = info != null
}

// ── API client ─────────────────────────────────────────────────────────────────

@Singleton
class ScreenScraperApi @Inject constructor(
    private val httpClient: HttpClient,
    private val keyProvider: MetadataApiKeyProvider,
) {
    companion object {
        private const val BASE = "https://www.screenscraper.fr/api2"

        // ScreenScraper system IDs → our platform IDs
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
        )
    }

    // Convenience wrapper — returns just the game info for callers that don't need diagnostics.
    suspend fun fetchGameInfo(
        platformId: String,
        romFile: File?,
        title: String,
    ): SsGameInfo? = fetchGameInfoWithDiagnostics(platformId, romFile, title).info

    // Full lookup with structured diagnostics for display in UI and logging.
    suspend fun fetchGameInfoWithDiagnostics(
        platformId: String,
        romFile: File?,
        title: String,
    ): SsLookupResult {
        val systemId = PLATFORM_IDS[platformId]
        val filename = romFile?.name ?: "$title.rom"
        val cleanedName = cleanSearchName(filename)
        val username = keyProvider.getSsUsername()
        val credentialsPresent = !username.isNullOrBlank()

        val baseDiag = SsLookupDiagnostics(
            originalFileName  = filename,
            cleanedSearchName = cleanedName,
            platformId        = platformId,
            screenScraperSystemId = systemId,
            credentialsPresent    = credentialsPresent,
        )

        if (systemId == null) {
            Timber.w("ScreenScraper: no system ID mapping for platform='$platformId', skipping")
            return SsLookupResult(null, baseDiag.copy(
                failureReason = SsFailureReason.NO_SYSTEM_ID_MAPPING,
                failureDetail = "No ScreenScraper system ID mapped for platform '$platformId'",
            ))
        }

        if (!credentialsPresent) {
            Timber.w("ScreenScraper: no credentials configured — anonymous requests have very low rate limits")
        }

        val password = keyProvider.getSsPassword() ?: ""
        val crc = romFile?.let { computeCrc32(it) }
        val md5 = romFile?.let { computeMd5(it) }

        Timber.d(
            "ScreenScraper lookup: platform=$platformId systemId=$systemId " +
            "file='$filename' cleaned='$cleanedName' " +
            "credentialsPresent=$credentialsPresent crc=${crc ?: "n/a"}"
        )

        return try {
            val httpResponse: HttpResponse = httpClient.get("$BASE/jeuInfos.php") {
                parameter("devid",       MetadataApiKeyProvider.SS_DEV_ID)
                parameter("devpassword", MetadataApiKeyProvider.SS_DEV_PASSWORD)
                parameter("ssid",        username ?: "")
                parameter("sspassword",  password)
                parameter("softname",    MetadataApiKeyProvider.SS_SOFT_NAME)
                parameter("output",      "json")
                parameter("systemeid",   systemId)
                parameter("romtype",     "rom")
                parameter("romnom",      filename)
                if (crc != null) parameter("crc", crc)
                if (md5 != null) parameter("md5", md5)
            }

            val statusCode = httpResponse.status.value
            val diagWithStatus = baseDiag.copy(httpStatus = statusCode)

            when (httpResponse.status) {
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> {
                    Timber.w("ScreenScraper: bad credentials (HTTP $statusCode) for '$title'")
                    return SsLookupResult(null, diagWithStatus.copy(
                        failureReason = SsFailureReason.BAD_CREDENTIALS,
                        failureDetail = "HTTP $statusCode — check username/password in Artwork Settings",
                    ))
                }
                HttpStatusCode.TooManyRequests -> {
                    Timber.w("ScreenScraper: rate limited (HTTP 429) for '$title'")
                    return SsLookupResult(null, diagWithStatus.copy(
                        failureReason = SsFailureReason.RATE_LIMITED,
                        failureDetail = "Rate limited — wait before retrying",
                    ))
                }
            }

            val ssResponse: SsResponse = try {
                httpResponse.body()
            } catch (parseEx: Exception) {
                val raw = runCatching { httpResponse.bodyAsText() }.getOrDefault("(unreadable)")
                Timber.w(parseEx, "ScreenScraper: parse error for '$title', status=$statusCode, body prefix='${raw.take(200)}'")
                return SsLookupResult(null, diagWithStatus.copy(
                    failureReason = SsFailureReason.PARSE_ERROR,
                    failureDetail = "JSON parse error: ${parseEx.message}",
                ))
            }

            val game = ssResponse.response?.game
            if (game == null) {
                Timber.i("ScreenScraper: no match — platform=$platformId file='$filename'")
                return SsLookupResult(null, diagWithStatus.copy(
                    matchCount    = 0,
                    failureReason = SsFailureReason.NO_MATCH,
                    failureDetail = "No game found for file '$filename' on platform '$platformId'",
                ))
            }

            val info = game.toInfo()
            Timber.i("ScreenScraper: match found — '${info.title}' for '$filename' (platform=$platformId)")
            SsLookupResult(info, diagWithStatus.copy(matchCount = 1))

        } catch (e: Exception) {
            Timber.w(e, "ScreenScraper: network error for '$title'")
            SsLookupResult(null, baseDiag.copy(
                failureReason = SsFailureReason.NETWORK_ERROR,
                failureDetail = e.message ?: "Network error",
            ))
        }
    }

    // Strips region tags, revision markers, disc labels, and file extensions from a ROM filename
    // to produce a cleaner search name. Used only for logging/diagnostics — the raw filename
    // is still sent to ScreenScraper since hash-based matching ignores the name anyway.
    private fun cleanSearchName(filename: String): String {
        return filename
            .substringBeforeLast(".")                             // strip extension
            .replace(Regex("""\s*\(.*?\)"""), "")                // (USA), (Rev 1), (Disc 2), etc.
            .replace(Regex("""\s*\[.*?]"""), "")                 // [!], [T+Eng], etc.
            .replace(Regex("[_\\-]+"), " ")                      // underscores / dashes → spaces
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun SsGame.toInfo(): SsGameInfo {
        val title = names.firstOrNull { it.region == "us" }?.text
            ?: names.firstOrNull { it.region == "wor" }?.text
            ?: names.firstOrNull()?.text

        val description = synopsis.firstOrNull { it.language == "en" }?.text
            ?: synopsis.firstOrNull()?.text

        val yearStr = dates.firstOrNull { it.region == "us" }?.text
            ?: dates.firstOrNull { it.region == "wor" }?.text
            ?: dates.firstOrNull()?.text
        val releaseYear = yearStr?.take(4)?.toIntOrNull()

        val genre = genres.firstOrNull()?.names
            ?.firstOrNull { it.language == "en" }?.text
            ?: genres.firstOrNull()?.names?.firstOrNull()?.text

        // Media: prefer region=us, fall back to wor/null
        fun bestMedia(type: String): String? = medias
            .filter { it.type == type && it.url != null }
            .sortedWith(compareBy { when (it.region) { "us" -> 0; "wor" -> 1; null -> 2; else -> 3 } })
            .firstOrNull()?.url

        return SsGameInfo(
            title       = title,
            description = description,
            developer   = developer?.text,
            publisher   = publisher?.text,
            releaseYear = releaseYear,
            genre       = genre,
            artworkUrl  = bestMedia("box-2D") ?: bestMedia("box-3D"),
            heroUrl     = bestMedia("fanart") ?: bestMedia("ss"),
            logoUrl     = bestMedia("wheel") ?: bestMedia("wheel-hd"),
            manualUrl   = bestMedia("manuel"),
            videoUrl    = bestMedia("video-normalized") ?: bestMedia("video"),
        )
    }

    // Streams the file to avoid loading it all into memory (ISOs can be 4GB+)
    private fun computeCrc32(file: File): String? = try {
        val crc = CRC32()
        file.inputStream().buffered(65_536).use { stream ->
            val buf = ByteArray(65_536)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) crc.update(buf, 0, n)
        }
        crc.value.toString(16).uppercase().padStart(8, '0')
    } catch (e: Exception) {
        Timber.w(e, "CRC32 failed for ${file.name}")
        null
    }

    private fun computeMd5(file: File): String? = try {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().buffered(65_536).use { stream ->
            val buf = ByteArray(65_536)
            var n: Int
            while (stream.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it) }.uppercase()
    } catch (e: Exception) {
        Timber.w(e, "MD5 failed for ${file.name}")
        null
    }
}
