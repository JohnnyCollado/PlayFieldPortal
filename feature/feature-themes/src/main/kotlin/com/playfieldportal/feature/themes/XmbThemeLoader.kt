package com.playfieldportal.feature.themes

import android.content.Context
import android.net.Uri
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
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

// Theme ids become a folder name (filesDir/themes/{id}) and the DB primary key, so they must be a
// short, filesystem-safe token — this both prevents path traversal via the id and keeps ids sane.
private val SAFE_THEME_ID = Regex("[A-Za-z0-9._-]{1,64}")

// Decompression limits — a .xmbtheme is a background image, a short boot clip and a handful of
// small sounds. These caps stop a hostile/corrupt archive from exhausting memory or disk (zip bomb)
// while staying well clear of any legitimate pack.
private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024   // 64 MB per file (generous for a boot mp4)
private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024  // 128 MB across the whole archive
private const val MAX_ENTRY_COUNT = 512

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
    // handled uniformly. Per-entry, total, and count caps bound memory/disk so a zip bomb or corrupt
    // archive can't exhaust the device.
    private fun readZipEntries(stream: InputStream): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        var totalBytes = 0L
        var count = 0
        ZipInputStream(stream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    if (++count > MAX_ENTRY_COUNT) throw IOException("Theme archive has too many entries")
                    val bytes = zip.readBounded(MAX_ENTRY_BYTES)
                    totalBytes += bytes.size
                    if (totalBytes > MAX_TOTAL_BYTES) throw IOException("Theme archive is too large")
                    map[entry.name] = bytes
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return map
    }

    // Reads the current entry, aborting if it exceeds [limit]. ZipInputStream reports an unreliable
    // getSize() for streamed archives, so we bound the actual decompressed bytes rather than trust
    // the header.
    private fun InputStream.readBounded(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) throw IOException("Theme entry exceeds the ${limit / (1024 * 1024)}MB size limit")
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }
}
