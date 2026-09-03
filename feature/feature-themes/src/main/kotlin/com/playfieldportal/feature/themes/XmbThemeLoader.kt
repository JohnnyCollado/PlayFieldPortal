package com.playfieldportal.feature.themes

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.archive.BoundedZipReader
import com.playfieldportal.core.archive.ZipLimits
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.entity.ThemeEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

// Theme ids become a folder name (filesDir/themes/{id}) and the DB primary key, so they must be a
// short, filesystem-safe token — this both prevents path traversal via the id and keeps ids sane.
private val SAFE_THEME_ID = Regex("[A-Za-z0-9._-]{1,64}")

// Decompression limits — a .xmbtheme is a background image, a short boot clip and a handful of
// small sounds. These caps stop a hostile/corrupt archive from exhausting memory or disk (zip bomb)
// while staying well clear of any legitimate pack.
// Theme archives carry a boot video, so the per-entry cap is looser here than the shared default.
private val THEME_ZIP_LIMITS = ZipLimits(
    maxEntries    = 512,
    maxEntryBytes = 64L * 1024 * 1024,   // 64 MB per file (generous for a boot mp4)
    maxTotalBytes = 128L * 1024 * 1024,  // 128 MB across the whole archive
)

sealed class ThemeLoadResult {
    data class Success(val themeId: String) : ThemeLoadResult()
    data class InvalidFormat(val reason: String) : ThemeLoadResult()
    data class UnsupportedVersion(val found: Int, val supported: Int) : ThemeLoadResult()
    data class IoError(val cause: Throwable) : ThemeLoadResult()
}

/**
 * Parses a .xmbtheme ZIP package and installs it into the app's internal storage.
 *
 * The ZIP must contain a `theme.json` that conforms to [XmbThemeManifest]. Optional asset
 * files (`background.jpg`, `boot_animation.mp4`, `sounds/`) are extracted to
 * `filesDir/themes/{id}/` only when the corresponding flag in the manifest is true.
 *
 * Use [loadFromStream] directly in tests to avoid requiring a real ContentResolver.
 */
