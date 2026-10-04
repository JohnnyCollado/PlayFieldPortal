package com.playfieldportal.studio

import com.playfieldportal.themekit.MotionLimits
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.ThemeMediaSlots
import com.playfieldportal.themekit.UpgradeReport

/** Where the bytes of the bundle go - the "In this file" budget strip. */
enum class BudgetKind(val label: String) {
    WALLPAPER("Wallpaper"),
    PREVIEW("Preview"),
    ICONS("Icons"),
    MOTION("Motion"),
    SOUNDS("Sounds"),
    AMBIENCE("Ambience"),
    BOOT("Boot"),
    GAMEBOOT("GameBoot"),
    OTHER("Other files"),
}

enum class CheckSeverity { OK, INFO, WARNING, ERROR }

/** The checklist rows of the Export check. */
enum class CheckId { MOTION_POSTER, MEDIA_LIMITS, TEXT_CONTRAST, OLDER_LAUNCHERS, SIZE, UPGRADE }

data class CheckItem(val id: CheckId, val severity: CheckSeverity, val message: String)

/**
 * What the Studio would write, as plain data for the Export-check panel and the contents strip.
 *
 * Byte counts are the payload the bundle will carry (PNG/MP4/audio do not compress, so they track
 * the zip size closely; the manifest is a few KB and ignored). The preview is rendered at export,
 * so only an already-embedded preview is counted.
 *
 * Media durations are NOT re-probed here - that happened at import ([com.playfieldportal.studio.io.MediaGates])
 * and the codec re-checks bytes on read; this re-checks presence and byte caps, the two things that
 * can change between import and export.
 */
