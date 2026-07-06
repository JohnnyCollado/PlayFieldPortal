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
 * └── preview.png     (optional on read; the app's preview gate always writes one)
 * ```
 *
 * Image entries are opaque bytes here (PNG by convention) — frontends do the encoding.
 */
object PfpThemeCodec {

    const val FILE_EXTENSION = "pfptheme"
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_WALLPAPER = "wallpaper.png"
    private const val ENTRY_PREVIEW = "preview.png"

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

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when (entry.name) {
                    ENTRY_MANIFEST -> manifest = runCatching {
                        json.decodeFromString(PfpThemeManifest.serializer(), zip.readBytes().decodeToString())
                    }.getOrNull()
                    ENTRY_WALLPAPER -> wallpaper = zip.readBytes()
                    ENTRY_PREVIEW -> preview = zip.readBytes()
                    // Unknown entries are ignored for forward compatibility.
                }
                zip.closeEntry()
            }
        }

        val m = manifest ?: return null
        if (m.manifest != PfpThemeManifest.MANIFEST_TYPE) return null
        return PfpThemeBundle(manifest = m, wallpaper = wallpaper, preview = preview)
    }

    fun read(bytes: ByteArray): PfpThemeBundle? = read(ByteArrayInputStream(bytes))

    private fun ZipOutputStream.writeEntry(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }
}
