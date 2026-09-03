package com.playfieldportal.themekit

import com.playfieldportal.core.archive.BoundedZipReader
import com.playfieldportal.core.archive.ZipLimitExceededException
import com.playfieldportal.core.archive.ZipLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json

/**
 * Reader/writer for `.pfptheme` bundles — a plain zip:
 *
 * ```
 * mytheme.pfptheme
 * ├── manifest.json   (required)
 * ├── wallpaper.png   (optional; absent -> live wave background)
 * ├── preview.png     (optional on read; the app's preview gate always writes one)
 * └── icons/<key>.png (optional, schema v2; custom icon per IconSlots key)
 * ```
 *
 * Image entries are opaque bytes here (PNG by convention) — frontends do the encoding.
 */
object PfpThemeCodec {

    const val FILE_EXTENSION = "pfptheme"
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_WALLPAPER = "wallpaper.png"
    private const val ENTRY_PREVIEW = "preview.png"
    private const val ICONS_PREFIX = "icons/"
    private const val ICONS_SUFFIX = ".png"

    // Bundles are read from untrusted SAF picks. Real entries are a few hundred KB to a few MB
    // (a 1080p PNG wallpaper), and a bundle is a manifest, two images and up to a registry's worth
    // of icons — so the entry count is small and bounded by IconSlots, not by the archive.
    private val BUNDLE_LIMITS = ZipLimits(
        maxEntries    = 128,
        maxEntryBytes = 32L * 1024 * 1024,
        maxTotalBytes = 128L * 1024 * 1024,
    )

    // Icons are small glyphs (256px templates); a tighter cap than the shared per-entry one, since
    // a bundle may carry dozens of them.
    private const val MAX_ICON_BYTES = 4 * 1024 * 1024

    // Lenient on unknown keys so newer bundles (higher schemaVersion additions) still open.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun write(bundle: PfpThemeBundle, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
            zip.write(json.encodeToString(PfpThemeManifest.serializer(), bundle.manifest).toByteArray())
            zip.closeEntry()
            bundle.wallpaper?.let { zip.writeEntry(ENTRY_WALLPAPER, it) }
            bundle.preview?.let { zip.writeEntry(ENTRY_PREVIEW, it) }
            // Sorted for deterministic output (byte-identical bundles for identical themes).
            for ((key, png) in bundle.icons.toSortedMap()) {
                if (IconSlots.isValidKey(key)) zip.writeEntry("$ICONS_PREFIX$key$ICONS_SUFFIX", png)
            }
        }
    }

    fun write(bundle: PfpThemeBundle): ByteArray =
        ByteArrayOutputStream().also { write(bundle, it) }.toByteArray()

    /**
     * Returns null when [input] is not a `.pfptheme` (no manifest, bad JSON, or not the
     * pfptheme manifest type).
     */
    fun read(input: InputStream): PfpThemeBundle? {
        var manifest: PfpThemeManifest? = null
        var wallpaper: ByteArray? = null
        var preview: ByteArray? = null
        val icons = mutableMapOf<String, ByteArray>()

        // BoundedZipReader supplies the caps. This reader used to bound memory per entry but never
        // counted entries, so a small bundle of repeated wallpaper entries was an unbounded hang —
        // re-triggered on every PfpThemeStore.scan().
        try {
            BoundedZipReader.read(input, BUNDLE_LIMITS) { entry ->
                when {
                    entry.name == ENTRY_MANIFEST -> manifest = runCatching {
                        json.decodeFromString(
                            PfpThemeManifest.serializer(),
                            entry.readBytes().decodeToString(),
                        )
                    }.getOrNull()
                    entry.name == ENTRY_WALLPAPER -> wallpaper = entry.readBytes()
                    entry.name == ENTRY_PREVIEW -> preview = entry.readBytes()
                    entry.name.startsWith(ICONS_PREFIX) && entry.name.endsWith(ICONS_SUFFIX) -> {
                        // Only registered slot keys are accepted — an icon entry can never
                        // smuggle a path (`icons/../x`) or an unexpected name into the app.
                        val key = entry.name.removePrefix(ICONS_PREFIX).removeSuffix(ICONS_SUFFIX)
                        if (IconSlots.isValidKey(key)) {
                            entry.readBytes().takeIf { it.size <= MAX_ICON_BYTES }?.let { icons[key] = it }
                        }
                    }
                    // Unknown entries are ignored for forward compatibility.
                }
            }
        } catch (e: ZipLimitExceededException) {
            // Same contract as before: an unreadable bundle is "not a .pfptheme", not a crash.
            return null
        }

        val m = manifest ?: return null
        if (m.manifest != PfpThemeManifest.MANIFEST_TYPE) return null
        return PfpThemeBundle(manifest = m, wallpaper = wallpaper, preview = preview, icons = icons)
    }

    fun read(bytes: ByteArray): PfpThemeBundle? = read(ByteArrayInputStream(bytes))

    private fun ZipOutputStream.writeEntry(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }

}
