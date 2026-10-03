package com.playfieldportal.studio.io

import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeCodec
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.ThemeUpgrade
import com.playfieldportal.themekit.UpgradeReport
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One theme the batch rewrote: where its untouched original now lives, and what changed. */
data class UpgradedTheme(val name: String, val backup: File, val report: UpgradeReport)

data class UpgradeBatchSummary(
    val upgraded: List<UpgradedTheme>,
    /** Already v4 with nothing to add or repair; not touched. */
    val alreadyCurrent: List<String>,
    /** Written by a newer app than this build; not touched (rewriting would drop what we can't see). */
    val newerVersion: List<String>,
    /** Unreadable or failed to write; the original is untouched. Name to reason. */
    val failed: List<Pair<String, String>>,
    /** The human-readable report written beside the themes; null when the run changed nothing. */
    val reportFile: File?,
)

/**
 * "Upgrade folder": every `.pfptheme` in a folder to the current format, in place. Each original
 * is copied to `<name>.pfptheme.bak` first (never over an existing backup), the upgraded bundle is
 * written to a temp file and re-read before it replaces the original, so a failure at any step
 * leaves the original as it was. Runs synchronously; callers dispatch to IO. Re-running is a no-op.
 */
object UpgradeBatch {

    private const val MAX_BATCH_FILES = 500
    private const val REPORT_NAME = "upgrade-report"

    fun run(
        dir: File,
        today: String,
        onProgress: (BatchProgress) -> Unit = {},
    ): UpgradeBatchSummary {
        val files = dir.listFiles { f ->
            f.isFile && f.extension.lowercase() == PfpThemeCodec.FILE_EXTENSION
        }?.sortedBy { it.name.lowercase() }.orEmpty().take(MAX_BATCH_FILES)

        val upgraded = mutableListOf<UpgradedTheme>()
        val current = mutableListOf<String>()
        val newer = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()

        files.forEachIndexed { index, file ->
            onProgress(BatchProgress(done = index, total = files.size, current = file.name))
            val read = runCatching { PfpThemeCodec.readDetailed(file) }
            val result = read.getOrNull()
            when {
                result == null -> failed += file.name to
                    (read.exceptionOrNull()?.message ?: "not a valid .pfptheme bundle")
                result.bundle.manifest.schemaVersion > PfpThemeManifest.SCHEMA_VERSION -> newer += file.name
                else -> {
                    val report = ThemeUpgrade.report(result.bundle, result.diagnostics)
                    val needsUpgrade = result.bundle.manifest.schemaVersion != PfpThemeManifest.SCHEMA_VERSION ||
                        report.added.isNotEmpty() || report.repaired.isNotEmpty()
                    if (!needsUpgrade) current += file.name
                    else runCatching { rewrite(file, ThemeUpgrade.upgrade(result.bundle, today)) }
                        .onSuccess { upgraded += UpgradedTheme(file.name, it, report) }
                        .onFailure { failed += file.name to (it.message ?: "write error") }
                }
            }
        }
        onProgress(BatchProgress(done = files.size, total = files.size, current = ""))

        val summary = UpgradeBatchSummary(upgraded, current, newer, failed, reportFile = null)
        val reportFile = if (upgraded.isEmpty() && failed.isEmpty() && newer.isEmpty()) null
        else runCatching { File(dir, uniqueName(dir, REPORT_NAME, ".txt")).also { it.writeText(reportText(summary)) } }
            .getOrNull()
        return summary.copy(reportFile = reportFile)
    }

    /** Backs [file] up, then replaces it with [upgraded]. Returns the backup. On failure the original is untouched and the copy is removed. */
    private fun rewrite(file: File, upgraded: PfpThemeBundle): File {
        val dir = file.parentFile
        val backup = File(dir, uniqueName(dir, "${file.name}.bak", ""))
        Files.copy(file.toPath(), backup.toPath())
        val temp = File(dir, "${file.name}.upgrade.tmp")
        try {
            temp.outputStream().use { PfpThemeCodec.write(upgraded, it) }
            // Never swap in something we could not read back at the current version.
            val check = PfpThemeCodec.readDetailed(temp)
            check(check != null && check.bundle.manifest.schemaVersion == PfpThemeManifest.SCHEMA_VERSION) {
                "upgraded file failed verification"
            }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            temp.delete()
            backup.delete() // the original is untouched, so the copy we just made is redundant
            throw t
        }
        return backup
    }

    /** `base`, `base.2`, ... — the first name in [dir] that does not exist (`base.bak`, `base.bak.2`; `report.txt`, `report-2.txt`). */
    private fun uniqueName(dir: File, base: String, suffix: String): String {
        if (!File(dir, base + suffix).exists()) return base + suffix
        var n = 2
        val sep = if (suffix.isEmpty()) "." else "-"
        while (File(dir, "$base$sep$n$suffix").exists()) n++
        return "$base$sep$n$suffix"
    }

    fun reportText(summary: UpgradeBatchSummary): String = buildString {
        appendLine("PlayField Portal Studio - theme upgrade report")
        appendLine()
        appendLine("Upgraded: ${summary.upgraded.size}   Already current: ${summary.alreadyCurrent.size}   " +
            "Newer version: ${summary.newerVersion.size}   Failed: ${summary.failed.size}")
        if (summary.upgraded.isNotEmpty()) {
            appendLine()
            appendLine("Upgraded (originals kept as .bak):")
            for (u in summary.upgraded) {
                appendLine("  ${u.name}  (backup: ${u.backup.name})")
                section("Kept", u.report.kept)
                section("Added", u.report.added)
                section("Repaired", u.report.repaired)
                section("Could not recover", u.report.cantRecover)
            }
        }
        if (summary.alreadyCurrent.isNotEmpty()) {
            appendLine()
            appendLine("Already current:")
            summary.alreadyCurrent.forEach { appendLine("  $it") }
        }
        if (summary.newerVersion.isNotEmpty()) {
            appendLine()
            appendLine("Made by a newer version (left untouched):")
            summary.newerVersion.forEach { appendLine("  $it") }
        }
        if (summary.failed.isNotEmpty()) {
            appendLine()
            appendLine("Failed (left untouched):")
            summary.failed.forEach { (name, reason) -> appendLine("  $name: $reason") }
        }
    }

    private fun StringBuilder.section(label: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine("    $label:")
        lines.forEach { appendLine("      - $it") }
    }
}
