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
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Reader/writer for `.pfptheme` bundles — a plain zip:
 *
 * ```
 * mytheme.pfptheme
 * ├── manifest.json                        (required; written first; schemaVersion 4)
 * ├── wallpaper.png                        (optional; absent -> live wave background)
 * ├── preview.png                          (optional on read; the app's preview gate writes one)
 * ├── icons/<key>.<png|gif>                (v2 as png-only; v3 widens to gif; v4: 81 keys)
 * ├── sysicons/<platformId>.<png|gif>      (v3; console art; v4: 47 ids)
 * ├── motion.<mp4|webm|gif>                (v3; motion wallpaper)
 * ├── sounds/<sound_key>.<mp3|wav|ogg|m4a> (v4; five menu sounds, streamed)
 * ├── ambience.<mp3|wav|ogg|m4a>           (v4; streamed)
 * ├── boot.<mp4|webm>, gameboot.<mp4|webm> (v4; streamed)
 * └── <anything else>                      (v4; safe names kept as passthrough, streamed)
 * ```
 *
 * Image entries are opaque bytes here — frontends do the encoding.
 *
 * Every change since v2 is additive: unknown manifest fields are preserved ([PfpThemeBundle.manifestExtras]),
 * unknown zip entries with safe names are preserved ([PassthroughEntry]), and the readers never
 * gate on schemaVersion, so a v4 bundle still opens on older builds (they see the subset they
 * know). `sysicons/` gating rides on [CustomizableIcons]'s console keys, `icons/` on
 * [IconSlots], and media entries on [ThemeMediaSlots]. Format reference: docs/theme-format.md.
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
            zip.write(manifestJson(bundle).toByteArray())
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
            // UI media, streamed the same way. Only registered keys in an accepted container for
            // their kind are written; byte caps are enforced on read and by the callers' gates.
            for ((key, media) in bundle.media.toSortedMap()) {
                val name = ThemeMediaSlots.entryName(key, media.extension) ?: continue
                zip.putNextEntry(ZipEntry(name))
                media.copyTo(zip)
                zip.closeEntry()
            }
            // Unknown entries last, streamed. A name that is unsafe, collides with a typed entry,
            // or repeats is skipped, so nothing hostile or ambiguous reaches the written zip.
            val written = mutableSetOf<String>()
            for (entry in bundle.passthrough) {
                if (PassthroughNames.isSafe(entry.name) && !isRegisteredName(entry.name) && written.add(entry.name)) {
                    zip.putNextEntry(ZipEntry(entry.name))
                    entry.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
    }

    /** Typed manifest merged over the preserved unknown keys; typed fields win on collision. */
    private fun manifestJson(bundle: PfpThemeBundle): String {
        val typed = json.encodeToJsonElement(PfpThemeManifest.serializer(), bundle.manifest.forWrite()).jsonObject
        val merged = JsonObject(typed + bundle.manifestExtras.filterKeys { it !in typed })
        return json.encodeToString(JsonObject.serializer(), merged)
    }

    /**
     * Copies the bundle in [input] to [out] with only the manifest's name changed. Entries stream
     * through untouched, so a motion wallpaper is never held and every other byte is preserved.
     * The manifest is edited as raw JSON, so unknown (newer-format) keys and the schemaVersion
     * survive the rename exactly as they were.
     * Returns false (having written nothing useful) when [input] has no readable manifest.
     */
    fun rewriteName(input: InputStream, out: OutputStream, name: String): Boolean {
        var renamed = false
        ZipInputStream(input).use { zin ->
            ZipOutputStream(out).use { zip ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    zip.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == ENTRY_MANIFEST) {
                        val raw = runCatching {
                            val obj = json.parseToJsonElement(zin.readBytes().decodeToString()).jsonObject
                            // Must still be a readable manifest; the decode is the check.
                            json.decodeFromJsonElement(PfpThemeManifest.serializer(), obj)
                            obj
                        }.getOrNull()
                        if (raw != null) {
                            val updated = JsonObject(raw + ("name" to JsonPrimitive(name)))
                            zip.write(json.encodeToString(JsonObject.serializer(), updated).toByteArray())
                            renamed = true
                        }
                    } else {
                        zin.copyTo(zip)
                    }
                    zip.closeEntry()
                }
            }
        }
        return renamed
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
    fun read(
        input: InputStream,
        reopen: ((String) -> ThemeMotion)? = null,
        reopenEntry: ((String) -> PassthroughEntry)? = null,
    ): PfpThemeBundle? = readDetailed(input, reopen, reopenEntry)?.bundle

    /**
     * [read] plus a [ReadDiagnostics] of what was dropped or repaired on the way in. Same null
     * contract as [read]; the bundle is identical.
     */
    @JvmOverloads
    fun readDetailed(
        input: InputStream,
        reopen: ((String) -> ThemeMotion)? = null,
        reopenEntry: ((String) -> PassthroughEntry)? = null,
    ): ReadResult? {
        var manifest: PfpThemeManifest? = null
        val dropped = mutableListOf<DroppedEntry>()
        val undecodable = mutableListOf<String>()
        var manifestExtras = JsonObject(emptyMap())
        val passthrough = mutableListOf<PassthroughEntry>()
        val seenPassthrough = mutableSetOf<String>()
        val unrecoverable = mutableListOf<String>()
        var wallpaper: ByteArray? = null
        var preview: ByteArray? = null
        val icons = mutableMapOf<String, ThemeImage>()
        val sysicons = mutableMapOf<String, ThemeImage>()
        var motionExtension: String? = null
        val media = mutableMapOf<String, ThemeMotion>()
        val seenMedia = mutableSetOf<String>()

        // BoundedZipReader supplies the caps. This reader used to bound memory per entry but never
        // counted entries, so a small bundle of repeated wallpaper entries was an unbounded hang —
        // re-triggered on every PfpThemeStore.scan().
        try {
            BoundedZipReader.read(input, BUNDLE_LIMITS) { entry ->
                when {
                    entry.name == ENTRY_MANIFEST -> {
                        // Parsed to a tree first so the keys no typed field claims can be kept.
                        val text = entry.readBytes().decodeToString()
                        manifest = decodeManifest(text, undecodable)
                        manifestExtras = runCatching { extrasOf(json.parseToJsonElement(text).jsonObject) }
                            .getOrDefault(JsonObject(emptyMap()))
                    }
                    entry.name == ENTRY_WALLPAPER -> wallpaper = entry.readBytes()
                    entry.name == ENTRY_PREVIEW -> preview = entry.readBytes()
                    entry.name.startsWith(ICONS_PREFIX) && isRegisteredName(entry.name) -> {
                        // Only registered slot keys with an accepted extension are accepted — an
                        // icon entry can never smuggle a path (`icons/../x`) or an unexpected
                        // name into the app.
                        val name = entry.name.removePrefix(ICONS_PREFIX)
                        val key = name.substringBeforeLast('.')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in ICON_EXTENSIONS && IconSlots.isValidKey(key)) {
                            val bytes = entry.readBytes()
                            if (bytes.size <= MAX_ICON_BYTES) icons[key] = ThemeImage(bytes, ext)
                            else dropped += DroppedEntry(entry.name, DropReason.OVER_CAP)
                        }
                    }
                    entry.name.startsWith(SYSICONS_PREFIX) && isRegisteredName(entry.name) -> {
                        // Console art: the key is the platform id; the registry gates it under
                        // its sysicon_ key (sysicon_default is a slot too, since v4).
                        val name = entry.name.removePrefix(SYSICONS_PREFIX)
                        val platformId = name.substringBeforeLast('.')
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in ICON_EXTENSIONS && CustomizableIcons.isValidKey("sysicon_$platformId")) {
                            val bytes = entry.readBytes()
                            if (bytes.size <= MAX_ICON_BYTES) sysicons[platformId] = ThemeImage(bytes, ext)
                            else dropped += DroppedEntry(entry.name, DropReason.OVER_CAP)
                        }
                    }
                    entry.name.startsWith(MOTION_PREFIX) && isRegisteredName(entry.name) -> {
                        // Deliberately NOT read here. Only the extension is recorded; the caller's
                        // reopen strategy decides how (and whether) the content is ever streamed.
                        // The entry is still drained by the reader, so it is counted against the
                        // caps exactly as before — it just never lands on the heap.
                        val ext = entry.name.removePrefix(MOTION_PREFIX).lowercase()
                        if (ext in MOTION_EXTENSIONS) motionExtension = ext
                    }
                    // UI media: a name a slot claims is never passthrough. Like motion it is drained
                    // (counted, so the byte cap is checked against what actually inflates) and
                    // never held; the caller's reopenEntry decides whether it stays recoverable.
                    ThemeMediaSlots.claimedBy(entry.name) != null && PassthroughNames.isSafe(entry.name) -> {
                        val slot = ThemeMediaSlots.claimedBy(entry.name)!!
                        val ext = entry.name.substringAfterLast('.')
                        when {
                            !slot.accepts(ext) -> {
                                entry.copyTo(OutputStream.nullOutputStream())
                                dropped += DroppedEntry(entry.name, DropReason.UNSUPPORTED_MEDIA)
                            }
                            slot.key in seenMedia -> {
                                entry.copyTo(OutputStream.nullOutputStream())
                                dropped += DroppedEntry(entry.name, DropReason.DUPLICATE)
                            }
                            else -> {
                                val size = entry.copyTo(OutputStream.nullOutputStream())
                                if (size > slot.maxBytes) {
                                    dropped += DroppedEntry(entry.name, DropReason.OVER_CAP)
                                } else {
                                    seenMedia += slot.key
                                    reopenEntry?.let { reopen ->
                                        val source = reopen(entry.name)
                                        media[slot.key] = ThemeMotion(ext) { out -> source.copyTo(out) }
                                    }
                                }
                            }
                        }
                    }
                    // Unknown entries are kept for a lossless re-write (never extracted, never
                    // applied) when their name is safe; otherwise dropped and listed. The reader
                    // still drains them, so they count against the caps.
                    entry.isDirectory -> Unit
                    PassthroughNames.isSafe(entry.name) -> {
                        if (seenPassthrough.add(entry.name)) {
                            reopenEntry?.let { passthrough += it(entry.name) }
                        } else {
                            dropped += DroppedEntry(entry.name, DropReason.DUPLICATE)
                        }
                    }
                    else -> {
                        unrecoverable += entry.name
                        dropped += DroppedEntry(entry.name, unsafeNameReason(entry.name))
                    }
                }
            }
        } catch (e: ZipLimitExceededException) {
            // Same contract as before: an unreadable bundle is "not a .pfptheme", not a crash.
            return null
        }

        val m = manifest ?: return null
        if (m.manifest != PfpThemeManifest.MANIFEST_TYPE) return null
        val sanitized = m.sanitized()
        val bundle = PfpThemeBundle(
            manifest = sanitized,
            wallpaper = wallpaper,
            preview = preview,
            icons = icons,
            sysicons = sysicons,
            motion = motionExtension?.let { ext -> reopen?.invoke(ext) },
            manifestExtras = manifestExtras,
            passthrough = passthrough,
            unrecoverableEntries = unrecoverable,
            media = media,
        )
        return ReadResult(bundle, ReadDiagnostics(dropped, sanitizeRepairs(m, sanitized), undecodable))
    }

    /**
     * The strict decode, falling back to field-by-field recovery so one wrongly-typed optional
     * field costs that field rather than the whole theme. The required trio (type, name, accent)
     * must decode on its own or the file is not a theme. Fields recovered from are appended to
     * [undecodable].
     */
    private fun decodeManifest(text: String, undecodable: MutableList<String>): PfpThemeManifest? {
        runCatching { json.decodeFromString(PfpThemeManifest.serializer(), text) }.getOrNull()?.let { return it }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val required = setOf("manifest", "name", "accentColor")
        var kept = JsonObject(root.filterKeys { it in required })
        var result = runCatching { json.decodeFromJsonElement(PfpThemeManifest.serializer(), kept) }
            .getOrNull() ?: return null
        for ((key, value) in root) {
            if (key in required || key !in TYPED_MANIFEST_KEYS) continue
            val candidate = JsonObject(kept + (key to value))
            val decoded = runCatching { json.decodeFromJsonElement(PfpThemeManifest.serializer(), candidate) }.getOrNull()
            if (decoded != null) {
                kept = candidate
                result = decoded
            } else {
                undecodable += key
            }
        }
        return result
    }

    /** Human lines for what [sanitized] changed relative to the [raw] decode. */
    private fun sanitizeRepairs(raw: PfpThemeManifest, sanitized: PfpThemeManifest): List<String> = buildList {
        if ((raw.description?.length ?: 0) > MANIFEST_DESCRIPTION_MAX) {
            add("Description shortened to $MANIFEST_DESCRIPTION_MAX characters")
        }
        if (raw.subTextColor != null && sanitized.subTextColor == null) {
            add("Sub text color \"${raw.subTextColor}\" is not a valid #RRGGBB value and was ignored")
        }
        raw.legibility?.let { l ->
            if (l.text != null && l.text !in ThemeLegibility.TEXT_VALUES) {
                add("Text legibility style \"${l.text}\" is not recognized and was ignored")
            }
            if (l.icon != null && l.icon !in ThemeLegibility.ICON_VALUES) {
                add("Icon legibility style \"${l.icon}\" is not recognized and was ignored")
            }
        }
        raw.motionCrop?.let {
            when {
                sanitized.motionCrop == null -> add("Motion crop was invalid and was ignored")
                sanitized.motionCrop != it -> add("Motion crop adjusted to fit inside the frame")
            }
        }
    }

    /** Why a name failed [PassthroughNames.isSafe]: no usable extension, or anything else. */
    private fun unsafeNameReason(name: String): DropReason {
        val ext = name.substringAfterLast('.', "")
        val hasExt = '.' in name && ext.length in 1..5 && ext.all { it in 'a'..'z' || it in '0'..'9' }
        return if (hasExt) DropReason.HOSTILE_NAME else DropReason.BAD_EXTENSION
    }

    /** Manifest keys no typed [PfpThemeManifest] field claims. */
    private fun extrasOf(root: JsonObject): JsonObject =
        JsonObject(root.filterKeys { it !in TYPED_MANIFEST_KEYS })

    private val TYPED_MANIFEST_KEYS: Set<String> by lazy {
        val d = PfpThemeManifest.serializer().descriptor
        (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
    }

    /**
     * True for any name this codec reads or writes as a typed part of the bundle. A passthrough
     * entry must never share one of these names (plan 5.5).
     */
    private fun isRegisteredName(name: String): Boolean = when {
        name == ENTRY_MANIFEST || name == ENTRY_WALLPAPER || name == ENTRY_PREVIEW -> true
        name.startsWith(ICONS_PREFIX) -> {
            val file = name.removePrefix(ICONS_PREFIX)
            file.substringAfterLast('.', "").lowercase() in ICON_EXTENSIONS &&
                IconSlots.isValidKey(file.substringBeforeLast('.'))
        }
        name.startsWith(SYSICONS_PREFIX) -> {
            val file = name.removePrefix(SYSICONS_PREFIX)
            file.substringAfterLast('.', "").lowercase() in ICON_EXTENSIONS &&
                CustomizableIcons.isValidKey("sysicon_${file.substringBeforeLast('.')}")
        }
        name.startsWith(MOTION_PREFIX) -> name.removePrefix(MOTION_PREFIX).lowercase() in MOTION_EXTENSIONS
        // Any name a media slot claims, even with an extension it would refuse: the reader drops
        // those, so a passthrough entry must not smuggle one back in.
        ThemeMediaSlots.claimedBy(name) != null -> true
        else -> false
    }

    /**
     * Reads a bundle held in memory. Motion streams back out of [bytes] — already on the heap,
     * so re-scanning them costs nothing extra.
     */
    fun read(bytes: ByteArray): PfpThemeBundle? = readDetailed(bytes)?.bundle

    fun readDetailed(bytes: ByteArray): ReadResult? =
        readDetailed(
            ByteArrayInputStream(bytes),
            { ext -> motionFrom({ ByteArrayInputStream(bytes) }, ext) },
            { name -> passthroughFrom({ ByteArrayInputStream(bytes) }, name) },
        )

    /**
     * Reads a bundle from a file **without inflating its motion entry**.
     *
     * This is the overload the launcher wants for anything on disk. The returned
     * [PfpThemeBundle.motion] re-opens [file] and streams that one entry when asked, so applying
     * a theme with a 50 MB video copies it file-to-file and never holds it.
     */
    fun read(file: File): PfpThemeBundle? = readDetailed(file)?.bundle

    fun readDetailed(file: File): ReadResult? =
        file.inputStream().use {
            readDetailed(
                it,
                { ext -> motionFrom({ file.inputStream() }, ext) },
                { name -> passthroughFrom({ file.inputStream() }, name) },
            )
        }

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
        ThemeMotion(ext, streamEntry(source, "$MOTION_PREFIX$ext"))

    /** Same strategy as [motionFrom], for an unknown entry found by its full zip [name]. */
    internal fun passthroughFrom(source: () -> InputStream, name: String): PassthroughEntry =
        PassthroughEntry(name, streamEntry(source, name))

    private fun streamEntry(source: () -> InputStream, name: String): (OutputStream) -> Long = { out ->
        var written = 0L
        source().use { input ->
            BoundedZipReader.read(input, BUNDLE_LIMITS) { entry ->
                if (entry.name == name) {
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