data class ExportCheck(
    val bytesByKind: Map<BudgetKind, Long>,
    val items: List<CheckItem>,
    val upgrade: UpgradeReport?,
) {
    val totalBytes: Long get() = bytesByKind.values.sum()
    val warnings: List<CheckItem> get() = items.filter { it.severity == CheckSeverity.WARNING }
    val errors: List<CheckItem> get() = items.filter { it.severity == CheckSeverity.ERROR }

    /** False when an ERROR row means the bundle would be refused or silently lose a part. */
    val canExport: Boolean get() = errors.isEmpty()

    companion object {
        /** Launchers before schema 4 refuse a picked file above this (plan A8). */
        const val PRE_V4_MAX_BYTES = 64L * 1024 * 1024

        /** The bundle's hard cap ([PfpThemeCodec.BUNDLE_LIMITS]). */
        val HARD_MAX_BYTES: Long = PfpThemeCodec.BUNDLE_LIMITS.maxTotalBytes

        private const val MB = 1024L * 1024

        private fun mb(bytes: Long): String = "%.1f MB".format(bytes.toDouble() / MB)

        fun of(state: StudioState): ExportCheck {
            val bytes = linkedMapOf<BudgetKind, Long>()
            fun add(kind: BudgetKind, n: Long) {
                bytes[kind] = (bytes[kind] ?: 0L) + n
            }
            add(BudgetKind.WALLPAPER, (state.wallpaperPng?.size ?: 0).toLong())
            add(BudgetKind.PREVIEW, (state.previewPng?.size ?: 0).toLong())
            add(
                BudgetKind.ICONS,
                state.iconOverrides.values.sumOf { it.size.toLong() },
            )
            add(BudgetKind.MOTION, state.motionFile?.takeIf { it.isFile }?.length() ?: 0L)
            for ((key, file) in state.mediaFiles) {
                val kind = when (key) {
                    "ambience_audio" -> BudgetKind.AMBIENCE
                    "boot_video" -> BudgetKind.BOOT
                    "gameboot_video" -> BudgetKind.GAMEBOOT
                    else -> BudgetKind.SOUNDS
                }
                add(kind, file.takeIf { it.isFile }?.length() ?: 0L)
            }
            add(BudgetKind.OTHER, state.passthroughFiles.values.sumOf { f -> f.takeIf { it.isFile }?.length() ?: 0L })
            val total = bytes.values.sum()

            val items = buildList {
                state.motionFile?.let {
                    add(
                        if (state.wallpaperPng != null) {
                            CheckItem(CheckId.MOTION_POSTER, CheckSeverity.OK, "Motion has a still poster")
                        } else {
                            CheckItem(
                                CheckId.MOTION_POSTER, CheckSeverity.ERROR,
                                "Motion needs a still wallpaper as its poster - add one or remove the video",
                            )
                        },
                    )
                }
                add(mediaLimits(state))
                if (state.wallpaperPng != null) add(textContrast(state))
                add(olderLaunchers(state, total))
                add(size(total))
                state.upgradeReport?.let { add(upgrade(it, state.schemaVersion)) }
            }
            return ExportCheck(bytes, items, state.upgradeReport)
        }

        private fun mediaLimits(state: StudioState): CheckItem {
            val problems = buildList {
                for ((key, file) in state.mediaFiles) {
                    val slot = ThemeMediaSlots.slot(key)
                    when {
                        slot == null -> add("$key (unknown slot)")
                        !file.isFile -> add("$key (file is missing)")
                        file.length() > slot.maxBytes -> add("$key (over ${slot.maxBytes / MB} MB)")
                        !slot.accepts(file.extension) -> add("$key (.${file.extension} not accepted)")
                    }
                }
                state.motionFile?.let { video ->
                    if (!video.isFile) add("motion (file is missing)")
                    else if (video.length() > MotionLimits.MAX_BYTES) add("motion (over ${MotionLimits.MAX_BYTES / MB} MB)")
                }
            }
            return if (problems.isEmpty()) {
                CheckItem(CheckId.MEDIA_LIMITS, CheckSeverity.OK, "All media is within limits")
            } else {
                CheckItem(CheckId.MEDIA_LIMITS, CheckSeverity.ERROR, "Media outside limits: ${problems.joinToString(", ")}")
            }
        }

        private fun textContrast(state: StudioState): CheckItem {
            val shielded = state.legibility?.text.let { it != null && it != "none" }
            return if (state.wallpaperBusy && !shielded) {
                CheckItem(
                    CheckId.TEXT_CONTRAST, CheckSeverity.WARNING,
                    "This wallpaper is busy behind the labels - pick a text legibility style so they stay readable",
                )
            } else {
                CheckItem(CheckId.TEXT_CONTRAST, CheckSeverity.OK, "Text should read clearly on this wallpaper")
            }
        }

        private fun usesV4Only(state: StudioState): Boolean =
            state.mediaFiles.isNotEmpty() || state.legibility != null || state.motionCrop != null ||
                state.textColorExact != null

        private fun olderLaunchers(state: StudioState, total: Long): CheckItem = when {
            total > PRE_V4_MAX_BYTES -> CheckItem(
                CheckId.OLDER_LAUNCHERS, CheckSeverity.WARNING,
                "${mb(total)} - launchers from before this format refuse files over 64 MB",
            )
            usesV4Only(state) -> CheckItem(
                CheckId.OLDER_LAUNCHERS, CheckSeverity.INFO,
                "Opens on older launchers, which ignore sounds, boot clips, legibility and crop",
            )
            else -> CheckItem(CheckId.OLDER_LAUNCHERS, CheckSeverity.OK, "Opens on older launchers")
        }

        private fun size(total: Long): CheckItem =
            if (total > HARD_MAX_BYTES) {
                CheckItem(
                    CheckId.SIZE, CheckSeverity.ERROR,
                    "${mb(total)} is over the ${HARD_MAX_BYTES / MB} MB limit for a theme file",
                )
            } else {
                CheckItem(CheckId.SIZE, CheckSeverity.OK, "${mb(total)} of ${HARD_MAX_BYTES / MB} MB")
            }

        private fun upgrade(report: UpgradeReport, schemaVersion: Int?): CheckItem {
            val from = schemaVersion?.let { "format v$it" } ?: "an older format"
            return if (report.cantRecover.isNotEmpty()) {
                CheckItem(
                    CheckId.UPGRADE, CheckSeverity.WARNING,
                    "Saving upgrades this theme from $from; ${report.cantRecover.size} part(s) can't be recovered",
                )
            } else {
                CheckItem(CheckId.UPGRADE, CheckSeverity.INFO, "Saving upgrades this theme from $from")
            }
        }
    }
}