@Singleton
class XmbThemeLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val themeDao: ThemeDao,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun loadFromUri(uri: Uri): ThemeLoadResult = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return@withContext ThemeLoadResult.IoError(IOException("Cannot open URI: $uri"))
            loadFromStream(stream)
        } catch (e: Exception) {
            Timber.w(e, "IoError opening theme URI: $uri")
            ThemeLoadResult.IoError(e)
        }
    }

    suspend fun loadFromStream(stream: InputStream): ThemeLoadResult = withContext(Dispatchers.IO) {
        try {
            val entries = readZipEntries(stream)

            val manifestBytes = entries["theme.json"]
                ?: return@withContext ThemeLoadResult.InvalidFormat("Missing required theme.json in archive")

            val manifest = try {
                json.decodeFromString(XmbThemeManifest.serializer(), manifestBytes.decodeToString())
            } catch (e: Exception) {
                return@withContext ThemeLoadResult.InvalidFormat("Invalid theme.json: ${e.message}")
            }

            if (manifest.formatVersion > THEME_FORMAT_VERSION) {
                return@withContext ThemeLoadResult.UnsupportedVersion(
                    found     = manifest.formatVersion,
                    supported = THEME_FORMAT_VERSION,
                )
            }

            // The id becomes a folder name and DB key — reject anything that isn't a safe token so
            // a crafted id (e.g. "../../databases/pfp_database") can't escape filesDir.
            if (!SAFE_THEME_ID.matches(manifest.id)) {
                return@withContext ThemeLoadResult.InvalidFormat(
                    "Invalid theme id '${manifest.id}' — use letters, numbers, '.', '_' or '-' (max 64)"
                )
            }

            val waveColor = parseHexColor(manifest.waveColor)
                ?: return@withContext ThemeLoadResult.InvalidFormat(
                    "Invalid wave_color '${manifest.waveColor}' — expected #RRGGBB or #AARRGGBB"
                )
            val accentColor = parseHexColor(manifest.accentColor)
                ?: return@withContext ThemeLoadResult.InvalidFormat(
                    "Invalid accent_color '${manifest.accentColor}' — expected #RRGGBB or #AARRGGBB"
                )
            val textColor = parseHexColor(manifest.textColor)
                ?: return@withContext ThemeLoadResult.InvalidFormat(
                    "Invalid text_color '${manifest.textColor}' — expected #RRGGBB or #AARRGGBB"
                )

            val themeDir = File(context.filesDir, "themes/${manifest.id}")
            themeDir.mkdirs()

            val backgroundUri    = extractAsset(entries, "background.jpg",    themeDir, manifest.hasBackground)
            val bootAnimationUri = extractAsset(entries, "boot_animation.mp4", themeDir, manifest.hasBootAnimation)
            val soundPackUri     = extractSoundPack(entries, themeDir, manifest.hasSoundPack)

            val entity = ThemeEntity(
                id               = manifest.id,
                name             = manifest.name,
                author           = manifest.author,
                version          = manifest.version,
                waveColor        = waveColor,
                waveOpacity      = manifest.waveOpacity,
                waveSpeed        = manifest.waveSpeed,
                waveAmplitude    = manifest.waveAmplitude,
                accentColor      = accentColor,
                textColor        = textColor,
                backgroundUri    = backgroundUri,
                fontKey          = manifest.fontKey,
                hasBootAnimation = manifest.hasBootAnimation,
                bootAnimationUri = bootAnimationUri,
                soundPackUri     = soundPackUri,
                packagePath      = null,
                isBuiltIn        = false,
            )

            themeDao.upsert(entity)
            Timber.i("Theme installed: ${manifest.id} (${manifest.name})")
            ThemeLoadResult.Success(manifest.id)

        } catch (e: Exception) {
            Timber.w(e, "Unexpected error loading theme")
            ThemeLoadResult.IoError(e)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun extractAsset(
        entries: Map<String, ByteArray>,
        fileName: String,
        themeDir: File,
        shouldExtract: Boolean,
    ): String? {
        if (!shouldExtract) return null
        val bytes = entries[fileName] ?: return null
        val dest = safeChild(themeDir, fileName) ?: return null
        dest.writeBytes(bytes)
        return dest.absolutePath
    }

    // Extracts the entire sounds/ directory if hasSoundPack is true.
    // Returns the path to the sounds/ subfolder, or null if not present.
    private fun extractSoundPack(
        entries: Map<String, ByteArray>,
        themeDir: File,
        shouldExtract: Boolean,
    ): String? {
        if (!shouldExtract) return null
        val soundEntries = entries.filterKeys { it.startsWith("sounds/") }
        if (soundEntries.isEmpty()) return null

        val soundsDir = File(themeDir, "sounds")
        soundsDir.mkdirs()
        soundEntries.forEach { (name, bytes) ->
            // Zip-slip guard: a crafted entry name (e.g. "sounds/../../db") is dropped, not written.
            val dest = safeChild(themeDir, name) ?: return@forEach
            dest.parentFile?.mkdirs()
            dest.writeBytes(bytes)
        }
        return soundsDir.absolutePath
    }

    // Resolves [relativePath] under [baseDir] and returns the destination only if it stays inside
    // baseDir. Entry names that traverse out (../, absolute paths, symlink-style tricks) resolve to
    // a canonical path outside the base and are rejected — the core zip-slip defense.
    private fun safeChild(baseDir: File, relativePath: String): File? {
        val base = baseDir.canonicalFile
        val target = File(base, relativePath).canonicalFile
        val basePrefix = base.path + File.separator
        return if (target.path == base.path || target.path.startsWith(basePrefix)) {
            target
        } else {
            Timber.w("Rejected unsafe theme entry path: $relativePath")
            null
        }
    }

    // Reads all ZIP entries as raw ByteArrays so both text (JSON) and binary (images, audio) can be
    // handled uniformly. The caps live in BoundedZipReader now — this used to be one of three
    // hand-rolled readers enforcing the same policy at three different quality levels.
    private fun readZipEntries(stream: InputStream): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        BoundedZipReader.read(stream, THEME_ZIP_LIMITS) { entry ->
            if (!entry.isDirectory) map[entry.name] = entry.readBytes()
        }
        return map
    }

}
