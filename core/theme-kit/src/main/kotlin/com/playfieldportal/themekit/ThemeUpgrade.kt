package com.playfieldportal.themekit

/** Why [PfpThemeCodec.readDetailed] dropped a zip entry. */
enum class DropReason {
    /** A registered entry over its size cap (icons: [PfpThemeCodec.MAX_ICON_BYTES]). */
    OVER_CAP,

    /** A name with no usable extension. */
    BAD_EXTENSION,

    /** A name that failed the safe-name rule (plan 5.5): traversal, wrong case, too deep or long. */
    HOSTILE_NAME,

    /** A second entry with a name already seen. */
    DUPLICATE,

    /** A name a media slot claims (`boot.gif`, `sounds/sound_back.mp4`) in a container that slot refuses. */
    UNSUPPORTED_MEDIA,
}

data class DroppedEntry(val name: String, val reason: DropReason)

/** What a read dropped or repaired. All empty for a clean bundle. */
data class ReadDiagnostics(
    val dropped: List<DroppedEntry> = emptyList(),
    /** Human lines for read-time repairs (sanitized description, unknown enum, crop). */
    val repaired: List<String> = emptyList(),
    /** Manifest fields that could not be decoded and were reset to their defaults. */
    val undecodableFields: List<String> = emptyList(),
)

/** A bundle plus the diagnostics of the read that produced it. */
data class ReadResult(val bundle: PfpThemeBundle, val diagnostics: ReadDiagnostics)

/**
 * What an upgrade keeps, adds, repairs, and cannot recover, as human strings for the UI.
 * `cantRecover` is what is already lost: it is not in the bundle any more and no upgrade can
 * bring it back.
 */
data class UpgradeReport(
    val kept: List<String>,
    val added: List<String>,
    val repaired: List<String>,
    val cantRecover: List<String>,
)

/** Brings any readable bundle to the current format without losing anything it understood. */
object ThemeUpgrade {

    /** The accent a malformed `accentColor` is reset to (matches the Studio's default accent). */
    const val FALLBACK_ACCENT = "#0055AA"

    private val HEX = Regex("^#[0-9A-Fa-f]{6}$")

    /**
     * Describes what [upgrade] would do to [bundle] plus what the read that produced it lost.
     * Pass [ReadDiagnostics] defaults when the read is not at hand; the bundle alone still yields
     * kept, added and the upgrade-time repairs.
     */
    fun report(bundle: PfpThemeBundle, diagnostics: ReadDiagnostics): UpgradeReport {
        val m = bundle.manifest
        val kept = buildList {
            if (bundle.wallpaper != null) add("Wallpaper")
            if (bundle.preview != null) add("Preview image")
            if (bundle.icons.isNotEmpty()) add(count(bundle.icons.size, "custom icon"))
            if (bundle.sysicons.isNotEmpty()) add(count(bundle.sysicons.size, "console icon"))
            bundle.motion?.let { add("Motion wallpaper (${it.extension})") }
            val sounds = bundle.media.keys.count { ThemeMediaSlots.slot(it)?.kind == UiMediaLimits.Kind.SOUND }
            if (sounds > 0) add(count(sounds, "menu sound"))
            if ("ambience_audio" in bundle.media) add("Ambience track")
            if ("boot_video" in bundle.media) add("Boot animation")
            if ("gameboot_video" in bundle.media) add("GameBoot animation")
            if (m.layout != null) add("Custom layout")
            if (bundle.passthrough.isNotEmpty()) add("${count(bundle.passthrough.size, "unrecognized file")} kept as-is")
            if (bundle.manifestExtras.isNotEmpty()) {
                add("Unrecognized settings kept: ${bundle.manifestExtras.keys.sorted().joinToString(", ")}")
            }
        }
        val added = buildList {
            if (m.schemaVersion != PfpThemeManifest.SCHEMA_VERSION) {
                add("Format version ${PfpThemeManifest.SCHEMA_VERSION} (was ${m.schemaVersion})")
            }
            if (m.waveStyleV4 == null) add("Exact wave style")
            if (m.created == null) add("Creation date")
            if (m.updated == null) add("Last-updated date")
        }
        val repaired = diagnostics.repaired + repair(m).second
        val cantRecover = buildList {
            for (d in diagnostics.dropped) add("${d.name} (${describe(d)})")
            for (name in bundle.unrecoverableEntries) {
                if (diagnostics.dropped.none { it.name == name }) add("$name (unsafe file name)")
            }
            for (field in diagnostics.undecodableFields) add("Setting \"$field\" could not be read and was reset")
        }
        return UpgradeReport(kept, added, repaired, cantRecover)
    }

