package com.playfieldportal.themekit

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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

    // Bundles are read from untrusted SAF picks; cap each entry so a zip bomb can't OOM us.
    // Real entries are a few hundred KB to a few MB (a 1080p PNG wallpaper).
    private const val MAX_ENTRY_BYTES = 32 * 1024 * 1024

    // Icons are small glyphs (256px templates); a tighter per-entry cap since a bundle may
    // carry dozens of them.
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

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == ENTRY_MANIFEST -> manifest = runCatching {
                        json.decodeFromString(
                            PfpThemeManifest.serializer(),
                            (zip.readCapped() ?: return null).decodeToString(),
                        )
                    }.getOrNull()
                    entry.name == ENTRY_WALLPAPER -> wallpaper = zip.readCapped() ?: return null
                    entry.name == ENTRY_PREVIEW -> preview = zip.readCapped() ?: return null
                    entry.name.startsWith(ICONS_PREFIX) && entry.name.endsWith(ICONS_SUFFIX) -> {
                        // Only registered slot keys are accepted — an icon entry can never
                        // smuggle a path (`icons/../x`) or an unexpected name into the app.
                        val key = entry.name.removePrefix(ICONS_PREFIX).removeSuffix(ICONS_SUFFIX)
                        if (IconSlots.isValidKey(key)) {
                            icons[key] = zip.readCapped(MAX_ICON_BYTES) ?: return null
                        }
                    }
                    // Unknown entries are ignored for forward compatibility.
                }
                zip.closeEntry()
            }
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

    /** Reads the current entry, bailing (null) if it exceeds [cap] — zip-bomb guard. */
    private fun ZipInputStream.readCapped(cap: Int = MAX_ENTRY_BYTES): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > cap) return null
        }
        return out.toByteArray()
    }
}
