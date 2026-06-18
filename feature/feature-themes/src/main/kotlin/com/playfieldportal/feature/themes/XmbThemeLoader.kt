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
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

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
        val dest = File(themeDir, fileName)
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
            File(themeDir, name).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
        }
        return soundsDir.absolutePath
    }

    // Reads all ZIP entries as raw ByteArrays so both text (JSON) and binary
    // (images, audio) can be handled uniformly without a second pass.
    private fun readZipEntries(stream: InputStream): Map<String, ByteArray> {
        val map = mutableMapOf<String, ByteArray>()
        ZipInputStream(stream.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    map[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return map
    }
}