    /**
     * [bundle] with a current-format manifest: version stamped, legacy and exact wave written,
     * malformed colours repaired, `created` preserved or backfilled with [today], `updated`
     * set to [today]. Extras and passthrough ride along untouched. Idempotent for a given [today].
     */
    fun upgrade(bundle: PfpThemeBundle, today: String): PfpThemeBundle {
        val repaired = repair(bundle.manifest).first
        val exact = WaveStyles.resolveExact(repaired)
        val (legacy, v4) = WaveStyles.encode(exact)
        return bundle.copy(
            manifest = repaired.copy(
                schemaVersion = PfpThemeManifest.SCHEMA_VERSION,
                waveStyle = legacy,
                waveStyleV4 = v4,
                created = repaired.created ?: today,
                updated = today,
            ),
        )
    }

    /** The manifest with malformed colours and an unrecognized wave reset, and a line per repair. */
    private fun repair(m: PfpThemeManifest): Pair<PfpThemeManifest, List<String>> {
        val lines = mutableListOf<String>()
        var out = m
        if (!HEX.matches(m.accentColor)) {
            lines += "Accent color \"${m.accentColor}\" is not a valid #RRGGBB value; reset to $FALLBACK_ACCENT"
            out = out.copy(accentColor = FALLBACK_ACCENT)
        }
        if (m.iconColor != PfpThemeManifest.ICON_COLOR_AUTO && !HEX.matches(m.iconColor)) {
            lines += "Icon color \"${m.iconColor}\" is not a valid #RRGGBB value; reset to auto"
            out = out.copy(iconColor = PfpThemeManifest.ICON_COLOR_AUTO)
        }
        if (m.textColor != PfpThemeManifest.ICON_COLOR_AUTO && !HEX.matches(m.textColor)) {
            lines += "Text color \"${m.textColor}\" is not a valid #RRGGBB value; reset to auto"
            out = out.copy(textColor = PfpThemeManifest.ICON_COLOR_AUTO)
        }
        val v4 = m.waveStyleV4
        val resolved = WaveStyles.resolveExact(m)
        // Unrecognized means the field that decided the outcome had to be skipped: a present but
        // unknown waveStyleV4, or (with no V4 field) a legacy waveStyle outside the legacy set.
        val v4Unknown = v4 != null && !WaveStyles.isExact(v4)
        val legacyUnknown = v4 == null && resolved == PfpThemeManifest.WAVE_ANIMATED &&
            m.waveStyle != PfpThemeManifest.WAVE_ANIMATED
        if (v4Unknown || legacyUnknown) {
            lines += "Wave style \"${if (v4Unknown) v4 else m.waveStyle}\" is not recognized; reset to $resolved"
        }
        return out to lines
    }

    private fun describe(dropped: DroppedEntry): String = when (dropped.reason) {
        DropReason.OVER_CAP -> {
            val slot = ThemeMediaSlots.claimedBy(dropped.name)
            if (slot != null) "larger than the ${slot.maxBytes / (1024 * 1024)} MB limit for this slot"
            else "larger than the ${PfpThemeCodec.MAX_ICON_BYTES / (1024 * 1024)} MB icon limit"
        }
        DropReason.UNSUPPORTED_MEDIA -> "not a supported format for this slot"
        DropReason.BAD_EXTENSION -> "no usable file extension"
        DropReason.HOSTILE_NAME -> "unsafe file name"
        DropReason.DUPLICATE -> "repeated file name"
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
