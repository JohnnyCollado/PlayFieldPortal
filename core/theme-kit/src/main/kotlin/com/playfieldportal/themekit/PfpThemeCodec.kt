package com.playfieldportal.themekit

import com.playfieldportal.core.archive.BoundedZipReader
import com.playfieldportal.core.archive.ZipLimitExceededException
import com.playfieldportal.core.archive.ZipLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
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
 * ├── manifest.json                        (required; schemaVersion 3)
 * ├── wallpaper.png                        (optional; absent -> live wave background)
 * ├── preview.png                          (optional on read; the app's preview gate writes one)
 * ├── icons/<key>.<png|gif>                (v2 as png-only; v3 widens to gif)
 * ├── sysicons/<platformId>.<png|gif>      (v3; console art)
 * └── motion.<mp4|webm|gif>                (v3; motion wallpaper)
 * ```
 *
 * Image entries are opaque bytes here — frontends do the encoding.
 *
 * All changes from v2 are additive: unknown zip entries are ignored, unknown manifest fields
 * ignored, and the readers never gate on schemaVersion, so a v3 bundle still opens on v2-era
 * builds (they see the v2 subset). The `sysicons/` gating rides on [CustomizableIcons]'s
 * console keys — NOT `IconSlots.ALL` — so the desktop Theme Studio's slot list is unaffected
 * until it opts in.
 */
object PfpThemeCodec {

    const val FILE_EXTENSION = "pfptheme"
    private const val ENTRY_MANIFEST = "manifest.json"
    private const val ENTRY_WALLPAPER = "wallpaper.png"
    private const val ENTRY_PREVIEW = "preview.png"
    private const val ICONS_PREFIX = "icons/"
    private const val SYSICONS_PREFIX = "sysicons/"
    private const val MOTION_PREFIX = "motion."

    /** Accepted extensions per directory. v2 accepted png only for icons; v3 adds gif. */
    val ICON_EXTENSIONS = setOf("png", "gif")
    val MOTION_EXTENSIONS = setOf("mp4", "webm", "gif")

    /** Public so the v3 limit tests pin the caps — a bundle that trips them is "not a .pfptheme". */
    val BUNDLE_LIMITS = ZipLimits(
        maxEntries    = 256,
        maxEntryBytes = 64L * 1024 * 1024,
        maxTotalBytes = 256L * 1024 * 1024,
    )

    // Icons are small glyphs (256px templates); a tighter cap than the shared per-entry one, since
    // a bundle may carry dozens of them. 8 MB matches CustomIconLimits.MAX_BYTES app-side.
    const val MAX_ICON_BYTES = 8 * 1024 * 1024

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
            // Keys were validated against the registries by the callers' codecs; unknown keys
            // and non-accepted extensions are silently skipped rather than written.
            for ((key, image) in bundle.icons.toSortedMap()) {
                if (IconSlots.isValidKey(key) && image.extension.lowercase() in ICON_EXTENSIONS) {
                    zip.writeEntry("$ICONS_PREFIX$key.${image.extension.lowercase()}", image.bytes)
                }
            }
            for ((platformId, image) in bundle.sysicons.toSortedMap()) {
                if (CustomizableIcons.isValidKey("sysicon_$platformId") && image.extension.lowercase() in ICON_EXTENSIONS) {
                    zip.writeEntry("$SYSICONS_PREFIX$platformId.${image.extension.lowercase()}", image.bytes)
                }
            }
            // Streamed, never held: copyTo pulls from the motion's own source (a file on disk,
            // usually) straight into the zip.
            bundle.motion?.let { motion ->
                val ext = motion.extension.lowercase()
                if (ext in MOTION_EXTENSIONS) {
                    zip.putNextEntry(ZipEntry("$MOTION_PREFIX$ext"))
                    motion.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
    }

    fun write(bundle: PfpThemeBundle): ByteArray =
        ByteArrayOutputStream().also { write(bundle, it) }.toByteArray()

    /**
     * Returns null when [input] is not a `.pfptheme` (no manifest, bad JSON, or not the
     * pfptheme manifest type).
     *
     * [reopen] turns the motion entry's extension into a [ThemeMotion] that can stream the entry
     * on demand. It is a parameter rather than something this function can work out for itself
     * because a stream is one-pass: by the time the caller has the bundle, the bytes are gone,
     * and only whoever supplied the stream knows how to get them again. Callers that pass null
     * (the plain-stream overload) get `motion == null` — the entry is still read and counted
     * against the caps, it is simply not recoverable afterwards.
     */
    @JvmOverloads
    fun read(input: InputStream, reopen: ((String) -> ThemeMotion)? = null): PfpThemeBundle? {
        var manifest: PfpThemeManifest? = null
        var wallpaper: ByteArray? = null
        var preview: ByteArray? = null
        val icons = mutableMapOf<String, ThemeImage>()
        val sysicons = mutableMapOf<String, ThemeImage>()
        var motionExtension: String? = null

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
                    entry.name.startsWith(ICONS_PREFIX) -> {
                        // Only registered slot keys with an accepted extension are accepted — an
                        // icon entry can never smuggle a path (`icons/../x`) or an unexpected
                        // name into the app.
                        val name = entry.name.removePrefix(ICONS_PREFIX)
                        val key = name.substringBeforeLast('.')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in ICON_EXTENSIONS && IconSlots.isValidKey(key)) {
                            entry.readBytes()
                                .takeIf { it.size <= MAX_ICON_BYTES }
                                ?.let { icons[key] = ThemeImage(it, ext) }
                        }
                    }
                    entry.name.startsWith(SYSICONS_PREFIX) -> {
                        // Console art: the key is the platform id; the registry gates it under
                        // its sysicon_ key (which excludes the sysicon_default fallback art).
                        val name = entry.name.removePrefix(SYSICONS_PREFIX)
                        val platformId = name.substringBeforeLast('.')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in ICON_EXTENSIONS && CustomizableIcons.isValidKey("sysicon_$platformId")) {
                            entry.readBytes()
                                .takeIf { it.size <= MAX_ICON_BYTES }
                                ?.let { sysicons[platformId] = ThemeImage(it, ext) }
                        }
                    }
                    entry.name.startsWith(MOTION_PREFIX) -> {
                        // Deliberately NOT read here. Only the extension is recorded; the caller's
                        // reopen strategy decides how (and whether) the content is ever streamed.
                        // The entry is still drained by the reader, so it is counted against the
                        // caps exactly as before — it just never lands on the heap.
                        val ext = entry.name.removePrefix(MOTION_PREFIX).lowercase()
                        if (ext in MOTION_EXTENSIONS) motionExtension = ext
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
        return PfpThemeBundle(
            manifest = m,
            wallpaper = wallpaper,
            preview = preview,
            icons = icons,
            sysicons = sysicons,
            motion = motionExtension?.let { ext -> reopen?.invoke(ext) },
        )
    }

    /**
     * Reads a bundle held in memory. Motion streams back out of [bytes] — already on the heap,
     * so re-scanning them costs nothing extra.
     */
    fun read(bytes: ByteArray): PfpThemeBundle? =
        read(ByteArrayInputStream(bytes)) { ext -> motionFrom({ ByteArrayInputStream(bytes) }, ext) }

    /**
     * Reads a bundle from a file **without inflating its motion entry**.
     *
     * This is the overload the launcher wants for anything on disk. The returned
     * [PfpThemeBundle.motion] re-opens [file] and streams that one entry when asked, so applying
     * a theme with a 50 MB video copies it file-to-file and never holds it.
     */
    fun read(file: File): PfpThemeBundle? =
        file.inputStream().use { read(it) { ext -> motionFrom({ file.inputStream() }, ext) } }

    /**
     * The manifest alone, at O(first entry) cost.
     *
     * [write] emits `manifest.json` first, so this stops the read there and never touches the
     * wallpaper, the icons, or the video behind them. Listing the saved-theme library used to
     * call full [read] per file purely to recover a name and an accent colour — which inflated
     * every motion wallpaper in the library on every scan, and dropped any theme too big to
     * inflate, so a large theme imported successfully and then simply never appeared.
     */
    fun readManifest(file: File): PfpThemeManifest? {
        var manifest: PfpThemeManifest? = null
        try {
            file.inputStream().use { input ->
                BoundedZipReader.read(input, BUNDLE_LIMITS) { entry ->
                    if (entry.name == ENTRY_MANIFEST) {
                        manifest = runCatching {
                            json.decodeFromString(
                                PfpThemeManifest.serializer(),
                                entry.readBytes().decodeToString(),
                            )
                        }.getOrNull()
                        entry.stop()
                    }
                }
            }
        } catch (e: ZipLimitExceededException) {
            return null
        }
        return manifest?.takeIf { it.manifest == PfpThemeManifest.MANIFEST_TYPE }
    }

    /**
     * A [ThemeMotion] that finds `motion.<ext>` by re-reading [source] through the same bounded
     * reader, so a reopened entry is held to the same caps as the first pass and is streamed
     * rather than inflated.
     */
    private fun motionFrom(source: () -> InputStream, ext: String): ThemeMotion =
        ThemeMotion(ext) { out ->
            var written = 0L
            source().use { input ->
                BoundedZipReader.read(input, BUNDLE_LIMITS) { entry ->
                    if (entry.name == "$MOTION_PREFIX$ext") {
                        written = entry.copyTo(out)
                        entry.stop()
                    }
                }
            }
            written
        }

    private fun ZipOutputStream.writeEntry(name: String, data: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(data)
        closeEntry()
    }

}
